package com.danishakh.att.devicemgmt.statemachine;

import static com.danishakh.att.devicemgmt.statemachine.UpgradeState.ACCEPTED;
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
 * Rules follow IMPLEMENTATION-PLAN.md §2.3, with decisions D7, D9, D12 and D13 applied.
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
     * after the write has committed.
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
        };
    }

    /**
     * Handles a scheduler {@link UpgradeEvent.Tick}.
     *
     * <p>Only a PENDING row with a free slot ({@code tick.mayStart()}) is affected. The scheduler
     * is the only thing that is allowed to leave PENDING (decision D7): a device that informs on
     * its own while PENDING is ignored by {@link #onInform}.
     */
    private static Transition onTick(UpgradeRow row, UpgradeEvent.Tick tick, Instant now, UpgradeTimings t) {
        if (row.getState() != PENDING || !tick.mayStart()) {
            return Transition.unchanged(row);
        }
        UpgradeRow next = row.toBuilder()
                .state(NOTIFIED)
                .crAttempts(1)
                .deadlineAt(now.plus(t.crWait()))
                .build();
        return new Transition(next, true, true, false);
    }

    /**
     * Handles an {@link UpgradeEvent.Deadline}: the caller has already decided this row is
     * overdue. Routes to a retry or a timeout depending on the row's current waiting state.
     */
    private static Transition onDeadline(UpgradeRow row, Instant now, UpgradeTimings t) {
        return switch (row.getState()) {
            case NOTIFIED -> row.getCrAttempts() < t.maxCr()
                    ? nudgeAgain(row, now, t)
                    : timeOut(row, OutcomeReason.NO_CALLBACK);
            case DOWNLOAD_SENT -> timeOut(row, OutcomeReason.NO_ACK);
            case ACCEPTED -> timeOut(row, OutcomeReason.NO_COMPLETION);
            default -> Transition.unchanged(row);
        };
    }

    /** Sends another connection request: increments the attempt count and resets the deadline. */
    private static Transition nudgeAgain(UpgradeRow row, Instant now, UpgradeTimings t) {
        UpgradeRow next = row.toBuilder()
                .crAttempts(row.getCrAttempts() + 1)
                .deadlineAt(now.plus(t.crWait()))
                .build();
        return new Transition(next, true, true, false);
    }

    /** Moves the row to TIMED_OUT with the given reason, and clears its deadline. */
    private static Transition timeOut(UpgradeRow row, OutcomeReason reason) {
        UpgradeRow next = row.toBuilder()
                .state(TIMED_OUT)
                .outcomeReason(reason)
                .deadlineAt(null)
                .build();
        return new Transition(next, true, false, false);
    }

    /**
     * Handles a device {@link UpgradeEvent.Inform}. A TransferComplete payload is handled first,
     * regardless of state (decision D12 and D13); otherwise the row's current state decides
     * whether to start a Download, re-issue one, or look at ACCEPTED evidence.
     */
    private static Transition onInform(UpgradeRow row, UpgradeEvent.Inform inform, Instant now, UpgradeTimings t) {
        if (inform.transferComplete() != null) {
            return onTransferComplete(row, inform);
        }
        return switch (row.getState()) {
            case NOTIFIED -> {
                UpgradeRow next = row.toBuilder()
                        .state(DOWNLOAD_SENT)
                        .downloadAttempts(1)
                        .deadlineAt(now.plus(t.ackWait()))
                        .build();
                yield new Transition(next, true, false, true);
            }
            case DOWNLOAD_SENT -> row.getDownloadAttempts() < t.maxDl()
                    ? reissueDownload(row)
                    : Transition.unchanged(row);
            case ACCEPTED -> onAcceptedInform(row, inform);
            default -> Transition.unchanged(row);
        };
    }

    /** Re-sends the same commandKey, one more time, up to {@code maxDl} attempts. */
    private static Transition reissueDownload(UpgradeRow row) {
        UpgradeRow next = row.toBuilder()
                .downloadAttempts(row.getDownloadAttempts() + 1)
                .build();
        return new Transition(next, true, false, true);
    }

    /**
     * Decides the outcome of an ACCEPTED row that informs with no TransferComplete. The reported
     * version is the only evidence available (plan §6.3): a match on the target succeeds, a BOOT
     * on the from-version is a rollback, a BOOT on anything else is unexpected, and anything
     * else (PERIODIC or CONNECTION_REQUEST on the old version) just keeps waiting.
     */
    private static Transition onAcceptedInform(UpgradeRow row, UpgradeEvent.Inform inform) {
        String version = inform.firmwareVersion();
        if (row.getTargetVersion().equals(version)) {
            return finish(row, SUCCEEDED, OutcomeReason.INFERRED_FROM_VERSION, null, version, false, false);
        }
        if (inform.informEvent() == InformEvent.BOOT) {
            if (row.getFromVersion().equals(version)) {
                return finish(row, FAILED, OutcomeReason.ROLLED_BACK, null, version, false, false);
            }
            return finish(row, FAILED, OutcomeReason.UNEXPECTED_VERSION, null, version, false, false);
        }
        // PERIODIC or CONNECTION_REQUEST on the old version: the device may still be downloading.
        return Transition.unchanged(row);
    }

    /**
     * Handles a TransferComplete. Correlation is by commandKey only: anything that does not
     * match the row's key is ignored. A match on a row still DOWNLOAD_SENT or ACCEPTED finishes
     * it normally; a match on a TIMED_OUT row is late evidence and still finishes it; a match on
     * an already-finished row is a duplicate and changes nothing.
     */
    private static Transition onTransferComplete(UpgradeRow row, UpgradeEvent.Inform inform) {
        UpgradeEvent.TransferResult tc = inform.transferComplete();
        if (!keyMatches(row, tc.commandKey())) {
            return Transition.unchanged(row);
        }
        boolean late = row.getState() == TIMED_OUT;
        boolean live = row.getState() == DOWNLOAD_SENT || row.getState() == ACCEPTED;
        if (!live && !late) {
            // Duplicate on a finished row (SUCCEEDED or FAILED), or nothing has been issued yet.
            return Transition.unchanged(row);
        }
        String version = inform.firmwareVersion();
        if (tc.faultCode() == 0) {
            boolean mismatch = !row.getTargetVersion().equals(version);
            return finish(row, SUCCEEDED, OutcomeReason.TRANSFER_COMPLETE, 0, version, late, mismatch);
        }
        return finish(row, FAILED, OutcomeReason.FAULT, tc.faultCode(), version, late, false);
    }

    /**
     * Handles a {@link UpgradeEvent.Ack}. Only a DOWNLOAD_SENT row with a matching commandKey can
     * change state; anything else (a duplicate, a late ack after a finish or timeout, or a wrong
     * key) is ignored.
     */
    private static Transition onAck(UpgradeRow row, UpgradeEvent.Ack ack, Instant now, UpgradeTimings t) {
        if (row.getState() != DOWNLOAD_SENT || !keyMatches(row, ack.commandKey())) {
            // Duplicate, late after a finish or timeout, or a wrong key.
            return Transition.unchanged(row);
        }
        return switch (ack.status()) {
            case ACCEPTED -> {
                UpgradeRow next = row.toBuilder()
                        .state(ACCEPTED)
                        .deadlineAt(now.plus(t.completionWait()))
                        .build();
                yield new Transition(next, true, false, false);
            }
            case REJECTED -> finish(row, FAILED, OutcomeReason.REJECTED, null, null, false, false);
        };
    }

    /**
     * Builds the terminal transition shared by every path that ends a row: sets the final state
     * and reason, stores the evidence (fault code, reported version), clears the deadline, and
     * accumulates the late-evidence and version-mismatch flags rather than overwriting them.
     */
    private static Transition finish(UpgradeRow row, UpgradeState state, OutcomeReason reason, Integer faultCode,
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
        return new Transition(next, true, false, false);
    }

    /** True only when the row has an assigned commandKey and it equals the one supplied. */
    private static boolean keyMatches(UpgradeRow row, String commandKey) {
        return row.getCommandKey() != null && row.getCommandKey().equals(commandKey);
    }
}
