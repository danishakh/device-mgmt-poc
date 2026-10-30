package com.danishakh.att.devicemgmt.statemachine;

import static com.danishakh.att.devicemgmt.statemachine.UpgradeState.PENDING;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * One device_upgrade row as the state machine sees it. Immutable: the state machine returns a modified copy.
 * targetVersion and imageUrl are not columns of device_upgrade. The repository reads them from the campaign
 * when it loads the row. id and version identify the row for a conditional write; they are null and 0 for a
 * row that has not been saved yet.
 */
@Value
@Builder(toBuilder = true)
public class UpgradeRow {
    Long id;
    UUID campaignId;
    String deviceId;
    int version;
    UpgradeState state;
    String fromVersion;
    String targetVersion;
    String imageUrl;
    String commandKey;
    int crAttempts;
    int downloadAttempts;
    Instant deadlineAt;
    String lastReportedVersion;
    Integer faultCode;
    OutcomeReason outcomeReason;
    String outcomeDetail;
    boolean lateEvidence;
    boolean reportedVersionMismatch;
    TrailingEvidence trailingEvidence;
    Integer trailingFaultCode;

    /** A brand-new row for a device just snapshotted into a campaign: state PENDING, no history yet. */
    public static UpgradeRow pending(String fromVersion, String targetVersion) {
        return builder().state(PENDING).fromVersion(fromVersion).targetVersion(targetVersion).build();
    }
}


