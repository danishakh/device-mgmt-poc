package com.danishakh.att.devicemgmt.statemachine;
import static com.danishakh.att.devicemgmt.statemachine.UpgradeState.ACCEPTED;
import static com.danishakh.att.devicemgmt.statemachine.UpgradeState.CANCELLED;
import static com.danishakh.att.devicemgmt.statemachine.UpgradeState.DOWNLOAD_SENT;
import static com.danishakh.att.devicemgmt.statemachine.UpgradeState.FAILED;
import static com.danishakh.att.devicemgmt.statemachine.UpgradeState.NOTIFIED;
import static com.danishakh.att.devicemgmt.statemachine.UpgradeState.PENDING;
import static com.danishakh.att.devicemgmt.statemachine.UpgradeState.SUCCEEDED;
import static com.danishakh.att.devicemgmt.statemachine.UpgradeState.TIMED_OUT;
import java.time.Instant;
/**
 * The upgrade state machine as a pure function: (row, event, now, timings) to a transition.
 * It does no I/O and needs no Spring context. The service writes the result with one conditional UPDATE.
 * Rules follow HLD §7.
 */
public final class UpgradeStateMachine {
    private UpgradeStateMachine() {
    }
    /**
     * Decides what one {@link UpgradeRow} becomes in response to one {@link UpgradeEvent}.
     *
     * <p>This method does not read a clock, a database, or the network. {@code now} and every
     * other input are supplied by the caller, which makes every rule reproducible in a plain
     * unit test. The caller is responsible for persisting {@link Transition#next()} and for
     * acting on {@link Transition#sendConnectionRequest()} and {@link Transition#issueDownload()}
     * after the write has committed. {@link Transition#result()} classifies the event on every path.
     *
     * @param row   the current device_upgrade row
     * @param event what just happened to it
     * @param now   the current time, supplied by the caller so the machine stays pure
     * @param t     the configured timeouts and retry limits
     * @return the transition: the new row, whether anything changed, and what to do next
     */
    public static Transition apply(UpgradeRow row, UpgradeEvent event, Instant now, UpgradeTimings t) {
        return switch (event) {
            case UpgradeEvent.Tick tick -> onTick(row, tick, now, t);
            case UpgradeEvent.Deadline deadline -> onDeadline(row, now, t);
            case UpgradeEvent.Inform inform -> onInform(row, inform, now, t);
            case UpgradeEvent.Ack ack -> onAck(row, ack, now, t);
            case UpgradeEvent.CampaignHalted halted -> onCampaignHalted(row);
        };
    }
    /**
     * Cancels a row that has not been given a Download yet (PENDING or NOTIFIED). Rows already
     * in flight carry on, and finished rows are untouched.
     */
    private static Transition onCampaignHalted(UpgradeRow row) {
        if (row.getState() != PENDING && row.getState() != NOTIFIED) {
            return Transition.unchanged(row, CallbackResult.IGNORED);
        }
        UpgradeRow next = row.toBuilder()
                .state(CANCELLED)
                .outcomeReason(OutcomeReason.CAMPAIGN_HALTED)
                .deadlineAt(null)
                .build();
        return Transition.applied(next, false, false);
    }
    /**
     * Handles a scheduler {@link UpgradeEvent.Tick}.
     *
     * <p>Only a PENDING row with a free slot ({@code tick.mayStart()}) is affected. The scheduler
     * is the only thing that is allowed to leave PENDING (HLD §10.1): a device that informs on
     * its own while PENDING is ignored by {@link #onInform}.
     */
    private static Transition onTick(UpgradeRow row, UpgradeEvent.Tick tick, Instant now, UpgradeTimings t) {
        if (row.getState() != PENDING || !tick.mayStart()) {
            return Transition.unchanged(row, CallbackResult.IGNORED);
        }
        UpgradeRow next = row.toBuilder()
                .state(NOTIFIED)
                .crAttempts(1)
                .deadlineAt(now.plus(t.crWait()))
                .build();
        return Transition.applied(next, true, false);
    }
    /**
     * Handles an {@link UpgradeEvent.Deadline}: the caller has already decided this row is
     * overdue. Routes to a retry or a timeout depending on the row's current waiting state.
     */
    private static Transition onDeadline(UpgradeRow row, Instant now, UpgradeTimings t) {
        return switch (row.getState()) {
            case NOTIFIED -> row.getCrAttempts() < t.maxCr()
                    ? sendConnectionRequestAgain(row, now, t)
                    : toTimedOut(row, OutcomeReason.NO_CALLBACK);
            case DOWNLOAD_SENT -> toTimedOut(row, OutcomeReason.NO_ACK);
            case ACCEPTED -> toTimedOut(row, OutcomeReason.NO_COMPLETION);
            default -> Transition.unchanged(row, CallbackResult.IGNORED);
        };
    }
    /** Sends another connection request: increments the attempt count and resets the deadline. */
    private static Transition sendConnectionRequestAgain(UpgradeRow row, Instant now, UpgradeTimings t) {
        UpgradeRow next = row.toBuilder()
                .crAttempts(row.getCrAttempts() + 1)
                .deadlineAt(now.plus(t.crWait()))
                .build();
        return Transition.applied(next, true, false);
    }
    /** Moves the row to TIMED_OUT with the given reason, and clears its deadline. */
    private static Transition toTimedOut(UpgradeRow row, OutcomeReason reason) {
        UpgradeRow next = row.toBuilder()
                .state(TIMED_OUT)
                .outcomeReason(reason)
                .deadlineAt(null)
                .build();
        return Transition.applied(next, false, false);
    }
    /**
     * Handles a device {@link UpgradeEvent.Inform}. A TransferComplete payload is handled first,
     * regardless of state (HLD §7.2); otherwise the row's current state decides
     * whether to start a Download, re-issue one, or look at ACCEPTED evidence.
     */
    private static Transition onInform(UpgradeRow row, UpgradeEvent.Inform inform, Instant now, UpgradeTimings t) {
        if (inform.transferComplete() != null) {
            return onTransferCompleteInform(row, inform);
        }
        return switch (row.getState()) {
            case NOTIFIED -> {
                UpgradeRow next = row.toBuilder()
                        .state(DOWNLOAD_SENT)
                        .commandKey(inform.proposedCommandKey())
                        .downloadAttempts(1)
                        .deadlineAt(now.plus(t.ackWait()))
                        .build();
                yield Transition.applied(next, false, true);
            }
            case DOWNLOAD_SENT -> {
                // A lost ack: the device already runs the target, so never flash it again.
                if (row.getTargetVersion().equals(inform.firmwareVersion())) {
                    yield terminalState(row, SUCCEEDED, OutcomeReason.INFERRED_FROM_VERSION, null,
                            inform.firmwareVersion(), false, false);
                }
                yield row.getDownloadAttempts() < t.maxDl()
                        ? sendDownloadAgain(row)
                        : Transition.unchanged(row, CallbackResult.IGNORED);
            }
            case ACCEPTED -> onInformAfterAccepted(row, inform);
            case TIMED_OUT -> onInformAfterTimedOut(row, inform);
            default -> Transition.unchanged(row, CallbackResult.IGNORED);
        };
    }
    /**
     * Records, once, that a device which timed out after being given a Download now reports the
     * target version. The row stays TIMED_OUT and still counts as a failure. NO_CALLBACK is excluded:
     * that device never got a Download, so its version says nothing about this campaign.
     */
    private static Transition onInformAfterTimedOut(UpgradeRow row, UpgradeEvent.Inform inform) {
        boolean givenDownload = row.getOutcomeReason() == OutcomeReason.NO_ACK
                || row.getOutcomeReason() == OutcomeReason.NO_COMPLETION;
        if (!givenDownload || !row.getTargetVersion().equals(inform.firmwareVersion())) {
            return Transition.unchanged(row, CallbackResult.IGNORED);
        }
        if (row.getTrailingEvidence() != null) {
            return Transition.unchanged(row, CallbackResult.DUPLICATE);
        }
        UpgradeRow next = row.toBuilder()
                .trailingEvidence(TrailingEvidence.REPORTED_TARGET_VERSION)
                .lastReportedVersion(inform.firmwareVersion())
                .build();
        return new Transition(next, true, false, false, CallbackResult.TRAILING);
    }
    /** Re-sends the same commandKey, one more time, up to {@code maxDl} attempts. */
    private static Transition sendDownloadAgain(UpgradeRow row) {
        UpgradeRow next = row.toBuilder()
                .downloadAttempts(row.getDownloadAttempts() + 1)
                .build();
        return Transition.applied(next, false, true);
    }
    /**
     * Decides the outcome of an ACCEPTED row that informs with no TransferComplete. The reported
     * version is the only evidence available (HLD §7.2): a match on the target succeeds, a BOOT
     * on the from-version is a rollback, a BOOT on anything else is unexpected, and anything
     * else (PERIODIC or CONNECTION_REQUEST on the old version) just keeps waiting.
     */
    private static Transition onInformAfterAccepted(UpgradeRow row, UpgradeEvent.Inform inform) {
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
        return Transition.unchanged(row, CallbackResult.IGNORED);
    }
    /**
     * Handles a TransferComplete. Correlation is by commandKey only: anything that does not
     * match the row's key is ignored. A match on a row still DOWNLOAD_SENT or ACCEPTED finishes
     * it normally; a match on a TIMED_OUT row is late evidence and still finishes it; a match on
     * an already-finished row is trailing evidence the first time, and a duplicate after that.
     */
    private static Transition onTransferCompleteInform(UpgradeRow row, UpgradeEvent.Inform inform) {
        UpgradeEvent.TransferResult tc = inform.transferComplete();
        if (!commandKeyMatches(row, tc.commandKey())) {
            return Transition.unchanged(row, CallbackResult.IGNORED);
        }
        boolean late = row.getState() == TIMED_OUT;
        boolean live = row.getState() == DOWNLOAD_SENT || row.getState() == ACCEPTED;
        if (!live && !late) {
            return onTransferCompleteAfterFinish(row, tc.faultCode());
        }
        String version = inform.firmwareVersion();
        if (tc.faultCode() == 0) {
            boolean mismatch = !row.getTargetVersion().equals(version);
            return terminalState(row, SUCCEEDED, OutcomeReason.TRANSFER_COMPLETE, 0, version, late, mismatch);
        }
        return terminalState(row, FAILED, OutcomeReason.FAULT, tc.faultCode(), version, late, false);
    }
    /**
     * A matching TransferComplete on a row that is not waiting for one. On a SUCCEEDED or FAILED row
     * whose outcome did not come from a TransferComplete, the first one is stored as trailing evidence.
     * Everything else (a repeat, or a row that never issued a Download) is a duplicate.
     */
    private static Transition onTransferCompleteAfterFinish(UpgradeRow row, int faultCode) {
        boolean finished = row.getState() == SUCCEEDED || row.getState() == FAILED;
        boolean outcomeFromTransferComplete = row.getOutcomeReason() == OutcomeReason.TRANSFER_COMPLETE
                || row.getOutcomeReason() == OutcomeReason.FAULT;
        if (!finished || outcomeFromTransferComplete || row.getTrailingEvidence() != null) {
            return Transition.unchanged(row, CallbackResult.DUPLICATE);
        }
        UpgradeRow next = row.toBuilder()
                .trailingEvidence(TrailingEvidence.FAULT)
                .trailingFaultCode(faultCode)
                .build();
        return new Transition(next, true, false, false, CallbackResult.TRAILING);
    }
    /**
     * Handles a {@link UpgradeEvent.Ack}. Only a DOWNLOAD_SENT row with a matching commandKey can
     * change state; anything else (a duplicate, a late ack after a finish or timeout, or a wrong
     * key) is ignored.
     */
    private static Transition onAck(UpgradeRow row, UpgradeEvent.Ack ack, Instant now, UpgradeTimings t) {
        if (!commandKeyMatches(row, ack.commandKey())) {
            return Transition.unchanged(row, CallbackResult.IGNORED);
        }
        if (row.getState() != DOWNLOAD_SENT) {
            // Late after a finish or timeout, or a repeat of an ack already applied.
            return Transition.unchanged(row, CallbackResult.DUPLICATE);
        }
        return switch (ack.status()) {
            case ACCEPTED -> {
                UpgradeRow next = row.toBuilder()
                        .state(ACCEPTED)
                        .deadlineAt(now.plus(t.completionWait()))
                        .build();
                yield Transition.applied(next, false, false);
            }
            // BUSY means the device is already downloading from an earlier Download; the real upgrade goes on.
            case REJECTED -> UpgradeEvent.Ack.BUSY.equals(ack.reason())
                    ? Transition.unchanged(row, CallbackResult.BUSY_REJECTION)
                    : terminalState(row, FAILED, OutcomeReason.REJECTED, null, null, false, false);
        };
    }
    /**
     * Builds the terminal transition shared by every path that ends a row: sets the final state
     * and reason, stores the evidence (fault code, reported version), clears the deadline, and
     * accumulates the late-evidence and version-mismatch flags rather than overwriting them.
     * A transition that replaces a timeout ({@code late}) is classified LATE, otherwise APPLIED.
     */
    private static Transition terminalState(UpgradeRow row, UpgradeState state, OutcomeReason reason, Integer faultCode,
            String reportedVersion, boolean late, boolean mismatch) {
        UpgradeRow next = row.toBuilder()
                .state(state)
                .outcomeReason(reason)
                .faultCode(faultCode)
                .deadlineAt(null)
                .lastReportedVersion(reportedVersion)
                .lateEvidence(row.isLateEvidence() || late)
                .reportedVersionMismatch(mismatch)
                .build();
        return new Transition(next, true, false, false, late ? CallbackResult.LATE : CallbackResult.APPLIED);
    }
    /** True only when the row has an assigned commandKey and it equals the one supplied. */
    private static boolean commandKeyMatches(UpgradeRow row, String commandKey) {
        return row.getCommandKey() != null && row.getCommandKey().equals(commandKey);
    }
}
