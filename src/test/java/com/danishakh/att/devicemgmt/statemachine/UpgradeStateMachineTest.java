package com.danishakh.att.devicemgmt.statemachine;

import static com.danishakh.att.devicemgmt.statemachine.UpgradeState.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.danishakh.att.devicemgmt.statemachine.UpgradeEvent.Ack;
import com.danishakh.att.devicemgmt.statemachine.UpgradeEvent.Deadline;
import com.danishakh.att.devicemgmt.statemachine.UpgradeEvent.Inform;
import com.danishakh.att.devicemgmt.statemachine.UpgradeEvent.Tick;
import com.danishakh.att.devicemgmt.statemachine.UpgradeEvent.TransferResult;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Table-driven checks of every transition rule in HLD §7. No Spring context. */
class UpgradeStateMachineTest {

    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");
    private static final UpgradeTimings T = new UpgradeTimings(
            Duration.ofSeconds(30), 2, Duration.ofSeconds(30), 2, Duration.ofMinutes(5));
    private static final String FROM = "1.0.0";
    private static final String TARGET = "2.0.0";
    private static final String KEY = "k-1";

    /** Expected result of one event. */
    record Expect(UpgradeState state, OutcomeReason reason, boolean changed, boolean sendCr, boolean download) {}

    static UpgradeRow row(UpgradeState state, String key, int cr, int dl) {
        return UpgradeRow.builder()
                .state(state).fromVersion(FROM).targetVersion(TARGET)
                .commandKey(key).crAttempts(cr).downloadAttempts(dl)
                .build();
    }

    static UpgradeRow withReason(UpgradeRow row, OutcomeReason reason) {
        return row.toBuilder().outcomeReason(reason).build();
    }

    static Inform inform(InformEvent kind, String version) {
        return new Inform(kind, version, null);
    }

    static Inform tc(InformEvent kind, String version, String key, int fault) {
        return new Inform(kind, version, new TransferResult(key, fault));
    }

    static Stream<Arguments> table() {
        return Stream.of(
                // PENDING: only the scheduler leaves PENDING (HLD §10.1)
                Arguments.of("PENDING: tick with a free slot nudges the device",
                        row(PENDING, null, 0, 0), new Tick(true),
                        new Expect(NOTIFIED, null, true, true, false)),
                Arguments.of("PENDING: tick without a free slot waits",
                        row(PENDING, null, 0, 0), new Tick(false),
                        new Expect(PENDING, null, false, false, false)),
                Arguments.of("PENDING: an inform does not dispatch",
                        row(PENDING, null, 0, 0), inform(InformEvent.PERIODIC, FROM),
                        new Expect(PENDING, null, false, false, false)),

                // NOTIFIED
                Arguments.of("NOTIFIED: any inform returns the download",
                        row(NOTIFIED, null, 1, 0), inform(InformEvent.PERIODIC, FROM),
                        new Expect(DOWNLOAD_SENT, null, true, false, true)),
                Arguments.of("NOTIFIED: deadline with attempts left nudges again",
                        row(NOTIFIED, null, 1, 0), new Deadline(),
                        new Expect(NOTIFIED, null, true, true, false)),
                Arguments.of("NOTIFIED: deadline with attempts exhausted times out",
                        row(NOTIFIED, null, 2, 0), new Deadline(),
                        new Expect(TIMED_OUT, OutcomeReason.NO_CALLBACK, true, false, false)),
                Arguments.of("NOTIFIED: TransferComplete for a key never issued is ignored",
                        row(NOTIFIED, null, 1, 0), tc(InformEvent.TRANSFER_COMPLETE, TARGET, KEY, 0),
                        new Expect(NOTIFIED, null, false, false, false)),
                Arguments.of("NOTIFIED: tick changes nothing",
                        row(NOTIFIED, null, 1, 0), new Tick(true),
                        new Expect(NOTIFIED, null, false, false, false)),

                // DOWNLOAD_SENT
                Arguments.of("DOWNLOAD_SENT: inform re-issues the same download",
                        row(DOWNLOAD_SENT, KEY, 1, 1), inform(InformEvent.PERIODIC, FROM),
                        new Expect(DOWNLOAD_SENT, null, true, false, true)),
                Arguments.of("DOWNLOAD_SENT: inform after the last download sends nothing",
                        row(DOWNLOAD_SENT, KEY, 1, 2), inform(InformEvent.PERIODIC, FROM),
                        new Expect(DOWNLOAD_SENT, null, false, false, false)),
                Arguments.of("DOWNLOAD_SENT: ACCEPTED ack moves to ACCEPTED",
                        row(DOWNLOAD_SENT, KEY, 1, 1), new Ack(KEY, AckStatus.ACCEPTED),
                        new Expect(ACCEPTED, null, true, false, false)),
                Arguments.of("DOWNLOAD_SENT: REJECTED ack fails the device",
                        row(DOWNLOAD_SENT, KEY, 1, 1), new Ack(KEY, AckStatus.REJECTED),
                        new Expect(FAILED, OutcomeReason.REJECTED, true, false, false)),
                Arguments.of("DOWNLOAD_SENT: deadline times out with NO_ACK",
                        row(DOWNLOAD_SENT, KEY, 1, 1), new Deadline(),
                        new Expect(TIMED_OUT, OutcomeReason.NO_ACK, true, false, false)),
                Arguments.of("DOWNLOAD_SENT: ack with another key is ignored",
                        row(DOWNLOAD_SENT, KEY, 1, 1), new Ack("other", AckStatus.ACCEPTED),
                        new Expect(DOWNLOAD_SENT, null, false, false, false)),
                Arguments.of("DOWNLOAD_SENT: TransferComplete with fault 0 finishes the row",
                        row(DOWNLOAD_SENT, KEY, 1, 1), tc(InformEvent.TRANSFER_COMPLETE, TARGET, KEY, 0),
                        new Expect(SUCCEEDED, OutcomeReason.TRANSFER_COMPLETE, true, false, false)),
                Arguments.of("DOWNLOAD_SENT: TransferComplete with a fault fails the row",
                        row(DOWNLOAD_SENT, KEY, 1, 1), tc(InformEvent.TRANSFER_COMPLETE, TARGET, KEY, 9010),
                        new Expect(FAILED, OutcomeReason.FAULT, true, false, false)),

                // ACCEPTED
                Arguments.of("ACCEPTED: TransferComplete with fault 0 succeeds",
                        row(ACCEPTED, KEY, 1, 1), tc(InformEvent.TRANSFER_COMPLETE, TARGET, KEY, 0),
                        new Expect(SUCCEEDED, OutcomeReason.TRANSFER_COMPLETE, true, false, false)),
                Arguments.of("ACCEPTED: TransferComplete with a fault fails",
                        row(ACCEPTED, KEY, 1, 1), tc(InformEvent.TRANSFER_COMPLETE, FROM, KEY, 9010),
                        new Expect(FAILED, OutcomeReason.FAULT, true, false, false)),
                Arguments.of("ACCEPTED: TransferComplete on a PERIODIC inform still counts",
                        row(ACCEPTED, KEY, 1, 1), tc(InformEvent.PERIODIC, TARGET, KEY, 0),
                        new Expect(SUCCEEDED, OutcomeReason.TRANSFER_COMPLETE, true, false, false)),
                Arguments.of("ACCEPTED: TransferComplete with another key is ignored",
                        row(ACCEPTED, KEY, 1, 1), tc(InformEvent.TRANSFER_COMPLETE, TARGET, "other", 0),
                        new Expect(ACCEPTED, null, false, false, false)),
                Arguments.of("ACCEPTED: inform on the target version infers success",
                        row(ACCEPTED, KEY, 1, 1), inform(InformEvent.PERIODIC, TARGET),
                        new Expect(SUCCEEDED, OutcomeReason.INFERRED_FROM_VERSION, true, false, false)),
                Arguments.of("ACCEPTED: BOOT on the from version rolls back",
                        row(ACCEPTED, KEY, 1, 1), inform(InformEvent.BOOT, FROM),
                        new Expect(FAILED, OutcomeReason.ROLLED_BACK, true, false, false)),
                Arguments.of("ACCEPTED: BOOT on an unknown version fails",
                        row(ACCEPTED, KEY, 1, 1), inform(InformEvent.BOOT, "1.5.0"),
                        new Expect(FAILED, OutcomeReason.UNEXPECTED_VERSION, true, false, false)),
                Arguments.of("ACCEPTED: PERIODIC on the from version keeps waiting",
                        row(ACCEPTED, KEY, 1, 1), inform(InformEvent.PERIODIC, FROM),
                        new Expect(ACCEPTED, null, false, false, false)),
                Arguments.of("ACCEPTED: connection request on the from version keeps waiting",
                        row(ACCEPTED, KEY, 1, 1), inform(InformEvent.CONNECTION_REQUEST, FROM),
                        new Expect(ACCEPTED, null, false, false, false)),
                Arguments.of("ACCEPTED: deadline times out with NO_COMPLETION",
                        row(ACCEPTED, KEY, 1, 1), new Deadline(),
                        new Expect(TIMED_OUT, OutcomeReason.NO_COMPLETION, true, false, false)),
                Arguments.of("ACCEPTED: duplicate ACCEPTED ack changes nothing",
                        row(ACCEPTED, KEY, 1, 1), new Ack(KEY, AckStatus.ACCEPTED),
                        new Expect(ACCEPTED, null, false, false, false)),

                // TIMED_OUT: late evidence can still finish the row, and the slot is not retaken
                Arguments.of("TIMED_OUT: late TransferComplete with fault 0 succeeds",
                        withReason(row(TIMED_OUT, KEY, 1, 1), OutcomeReason.NO_ACK),
                        tc(InformEvent.TRANSFER_COMPLETE, TARGET, KEY, 0),
                        new Expect(SUCCEEDED, OutcomeReason.TRANSFER_COMPLETE, true, false, false)),
                Arguments.of("TIMED_OUT: late TransferComplete with a fault fails",
                        withReason(row(TIMED_OUT, KEY, 1, 1), OutcomeReason.NO_ACK),
                        tc(InformEvent.TRANSFER_COMPLETE, FROM, KEY, 9010),
                        new Expect(FAILED, OutcomeReason.FAULT, true, false, false)),
                Arguments.of("TIMED_OUT: an inform changes nothing",
                        withReason(row(TIMED_OUT, KEY, 1, 1), OutcomeReason.NO_ACK),
                        inform(InformEvent.PERIODIC, TARGET),
                        new Expect(TIMED_OUT, OutcomeReason.NO_ACK, false, false, false)),
                Arguments.of("TIMED_OUT: deadline changes nothing",
                        withReason(row(TIMED_OUT, KEY, 1, 1), OutcomeReason.NO_ACK), new Deadline(),
                        new Expect(TIMED_OUT, OutcomeReason.NO_ACK, false, false, false)),

                // Finished rows: duplicates change nothing
                Arguments.of("SUCCEEDED: duplicate TransferComplete changes nothing",
                        withReason(row(SUCCEEDED, KEY, 1, 1), OutcomeReason.TRANSFER_COMPLETE),
                        tc(InformEvent.TRANSFER_COMPLETE, TARGET, KEY, 0),
                        new Expect(SUCCEEDED, OutcomeReason.TRANSFER_COMPLETE, false, false, false)),
                Arguments.of("SUCCEEDED: deadline changes nothing",
                        withReason(row(SUCCEEDED, KEY, 1, 1), OutcomeReason.TRANSFER_COMPLETE), new Deadline(),
                        new Expect(SUCCEEDED, OutcomeReason.TRANSFER_COMPLETE, false, false, false)),
                Arguments.of("FAILED: duplicate TransferComplete changes nothing",
                        withReason(row(FAILED, KEY, 1, 1), OutcomeReason.FAULT),
                        tc(InformEvent.TRANSFER_COMPLETE, FROM, KEY, 9010),
                        new Expect(FAILED, OutcomeReason.FAULT, false, false, false)),
                Arguments.of("FAILED: duplicate REJECTED ack changes nothing",
                        withReason(row(FAILED, KEY, 1, 1), OutcomeReason.REJECTED),
                        new Ack(KEY, AckStatus.REJECTED),
                        new Expect(FAILED, OutcomeReason.REJECTED, false, false, false)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("table")
    void followsTheTable(String name, UpgradeRow before, UpgradeEvent event, Expect want) {
        Transition tr = UpgradeStateMachine.apply(before, event, NOW, T);

        assertThat(tr.changed()).as("changed").isEqualTo(want.changed());
        assertThat(tr.sendConnectionRequest()).as("send connection request").isEqualTo(want.sendCr());
        assertThat(tr.issueDownload()).as("issue download").isEqualTo(want.download());
        assertThat(tr.next().getState()).as("state").isEqualTo(want.state());
        assertThat(tr.next().getOutcomeReason()).as("outcome reason").isEqualTo(want.reason());
        if (!want.changed()) {
            assertThat(tr.next()).as("unchanged row").isEqualTo(before);
        }
    }

    @Test
    void tickSetsTheFirstNudgeAttemptAndDeadline() {
        Transition tr = UpgradeStateMachine.apply(row(PENDING, null, 0, 0), new Tick(true), NOW, T);

        assertThat(tr.next().getCrAttempts()).isEqualTo(1);
        assertThat(tr.next().getDeadlineAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void nudgeAgainCountsTheAttemptAndResetsTheDeadline() {
        Transition tr = UpgradeStateMachine.apply(row(NOTIFIED, null, 1, 0), new Deadline(), NOW, T);

        assertThat(tr.next().getCrAttempts()).isEqualTo(2);
        assertThat(tr.next().getDeadlineAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void firstDownloadSetsTheAckDeadlineAndCountsOne() {
        Transition tr = UpgradeStateMachine.apply(
                row(NOTIFIED, null, 1, 0), inform(InformEvent.PERIODIC, FROM), NOW, T);

        assertThat(tr.next().getDownloadAttempts()).isEqualTo(1);
        assertThat(tr.next().getDeadlineAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void reissueKeepsTheSameKeyAndCountsTheAttempt() {
        Transition tr = UpgradeStateMachine.apply(
                row(DOWNLOAD_SENT, KEY, 1, 1), inform(InformEvent.PERIODIC, FROM), NOW, T);

        assertThat(tr.next().getCommandKey()).isEqualTo(KEY);
        assertThat(tr.next().getDownloadAttempts()).isEqualTo(2);
    }

    @Test
    void acceptedSetsTheCompletionDeadline() {
        Transition tr = UpgradeStateMachine.apply(
                row(DOWNLOAD_SENT, KEY, 1, 1), new Ack(KEY, AckStatus.ACCEPTED), NOW, T);

        assertThat(tr.next().getDeadlineAt()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    void timingOutClearsTheDeadline() {
        Transition tr = UpgradeStateMachine.apply(row(NOTIFIED, null, 2, 0), new Deadline(), NOW, T);

        assertThat(tr.next().getDeadlineAt()).isNull();
    }

    @Test
    void successOnTheTargetVersionIsNotAMismatch() {
        Transition tr = UpgradeStateMachine.apply(
                row(DOWNLOAD_SENT, KEY, 1, 1), tc(InformEvent.TRANSFER_COMPLETE, TARGET, KEY, 0), NOW, T);

        assertThat(tr.next().isReportedVersionMismatch()).isFalse();
        assertThat(tr.next().getLastReportedVersion()).isEqualTo(TARGET);
    }

    @Test
    void successOnAnotherVersionIsRecordedAsAMismatch() {
        Transition tr = UpgradeStateMachine.apply(
                row(DOWNLOAD_SENT, KEY, 1, 1), tc(InformEvent.TRANSFER_COMPLETE, "2.0.1", KEY, 0), NOW, T);

        assertThat(tr.next().getState()).isEqualTo(SUCCEEDED);
        assertThat(tr.next().isReportedVersionMismatch()).isTrue();
        assertThat(tr.next().getLastReportedVersion()).isEqualTo("2.0.1");
    }

    @Test
    void faultStoresTheFaultCode() {
        Transition tr = UpgradeStateMachine.apply(
                row(ACCEPTED, KEY, 1, 1), tc(InformEvent.TRANSFER_COMPLETE, FROM, KEY, 9010), NOW, T);

        assertThat(tr.next().getFaultCode()).isEqualTo(9010);
    }

    @Test
    void rollbackRecordsTheVersionTheDeviceReported() {
        Transition tr = UpgradeStateMachine.apply(row(ACCEPTED, KEY, 1, 1), inform(InformEvent.BOOT, FROM), NOW, T);

        assertThat(tr.next().getLastReportedVersion()).isEqualTo(FROM);
    }

    @Test
    void lateTransferCompleteIsMarkedAsLateEvidence() {
        UpgradeRow timedOut = withReason(row(TIMED_OUT, KEY, 1, 1), OutcomeReason.NO_ACK);

        Transition tr = UpgradeStateMachine.apply(
                timedOut, tc(InformEvent.TRANSFER_COMPLETE, TARGET, KEY, 0), NOW, T);

        assertThat(tr.next().isLateEvidence()).isTrue();
    }

    @Test
    void onTimeEvidenceIsNotMarkedAsLate() {
        Transition tr = UpgradeStateMachine.apply(
                row(DOWNLOAD_SENT, KEY, 1, 1), tc(InformEvent.TRANSFER_COMPLETE, TARGET, KEY, 0), NOW, T);

        assertThat(tr.next().isLateEvidence()).isFalse();
    }
}

