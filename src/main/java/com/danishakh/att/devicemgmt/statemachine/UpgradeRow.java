package com.danishakh.att.devicemgmt.statemachine;

import static com.danishakh.att.devicemgmt.statemachine.UpgradeState.PENDING;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

/**
 * One device_upgrade row as the state machine sees it. Immutable: the state machine returns a modified copy.
 * targetVersion is not a column of device_upgrade. The service copies it from the campaign when it loads the row.
 */
@Value
@Builder(toBuilder = true)
public class UpgradeRow {
    UpgradeState state;
    String fromVersion;
    String targetVersion;
    String commandKey;
    int crAttempts;
    int downloadAttempts;
    Instant deadlineAt;
    String lastReportedVersion;
    Integer faultCode;
    OutcomeReason outcomeReason;
    boolean lateEvidence;
    boolean reportedVersionMismatch;

    /** A brand-new row for a device just snapshotted into a campaign: state PENDING, no history yet. */
    public static UpgradeRow pending(String fromVersion, String targetVersion) {
        return builder().state(PENDING).fromVersion(fromVersion).targetVersion(targetVersion).build();
    }
}


