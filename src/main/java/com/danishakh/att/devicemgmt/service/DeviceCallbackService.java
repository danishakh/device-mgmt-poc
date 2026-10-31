package com.danishakh.att.devicemgmt.service;

import com.danishakh.att.devicemgmt.dto.CommandResponseRequest;
import com.danishakh.att.devicemgmt.dto.InformRequest;
import com.danishakh.att.devicemgmt.dto.InformResponse;
import com.danishakh.att.devicemgmt.model.WriteOutcome;
import com.danishakh.att.devicemgmt.repository.DeviceRepository;
import com.danishakh.att.devicemgmt.repository.DeviceUpgradeRepository;
import com.danishakh.att.devicemgmt.statemachine.DeviceCallbackResult;
import com.danishakh.att.devicemgmt.statemachine.DeviceUpgradeTransition;
import com.danishakh.att.devicemgmt.statemachine.DeviceUpgradeEvent;
import com.danishakh.att.devicemgmt.statemachine.DeviceUpgradeRow;
import com.danishakh.att.devicemgmt.statemachine.DeviceUpgradeStateMachine;
import com.danishakh.att.devicemgmt.statemachine.DeviceUpgradeTimings;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** The Device Callbacks module: informs (record the device, apply, reply with a Download if due) and acknowledgements. */
@Slf4j
@Service
public class DeviceCallbackService {

    private static final String INFORM = "inform";
    private static final String COMMAND_RESPONSE = "command_response";
    private static final String ACK = "ack";

    private final DeviceRepository deviceRepository;
    private final DeviceUpgradeRepository deviceUpgradeRepository;
    private final DeviceUpgradeTimings timings;
    private final Clock clock;
    private final MetricsRecorder metrics;
    private final TransactionTemplate transaction;

    public DeviceCallbackService(DeviceRepository deviceRepository, DeviceUpgradeRepository deviceUpgradeRepository,
            DeviceUpgradeTimings timings, Clock clock, MetricsRecorder metrics, TransactionTemplate transaction) {
        this.deviceRepository = deviceRepository;
        this.deviceUpgradeRepository = deviceUpgradeRepository;
        this.timings = timings;
        this.clock = clock;
        this.metrics = metrics;
        this.transaction = transaction;
    }

    /**
     * Records the device and applies its inform to the device's upgrade, replying with a Download if one is due.
     * A well-formed inform never fails; an unknown commandKey or a duplicate gets no commands.
     *
     * @param deviceId the device sending the inform
     * @param request the inform body
     * @return the reply body: no commands, or one Download
     */
    public InformResponse handleInform(String deviceId, InformRequest request) {
        log.debug("handleInform() - Incoming Inform - deviceId {} , event: {} , firmwareVersion: {} , transferCompleteInRequestPayload: {}", deviceId, request.getEvent(), request.getFirmwareVersion(), request.getTransferComplete() != null);
        Instant now = clock.instant();
        DeviceUpgradeEvent.Inform informEvent = toInformDeviceUpgradeEvent(request);

        WriteOutcome outcome = transaction.execute(status -> {
            deviceRepository.recordInform(deviceId, request.getFirmwareVersion(), request.getEvent(), now);
            return processInform(deviceId, request, informEvent, now);
        });
        recordOutcome(INFORM, request.getEvent().name(), outcome);
        InformResponse response = outcome.isWritten() ? buildInformResponseForDevice(outcome.getTransition()) : InformResponse.empty();
        log.info("handleInform() - Sending InformResponse - deviceId {} , {} answered with {} command(s)", deviceId, request.getEvent(), response.getCommands().size());
        return response;
    }

    /**
     * Correlates an inform to an upgrade and writes the result, retrying once if another callback wins the write.
     * Writes to the database only; metrics are recorded after the transaction commits.
     *
     * @param deviceId the device sending the inform
     * @param request the inform body
     * @param informEvent the inform as the state machine sees it
     * @param now the time of the inform
     * @return what the write did, for metrics and the reply
     */
    private WriteOutcome processInform(String deviceId, InformRequest request, DeviceUpgradeEvent.Inform informEvent, Instant now) {
        Optional<DeviceUpgradeRow> currentRow = correlateInformToDeviceUpgrade(deviceId, request);
        // Unable to correlate the inform to an existing device upgrade: TransferComplete with an unknown key, else ignored
        if (currentRow.isEmpty()) {
            if (request.getTransferComplete() != null) {
                log.info("processInform() - No upgrade has commandKey: {} for deviceId {}", request.getTransferComplete().getCommandKey(), deviceId);
                return WriteOutcome.unknownCommandKey();
            }
            log.debug("processInform() - deviceId {} has no upgrade in flight or timed out", deviceId);
            return WriteOutcome.ignored();
        }

        DeviceUpgradeTransition transition = DeviceUpgradeStateMachine.apply(currentRow.get(), informEvent, now, timings);
        if (!transition.changed()) {
            return WriteOutcome.of(transition);
        }
        if (deviceUpgradeRepository.update(currentRow.get(), transition.next(), now)) {
            return WriteOutcome.of(transition);
        }

        // Lost a race with another callback: read the row again and apply the inform once more.
        log.info("processInform() - Lost a write race for deviceId: {}, re-reading its upgrade from the database", deviceId);
        Optional<DeviceUpgradeRow> reread = correlateInformToDeviceUpgrade(deviceId, request);
        if (reread.isPresent()) {
            DeviceUpgradeTransition again = DeviceUpgradeStateMachine.apply(reread.get(), informEvent, now, timings);
            if (again.changed() && deviceUpgradeRepository.update(reread.get(), again.next(), now)) {
                return WriteOutcome.of(again);
            }
        }
        return WriteOutcome.of(DeviceUpgradeTransition.unchanged(currentRow.get(), DeviceCallbackResult.DUPLICATE));
    }

    /**
     * Builds the reply to the device for a database write that won.
     *
     * @param transition the transition that was written to the db
     * @return the reply body, with a Download if one was issued
     */
    private static InformResponse buildInformResponseForDevice(DeviceUpgradeTransition transition) {
        // If the transitioned state does not issue a Download, return an empty response
        if (!transition.issueDownload()) {
            return InformResponse.empty();
        }
        // Build Inform response with the Download command, using the next state(DeviceUpgradeRow) from the transition
        DeviceUpgradeRow next = transition.next();
        return InformResponse.download(next.getCommandKey(), next.getImageUrl(), next.getTargetVersion());
    }

    /**
     * Records a device's acknowledgement of a Download, matched by command key. The reply has no body, so the
     * controller always answers 200, including for an unknown key.
     *
     * @param deviceId the device sending the acknowledgement
     * @param request the acknowledgement body
     */
    public void handleDownloadCommandAcknowledgement(String deviceId, CommandResponseRequest request) {
        log.debug("handleDownloadCommandAcknowledgement() - deviceId: {} , commandKey: {} with status: {}", deviceId,
                request.getCommandKey(), request.getStatus());
        Instant now = clock.instant();
        // Build the acknowledgement event for the state machine
        DeviceUpgradeEvent.Ack ackEvent = new DeviceUpgradeEvent.Ack(request.getCommandKey(), request.getStatus(), request.getReason());

        WriteOutcome outcome = transaction.execute(status -> processDownloadCommandAcknowledgement(deviceId, request, ackEvent, now));
        recordOutcome(COMMAND_RESPONSE, ACK, outcome);
        log.info("handleDownloadCommandAcknowledgement() - Device {} command {} acknowledged, written: {}", deviceId,
                request.getCommandKey(), outcome.isWritten());
    }

    /**
     * Correlates an acknowledgement to its upgrade and writes the result, retrying once if another callback wins the write.
     * Writes to the database only; metrics are recorded after the transaction commits.
     *
     * @param deviceId the device sending the acknowledgement
     * @param request the acknowledgement body
     * @param ackEvent the acknowledgement as the state machine sees it
     * @param now the time of the acknowledgement
     * @return what the write did, for metrics
     */
    private WriteOutcome processDownloadCommandAcknowledgement(String deviceId, CommandResponseRequest request, DeviceUpgradeEvent.Ack ackEvent, Instant now) {
        Optional<DeviceUpgradeRow> currentRow = correlateByCommandKey(deviceId, request.getCommandKey());
        if (currentRow.isEmpty()) {
            log.info("processDownloadCommandAcknowledgement() - No upgrade has command key {} for device {}", request.getCommandKey(), deviceId);
            return WriteOutcome.unknownCommandKey();
        }

        DeviceUpgradeTransition transition = DeviceUpgradeStateMachine.apply(currentRow.get(), ackEvent, now, timings);
        if (!transition.changed()) {
            return WriteOutcome.of(transition);
        }
        if (deviceUpgradeRepository.update(currentRow.get(), transition.next(), now)) {
            return WriteOutcome.of(transition);
        }

        // Lost a race with another callback: read the row again and apply the acknowledgement once more.
        log.info("processDownloadCommandAcknowledgement() - Lost a write race for deviceId {}, re-reading its upgrade", deviceId);
        Optional<DeviceUpgradeRow> reread = correlateByCommandKey(deviceId, request.getCommandKey());
        if (reread.isPresent()) {
            DeviceUpgradeTransition again = DeviceUpgradeStateMachine.apply(reread.get(), ackEvent, now, timings);
            if (again.changed() && deviceUpgradeRepository.update(reread.get(), again.next(), now)) {
                return WriteOutcome.of(again);
            }
        }
        return WriteOutcome.of(DeviceUpgradeTransition.unchanged(currentRow.get(), DeviceCallbackResult.DUPLICATE));
    }

    /**
     * Records the metrics for a callback's outcome, after its transaction has committed.
     *
     * @param endpoint the endpoint tag, inform or command_response
     * @param eventName the event tag
     * @param outcome what the callback did
     */
    private void recordOutcome(String endpoint, String eventName, WriteOutcome outcome) {
        if (outcome.isUnknownCommandKey()) {
            metrics.recordDeviceCallBackWithUnknownCommandKey(endpoint, eventName);
        } else if (outcome.getTransition() == null) {
            metrics.recordDeviceCallback(endpoint, eventName, outcome.getResult());
        } else {
            recordMetrics(endpoint, eventName, outcome.getTransition());
        }
    }

    /**
     * Counts the metrics for a transition. An unchanged transition only counts the callback.
     *
     * @param endpoint the endpoint tag, inform or command_response
     * @param eventName the event tag
     * @param transition the transition for the matched row
     */
    private void recordMetrics(String endpoint, String eventName, DeviceUpgradeTransition transition) {
        DeviceUpgradeRow before = transition.previous();
        DeviceUpgradeRow next = transition.next();
        // If state changed, record metrics for the transition
        if (before.getState() != next.getState()) {
            metrics.recordDeviceTransition(before, next);
        }

        // Record metrics for the device callback
        metrics.recordDeviceCallback(endpoint, eventName, transition.result());

        // If versions mismatch, record metrics for it
        if (next.isReportedVersionMismatch() && !before.isReportedVersionMismatch()) {
            metrics.recordVersionMismatch();
        }

        // If a Download command was issued, record metrics for it
        if (transition.issueDownload()) {
            metrics.recordDownloadCommandIssued(next.getDownloadAttempts() > 1);
        }
    }

    /**
     * Finds the device upgrade an inform belongs to, by command key for a TransferComplete, else by device state.
     *
     * @param deviceId the device sending the inform
     * @param request the inform request body
     * @return the matching upgrade, if any
     */
    private Optional<DeviceUpgradeRow> correlateInformToDeviceUpgrade(String deviceId, InformRequest request) {
        if (request.getTransferComplete() != null) {
            return correlateByCommandKey(deviceId, request.getTransferComplete().getCommandKey());
        }
        return deviceUpgradeRepository.findInFlightByDevice(deviceId)
                .or(() -> deviceUpgradeRepository.findMostRecentTimedOutByDevice(deviceId));
    }

    /**
     * Finds the device upgrade with a command key, only if it belongs to the calling device.
     *
     * @param deviceId the device sending the callback
     * @param commandKey the key the device quoted
     * @return the matching upgrade, if any
     */
    private Optional<DeviceUpgradeRow> correlateByCommandKey(String deviceId, String commandKey) {
        return deviceUpgradeRepository.findByCommandKey(commandKey)
                .filter(row -> row.getDeviceId().equals(deviceId));
    }

    /**
     * Builds the state machine's inform, with a fresh proposed command key.
     *
     * @param request the inform body
     * @return the inform event for the state machine
     */
    private static DeviceUpgradeEvent.Inform toInformDeviceUpgradeEvent(InformRequest request) {
        InformRequest.TransferComplete tc = request.getTransferComplete();
        DeviceUpgradeEvent.TransferResult result = tc == null
                ? null
                : new DeviceUpgradeEvent.TransferResult(tc.getCommandKey(), tc.getFaultCode(),
                        tc.getStartTime(), tc.getCompleteTime());
        return new DeviceUpgradeEvent.Inform(request.getEvent(), request.getFirmwareVersion(), result,
                UUID.randomUUID().toString());
    }
}

