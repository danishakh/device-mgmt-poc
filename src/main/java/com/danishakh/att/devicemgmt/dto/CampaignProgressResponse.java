package com.danishakh.att.devicemgmt.dto;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
/**
 * How a campaign is going (HLD §9.1). Also the body returned by create, pause and resume.
 */
@Getter
@Builder
public class CampaignProgressResponse {
    private final UUID id;
    private final String status;
    /** Why the campaign is in its status, for example the numbers behind a halt. */
    private final String statusReason;
    private final String targetVersion;
    private final String fromVersion;
    private final int maxConcurrent;
    /** Device upgrades that hold a slot: NOTIFIED, DOWNLOAD_SENT or ACCEPTED. */
    private final int inFlight;
    /** Devices that joined the campaign, whatever state they are in now. */
    private final int deviceCount;
    /** Devices left out at creation because they were already in another campaign. */
    private final int excludedDeviceCount;
    /** Every state, zeros included. */
    private final Map<String, Integer> countsByState;
    /** Outcome reasons that have occurred. */
    private final Map<String, Integer> reasons;
    /** Evidence that arrived after the outcome, by outcome then evidence, for example ROLLED_BACK then FAULT_9010. */
    private final Map<String, Map<String, Integer>> trailingEvidence;
    /** (FAILED + TIMED_OUT) / (SUCCEEDED + FAILED + TIMED_OUT), or 0 before anything has finished. */
    private final double failureRate;
    private final BigDecimal failureThresholdPercent;
    private final int minSample;
}
