package com.danishakh.att.devicemgmt.statemachine;
import static com.danishakh.att.devicemgmt.statemachine.DeviceUpgradeState.ACCEPTED;
import static com.danishakh.att.devicemgmt.statemachine.DeviceUpgradeState.CANCELLED;
import static com.danishakh.att.devicemgmt.statemachine.DeviceUpgradeState.DOWNLOAD_SENT;
import static com.danishakh.att.devicemgmt.statemachine.DeviceUpgradeState.FAILED;
import static com.danishakh.att.devicemgmt.statemachine.DeviceUpgradeState.NOTIFIED;
import static com.danishakh.att.devicemgmt.statemachine.DeviceUpgradeState.PENDING;
import static com.danishakh.att.devicemgmt.statemachine.DeviceUpgradeState.SUCCEEDED;
import static com.danishakh.att.devicemgmt.statemachine.DeviceUpgradeState.TIMED_OUT;
import java.time.Instant;
/**
 * The upgrade state machine as a pure function: (row, event, now, timings) to a transition.
 * It does no I/O and needs no Spring context. The service writes the result with one conditional UPDATE.
 * Rules follow HLD §7.
 */
public final class DeviceUpgradeStateMachine {
    private DeviceUpgradeStateMachine() {
    }
    /**
     * Decides what one {@link DeviceUpgradeRow} becomes in response to one {@link DeviceUpgradeEvent}.
     *
     * <p>This method does not read a clock, a database, or the network. {@code now} and every
     * other input are supplied by the caller, which makes every rule reproducible in a plain
     * unit test. The caller is responsible for persisting {@link DeviceUpgradeTransition#next()} and for
     * acting on {@link DeviceUpgradeTransition#sendConnectionRequest()} and {@link DeviceUpgradeTransition#issueDownload()}
     * after the write has committed. {@link DeviceUpgradeTransition#result()} classifies the event on every path.
     *
     * @param row   the current device_upgrade row
     * @param event what just happened to it
     * @param now   the current time, supplied by the caller so the machine stays pure
     * @param t     the configured timeouts and retry limits
     * @return the transition: the new row, whether anything changed, and what to do next
     */
    public static DeviceUpgradeTransition apply(DeviceUpgradeRow row, DeviceUpgradeEvent event, Instant now, DeviceUpgradeTimings t) {
        return switch (event) {
            case DeviceUpgradeEvent.Tick tick -> onTick(row, tick, now, t);
            case DeviceUpgradeEvent.Deadline deadline -> onDeadline(row, now, t);
            case DeviceUpgradeEvent.Inform inform -> onInform(row, inform, now, t);
            case DeviceUpgradeEvent.Ack ack -> onAck(row, ack, now, t);
            case DeviceUpgradeEvent.CampaignHalted halted -> onCampaignHalted(row);
        };
    }
    /**
     * Cancels a row that has not been given a Download yet (PENDING or NOTIFIED). Rows already
     * in flight carry on, and finished rows are untouched.
     */
    private static DeviceUpgradeTransition onCampaignHalted(DeviceUpgradeRow row) {
        if (row.getState() != PENDING && row.getState() != NOTIFIED) {
            return DeviceUpgradeTransition.unchanged(row, DeviceCallbackResult.IGNORED);
        }
        DeviceUpgradeRow next = row.toBuilder()
                .state(CANCELLED)
                .outcomeReason(OutcomeReason.CAMPAIGN_HALTED)
                .deadlineAt(null)
                .build();
        return DeviceUpgradeTransition.applied(row, next, false, false);
    }
    /**
     * Handles a scheduler {@link DeviceUpgradeEvent.Tick}.
     *
     * <p>Only a PENDING row with a free slot ({@code tick.mayStart()}) is affected. The scheduler
     * is the only thing that is allowed to leave PENDING (HLD §10.1): a device that informs on
     * its own while PENDING is ignored by {@link #onInform}.
     */
    private static DeviceUpgradeTransition onTick(DeviceUpgradeRow row, DeviceUpgradeEvent.Tick tick, Instant now, DeviceUpgradeTimings t) {
        if (row.getState() != PENDING || !tick.mayStart()) {
            return DeviceUpgradeTransition.unchanged(row, DeviceCallbackResult.IGNORED);
        }
        DeviceUpgradeRow next = row.toBuilder()
                .state(NOTIFIED)
                .crAttempts(1)
                .deadlineAt(now.plus(t.crWait()))
                .build();
        return DeviceUpgradeTransition.applied(row, next, true, false);
    }
    /**
     * Handles an {@link DeviceUpgradeEvent.Deadline}: the caller has already decided this row is
     * overdue. Routes to a retry or a timeout depending on the row's current waiting state.
     */
    private static DeviceUpgradeTransition onDeadline(DeviceUpgradeRow row, Instant now, DeviceUpgradeTimings t) {
        return switch (row.getState()) {
            case NOTIFIED -> row.getCrAttempts() < t.maxCr()
                    ? sendConnectionRequestAgain(row, now, t)
                    : toTimedOut(row, OutcomeReason.NO_CALLBACK);
            case DOWNLOAD_SENT -> toTimedOut(row, OutcomeReason.NO_ACK);
            case ACCEPTED -> toTimedOut(row, OutcomeReason.NO_COMPLETION);
            default -> DeviceUpgradeTransition.unchanged(row, DeviceCallbackResult.IGNORED);
        };
    }
    /** Sends another connection request: increments the attempt count and resets the deadline. */
    private static DeviceUpgradeTransition sendConnectionRequestAgain(DeviceUpgradeRow row, Instant now, DeviceUpgradeTimings t) {
        DeviceUpgradeRow next = row.toBuilder()
                .crAttempts(row.getCrAttempts() + 1)
                .deadlineAt(now.plus(t.crWait()))
                .build();
        return DeviceUpgradeTransition.applied(row, next, true, false);
    }
    /** Moves the row to TIMED_OUT with the given reason, and clears its deadline. */
    private static DeviceUpgradeTransition toTimedOut(DeviceUpgradeRow row, OutcomeReason reason) {
        DeviceUpgradeRow next = row.toBuilder()
                .state(TIMED_OUT)
                .outcomeReason(reason)
                .deadlineAt(null)
                .build();
        return DeviceUpgradeTransition.applied(row, next, false, false);
    }
    /**
     * Handles a device {@link DeviceUpgradeEvent.Inform}. A TransferComplete payload is handled first,
     * regardless of state (HLD §7.2); otherwise the row's current state decides
     * whether to start a Download, re-issue one, or look at ACCEPTED evidence.
     */
    private static DeviceUpgradeTransition onInform(DeviceUpgradeRow row, DeviceUpgradeEvent.Inform inform, Instant now, DeviceUpgradeTimings t) {
        if (inform.transferComplete() != null) {
            return onTransferCompleteInform(row, inform);
        }
        return switch (row.getState()) {
            case NOTIFIED -> {
                DeviceUpgradeRow next = row.toBuilder()
                        .state(DOWNLOAD_SENT)
                        .commandKey(inform.proposedCommandKey())
                        .downloadAttempts(1)
                        .deadlineAt(now.plus(t.ackWait()))
                        .build();
                yield DeviceUpgradeTransition.applied(row, next, false, true);
            }
            case DOWNLOAD_SENT -> {
                // A lost ack: the device already runs the target, so never flash it again.
                if (row.getTargetVersion().equals(inform.firmwareVersion())) {
                    yield terminalState(row, SUCCEEDED, OutcomeReason.INFERRED_FROM_VERSION, null,
                            inform.firmwareVersion(), false, false);
                }
                yield row.getDownloadAttempts() < t.maxDl()
                        ? sendDownloadAgain(row)
                        : DeviceUpgradeTransition.unchanged(row, DeviceCallbackResult.IGNORED);
            }
            case ACCEPTED -> onInformAfterAccepted(row, inform);
            case TIMED_OUT -> onInformAfterTimedOut(row, inform);
            default -> DeviceUpgradeTransition.unchanged(row, DeviceCallbackResult.IGNORED);
        };
    }
    /**
     * Records, once, that a device which timed out after being given a Download now reports the
     * target version. The row stays TIMED_OUT and still counts as a failure. NO_CALLBACK is excluded:
     * that device never got a Download, so its version says nothing about this campaign.
     */
    private static DeviceUpgradeTransition onInformAfterTimedOut(DeviceUpgradeRow row, DeviceUpgradeEvent.Inform inform) {
        boolean givenDownload = row.getOutcomeReason() == OutcomeReason.NO_ACK
                || row.getOutcomeReason() == OutcomeReason.NO_COMPLETION;
        if (!givenDownload || !row.getTargetVersion().equals(inform.firmwareVersion())) {
            return DeviceUpgradeTransition.unchanged(row, DeviceCallbackResult.IGNORED);
        }
        if (row.getTrailingEvidence() != null) {
            return DeviceUpgradeTransition.unchanged(row, DeviceCallbackResult.DUPLICATE);
        }
        DeviceUpgradeRow next = row.toBuilder()
                .trailingEvidence(TrailingEvidence.REPORTED_TARGET_VERSION)
                .lastReportedVersion(inform.firmwareVersion())
                .build();
        return new DeviceUpgradeTransition(row, next, true, false, false, DeviceCallbackResult.TRAILING);
    }
    /** Re-sends the same commandKey, one more time, up to {@code maxDl} attempts. */
    private static DeviceUpgradeTransition sendDownloadAgain(DeviceUpgradeRow row) {
        DeviceUpgradeRow next = row.toBuilder()
                .downloadAttempts(row.getDownloadAttempts() + 1)
                .build();
        return DeviceUpgradeTransition.applied(row, next, false, true);
    }
    /**
     * Decides the outcome of an ACCEPTED row that informs with no TransferComplete. The reported
     * version is the only evidence available (HLD §7.2): a match on the target succeeds, a BOOT
     * on the from-version is a rollback, a BOOT on anything else is unexpected, and anything
     * else (PERIODIC or CONNECTION_REQUEST on the old version) just keeps waiting.
     */
    private static DeviceUpgradeTransition onInformAfterAccepted(DeviceUpgradeRow row, DeviceUpgradeEvent.Inform inform) {
        String version = inform.firmwareVersion();
        if (row.getTargetVersion().equals(version)) {
            return terminalState(row, SUCCEEDED, OutcomeReason.INFERRED_FROM_VERSION, null, version, false, false);
        }
        if (inform.informEvent() == InformEvent.BOOT) {
            if (row.getFromVersion().equals(version)) {
                return terminalState(row, FAILED, OutcomeReason.ROLLED_BACK, null, version, false, false);
            }
            return terminalState(row, FAILED, OutcomeReason.UNEXPECTED_VERSION, null, version, false, false);
        }
        // PERIODIC or CONNECTION_REQUEST on the old version: the device may still be downloading.
        return DeviceUpgradeTransition.unchanged(row, DeviceCallbackResult.IGNORED);
    }
    /**
     * Handles a TransferComplete. Correlation is by commandKey only: anything that does not
     * match the row's key is ignored. A match on a row still DOWNLOAD_SENT or ACCEPTED finishes
     * it normally; a match on a TIMED_OUT row is late evidence and still finishes it; a match on
     * an already-finished row is trailing evidence the first time, and a duplicate after that.
     */
    private static DeviceUpgradeTransition onTransferCompleteInform(DeviceUpgradeRow row, DeviceUpgradeEvent.Inform inform) {
        DeviceUpgradeEvent.TransferResult tc = inform.transferComplete();
        if (!commandKeyMatches(row, tc.commandKey())) {
            return DeviceUpgradeTransition.unchanged(row, DeviceCallbackResult.IGNORED);
        }
        boolean late = row.getState() == TIMED_OUT;
        boolean live = row.getState() == DOWNLOAD_SENT || row.getState() == ACCEPTED;
        if (!live && !late) {
            return withTransferTimes(onTransferCompleteAfterFinish(row, tc), tc);
        }
        String version = inform.firmwareVersion();
        if (tc.faultCode() == 0) {
            boolean mismatch = !row.getTargetVersion().equals(version);
            return withTransferTimes(
                    terminalState(row, SUCCEEDED, OutcomeReason.TRANSFER_COMPLETE, 0, version, late, mismatch), tc);
        }
        return withTransferTimes(
                terminalState(row, FAILED, OutcomeReason.FAULT, tc.faultCode(), version, late, false), tc);
    }

    /**
     * Stores the device-reported start and complete times on a transition that changed the row. A duplicate
     * changes nothing, so its times are not written over the first ones.
     */
    private static DeviceUpgradeTransition withTransferTimes(DeviceUpgradeTransition transition, DeviceUpgradeEvent.TransferResult tc) {
        if (!transition.changed()) {
            return transition;
        }
        DeviceUpgradeRow next = transition.next().toBuilder()
                .transferStartTime(tc.startTime())
                .transferCompleteTime(tc.completeTime())
                .build();
        return new DeviceUpgradeTransition(transition.previous(), next, true, transition.sendConnectionRequest(),
                transition.issueDownload(), transition.result());
    }
    /**
     * A matching TransferComplete on a row that is not waiting for one. On a SUCCEEDED or FAILED row
     * whose outcome did not come from a TransferComplete, the first one is stored as trailing evidence.
     * Everything else (a repeat, or a row that never issued a Download) is a duplicate.
     */
    private static DeviceUpgradeTransition onTransferCompleteAfterFinish(DeviceUpgradeRow row, DeviceUpgradeEvent.TransferResult tc) {
        boolean finished = row.getState() == SUCCEEDED || row.getState() == FAILED;
        boolean outcomeFromTransferComplete = row.getOutcomeReason() == OutcomeReason.TRANSFER_COMPLETE
                || row.getOutcomeReason() == OutcomeReason.FAULT;
        if (!finished || outcomeFromTransferComplete || row.getTrailingEvidence() != null) {
            return DeviceUpgradeTransition.unchanged(row, DeviceCallbackResult.DUPLICATE);
        }
        DeviceUpgradeRow next = row.toBuilder()
                .trailingEvidence(TrailingEvidence.FAULT)
                .trailingFaultCode(tc.faultCode())
                .build();
        return new DeviceUpgradeTransition(row, next, true, false, false, DeviceCallbackResult.TRAILING);
    }
    /**
     * Handles a {@link DeviceUpgradeEvent.Ack}. Only a DOWNLOAD_SENT row with a matching commandKey can
     * change state; anything else (a duplicate, a late ack after a finish or timeout, or a wrong
     * key) is ignored.
     */
    private static DeviceUpgradeTransition onAck(DeviceUpgradeRow row, DeviceUpgradeEvent.Ack ack, Instant now, DeviceUpgradeTimings t) {
        if (!commandKeyMatches(row, ack.commandKey())) {
            return DeviceUpgradeTransition.unchanged(row, DeviceCallbackResult.IGNORED);
        }
        if (row.getState() != DOWNLOAD_SENT) {
            // Late after a finish or timeout, or a repeat of an ack already applied.
            return DeviceUpgradeTransition.unchanged(row, DeviceCallbackResult.DUPLICATE);
        }
        return switch (ack.status()) {
            case ACCEPTED -> {
                DeviceUpgradeRow next = row.toBuilder()
                        .state(ACCEPTED)
                        .deadlineAt(now.plus(t.completionWait()))
                        .build();
                yield DeviceUpgradeTransition.applied(row, next, false, false);
            }
            // BUSY means the device is already downloading from an earlier Download; the real upgrade goes on.
            case REJECTED -> DeviceUpgradeEvent.Ack.BUSY.equals(ack.reason())
                    ? DeviceUpgradeTransition.unchanged(row, DeviceCallbackResult.BUSY_REJECTION)
                    : terminalState(row, FAILED, OutcomeReason.REJECTED, null, null, false, false);
        };
    }
    /**
     * Builds the terminal transition shared by every path that ends a row: sets the final state
     * and reason, stores the evidence (fault code, reported version), clears the deadline, and
     * accumulates the late-evidence and version-mismatch flags rather than overwriting them.
     * A transition that replaces a timeout ({@code late}) is classified LATE, otherwise APPLIED.
     */
    private static DeviceUpgradeTransition terminalState(DeviceUpgradeRow row, DeviceUpgradeState state, OutcomeReason reason, Integer faultCode,
            String reportedVersion, boolean late, boolean mismatch) {
        DeviceUpgradeRow next = row.toBuilder()
                .state(state)
                .outcomeReason(reason)
                .faultCode(faultCode)
                .deadlineAt(null)
                .lastReportedVersion(reportedVersion)
                .lateEvidence(row.isLateEvidence() || late)
                .reportedVersionMismatch(mismatch)
                .build();
        return new DeviceUpgradeTransition(row, next, true, false, false, late ? DeviceCallbackResult.LATE : DeviceCallbackResult.APPLIED);
    }
    /** True only when the row has an assigned commandKey and it equals the one supplied. */
    private static boolean commandKeyMatches(DeviceUpgradeRow row, String commandKey) {
        return row.getCommandKey() != null && row.getCommandKey().equals(commandKey);
    }
}
