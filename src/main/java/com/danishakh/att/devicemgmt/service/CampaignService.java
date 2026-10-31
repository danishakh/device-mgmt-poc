package com.danishakh.att.devicemgmt.service;
import com.danishakh.att.devicemgmt.client.FleetClient;
import com.danishakh.att.devicemgmt.config.DeviceMgmtProperties;
import com.danishakh.att.devicemgmt.dto.CampaignProgressResponse;
import com.danishakh.att.devicemgmt.dto.CampaignSummaryResponse;
import com.danishakh.att.devicemgmt.dto.CreateCampaignRequest;
import com.danishakh.att.devicemgmt.exception.CampaignCreationException;
import com.danishakh.att.devicemgmt.exception.CampaignMoveNotAllowedException;
import com.danishakh.att.devicemgmt.exception.CampaignNotFoundException;
import com.danishakh.att.devicemgmt.model.Campaign;
import com.danishakh.att.devicemgmt.model.CampaignStatus;
import com.danishakh.att.devicemgmt.model.FleetDevice;
import com.danishakh.att.devicemgmt.model.UpgradeBreakdown;
import com.danishakh.att.devicemgmt.repository.CampaignRepository;
import com.danishakh.att.devicemgmt.repository.DeviceRepository;
import com.danishakh.att.devicemgmt.repository.DeviceUpgradeRepository;
import com.danishakh.att.devicemgmt.statemachine.TrailingEvidence;
import com.danishakh.att.devicemgmt.statemachine.DeviceUpgradeState;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
/** The Campaigns module: creating, viewing, pausing and resuming campaigns. */
@Slf4j
@Service
public class CampaignService {
    private final CampaignRepository campaignRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceUpgradeRepository deviceUpgradeRepository;
    private final FleetClient fleetClient;
    private final Clock clock;
    private final DeviceMgmtProperties properties;
    private final TransactionTemplate transaction;
    public CampaignService(CampaignRepository campaignRepository, DeviceRepository deviceRepository, DeviceUpgradeRepository deviceUpgradeRepository,
            FleetClient fleetClient, Clock clock, DeviceMgmtProperties properties, TransactionTemplate transaction) {
        this.campaignRepository = campaignRepository;
        this.deviceRepository = deviceRepository;
        this.deviceUpgradeRepository = deviceUpgradeRepository;
        this.fleetClient = fleetClient;
        this.clock = clock;
        this.properties = properties;
        this.transaction = transaction;
    }


    /**
     * Snapshots the devices on the requested version into a new campaign. The device list is fetched
     * before the transaction opens, so a slow simulator never holds a database transaction, and a
     * failed fetch ({@code FleetUnavailableException}) creates nothing. Devices that already have an open
     * upgrade are left out and counted in {@code excludedDeviceCount}. A campaign with no devices is
     * created as COMPLETED. A database failure rolls everything back and surfaces as
     * {@link CampaignCreationException}.
     * @param request - the campaign to create
     * @return the progress view of the new campaign
     */
    public CampaignProgressResponse createCampaign(CreateCampaignRequest request) {
        String fromVersion = request.getDeviceFilter().getCurrentVersion();
        List<FleetDevice> eligibleFleetDeviceList = distinctById(fleetClient.listDevices(fromVersion));
        Instant now = clock.instant();
        UUID campaignId = UUID.randomUUID();
        try {
            transaction.executeWithoutResult(status -> {
                campaignRepository.insert(Campaign.builder()
                        .id(campaignId)
                        .targetVersion(request.getTargetVersion())
                        .imageUrl(request.getImageUrl())
                        .fromVersion(fromVersion)
                        .maxConcurrent(request.getMaxConcurrent())
                        .failureThresholdPct(request.getFailureThresholdPercent())
                        .minSample(request.getMinSample() != null
                                ? request.getMinSample()
                                : properties.getCampaign().getDefaultMinSample())
                        .status(CampaignStatus.RUNNING)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());

                int devicesAddedToCampaignCount = 0;
                if (!eligibleFleetDeviceList.isEmpty()) {
                    // Record the devices in the device table, if they are not already there. This is a no-op for devices that are already known, so facts they reported themselves (version, last event) are never overwritten
                    deviceRepository.insertIfAbsent(eligibleFleetDeviceList, now);

                    // Record the devices in the device_upgrade table so the campaign can start upgrading them.
                    devicesAddedToCampaignCount = deviceUpgradeRepository.insert(campaignId, fromVersion,
                            eligibleFleetDeviceList.stream().map(FleetDevice::deviceId).toList(), now);
                }

                // Update the campaign with the number of devices that were left out at creation because they already had an open upgrade.
                int excludedDeviceCount = eligibleFleetDeviceList.size() - devicesAddedToCampaignCount;
                campaignRepository.updateExcludedDevices(campaignId, excludedDeviceCount);

                // If no devices were added to the campaign, mark it as COMPLETED with a reason.
                if (devicesAddedToCampaignCount == 0) {
                    campaignRepository.updateStatus(campaignId, CampaignStatus.RUNNING, CampaignStatus.COMPLETED,
                            "No eligible devices on version " + fromVersion, now);
                }
                log.info("Campaign {} created: {} devices joined, {} devices excluded", campaignId, devicesAddedToCampaignCount, excludedDeviceCount);
            });
        } catch (TransactionException | DataAccessException e) {
            log.error("Could not create campaign {}", campaignId, e);
            throw new CampaignCreationException("Could not create campaign " + campaignId, e);
        }
        return getCampaignById(campaignId);
    }


    /**
     * Shows how a campaign is going: its status, a count for every upgrade state and outcome reason,
     * the evidence that arrived after an outcome, and the failure rate. The counts come from one query,
     * so they agree with each other.
     *
     * @throws CampaignNotFoundException if no campaign has this id
     */
    public CampaignProgressResponse getCampaignById(UUID campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new CampaignNotFoundException(campaignId));

        List<UpgradeBreakdown> breakdown = deviceUpgradeRepository.getCampaignProgressById(campaignId);
        log.debug("getCampaignById() - Campaign {} , UpgradeBreakdown List: {}", campaignId, breakdown);

        Map<DeviceUpgradeState, Integer> byState = new EnumMap<>(DeviceUpgradeState.class);
        Map<String, Integer> reasons = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> trailingEvidence = new LinkedHashMap<>();

        // Aggregate the counts by state, outcome reason, and trailing evidence
        for (UpgradeBreakdown row : breakdown) {
            byState.merge(row.getState(), row.getCount(), Integer::sum);
            if (row.getOutcomeReason() != null) {
                reasons.merge(row.getOutcomeReason().name(), row.getCount(), Integer::sum);
            }
            if (row.getTrailingEvidence() != null) {
                trailingEvidence.computeIfAbsent(trailingEvidenceOutcome(row), key -> new LinkedHashMap<>())
                        .merge(joinTrailingEvidenceAndFaultCode(row), row.getCount(), Integer::sum);
            }
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (DeviceUpgradeState state : DeviceUpgradeState.values()) {
            counts.put(state.name(), byState.getOrDefault(state, 0));
        }
        int inFlight = byState.getOrDefault(DeviceUpgradeState.NOTIFIED, 0)
                + byState.getOrDefault(DeviceUpgradeState.DOWNLOAD_SENT, 0)
                + byState.getOrDefault(DeviceUpgradeState.ACCEPTED, 0);
        int failures = byState.getOrDefault(DeviceUpgradeState.FAILED, 0) + byState.getOrDefault(DeviceUpgradeState.TIMED_OUT, 0);
        int finished = failures + byState.getOrDefault(DeviceUpgradeState.SUCCEEDED, 0);
        log.debug("getCampaignById() - Campaign {} , counts: {}, inFlight: {}, failures: {}, finished: {}", campaignId, counts, inFlight, failures, finished);

        return CampaignProgressResponse.builder()
                .id(campaign.getId())
                .status(campaign.getStatus().name())
                .statusReason(campaign.getStatusReason())
                .targetVersion(campaign.getTargetVersion())
                .fromVersion(campaign.getFromVersion())
                .maxConcurrent(campaign.getMaxConcurrent())
                .inFlight(inFlight)
                .deviceCount(counts.values().stream().mapToInt(Integer::intValue).sum())
                .excludedDeviceCount(campaign.getExcludedDevices())
                .countsByState(counts)
                .reasons(reasons)
                .trailingEvidence(trailingEvidence)
                .failureRate(finished == 0 ? 0.0 : (double) failures / finished)
                .failureThresholdPercent(campaign.getFailureThresholdPct())
                .minSample(campaign.getMinSample())
                .build();
    }

    /** Lists every campaign, newest first. */
    public List<CampaignSummaryResponse> listAllCampaigns() {
        return campaignRepository.findAll().stream()
                .map(campaign -> CampaignSummaryResponse.builder()
                        .id(campaign.getId())
                        .status(campaign.getStatus().name())
                        .statusReason(campaign.getStatusReason())
                        .targetVersion(campaign.getTargetVersion())
                        .fromVersion(campaign.getFromVersion())
                        .maxConcurrent(campaign.getMaxConcurrent())
                        .failureThresholdPercent(campaign.getFailureThresholdPct())
                        .minSample(campaign.getMinSample())
                        .createdAt(campaign.getCreatedAt())
                        .build())
                .toList();
    }

    /**
     * Stops a RUNNING campaign from starting new devices. Devices already in flight carry on.
     *
     * @return the progress view after the move
     * @throws CampaignNotFoundException if no campaign has this id
     * @throws CampaignMoveNotAllowedException if the campaign is not RUNNING
     */
    public CampaignProgressResponse pauseCampaign(UUID campaignId) {
        return moveCampaign(campaignId, CampaignStatus.RUNNING, CampaignStatus.PAUSED, "pause");
    }

    /**
     * Lets a PAUSED campaign start new devices again. A HALTED campaign cannot be resumed.
     *
     * @return the progress view after the move
     * @throws CampaignNotFoundException if no campaign has this id
     * @throws CampaignMoveNotAllowedException if the campaign is not PAUSED
     */
    public CampaignProgressResponse resumeCampaign(UUID campaignId) {
        return moveCampaign(campaignId, CampaignStatus.PAUSED, CampaignStatus.RUNNING, "resume");
    }

    /**
     * Changes the status in one conditional write, so a pause that races a halt or a completion cannot
     * overwrite it. When the write does not win, the campaign is read again to tell "no such campaign"
     * from "wrong status".
     */
    private CampaignProgressResponse moveCampaign(UUID campaignId, CampaignStatus required, CampaignStatus next, String action) {
        log.debug("moveCampaign() - Campaign {} , required: {}, next: {}, action: {}", campaignId, required, next, action);
        boolean moved = campaignRepository.updateStatus(campaignId, required, next, null, clock.instant());

        // Update failed, check if campaign exists or invalid campaign move was attempted and throw appropriate exception
        if (!moved) {
            log.info("moveCampaign() - Unable to {} campaign {}. It is either missing from the database or it is not in the required status {}", action, campaignId, required);
            Campaign campaign = campaignRepository.findById(campaignId)
                    .orElseThrow(() -> new CampaignNotFoundException(campaignId));
            throw new CampaignMoveNotAllowedException(campaignId, campaign.getStatus(), action, required);
        }
        log.info("moveCampaign() - Campaign {} successfully {}d", campaignId, action);
        return getCampaignById(campaignId);
    }

    /** Groups trailing evidence by what the operator saw first: the outcome reason, or TIMED_OUT for a timeout. */
    private static String trailingEvidenceOutcome(UpgradeBreakdown row) {
        return row.getState() == DeviceUpgradeState.TIMED_OUT ? DeviceUpgradeState.TIMED_OUT.name() : row.getOutcomeReason().name();
    }

    /** A fault is shown with its code, for example FAULT_9010. */
    private static String joinTrailingEvidenceAndFaultCode(UpgradeBreakdown row) {
        return row.getTrailingEvidence() == TrailingEvidence.FAULT
                ? "FAULT_" + row.getTrailingFaultCode()
                : row.getTrailingEvidence().name();
    }

    /**
     * Returns a list of devices with distinct device IDs, preserving the order of their first occurrence in the input list.
     * @param listed the list of devices to filter
     * @return a list of devices with distinct device IDs
     */
    private static List<FleetDevice> distinctById(List<FleetDevice> listed) {
        return List.copyOf(listed.stream()
                .collect(Collectors.toMap(FleetDevice::deviceId, Function.identity(), (first, second) -> first,
                        LinkedHashMap::new))
                .values());
    }
}
