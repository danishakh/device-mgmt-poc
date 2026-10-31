package com.danishakh.att.devicemgmt.repository;
import com.danishakh.att.devicemgmt.model.UpgradeBreakdown;
import com.danishakh.att.devicemgmt.statemachine.OutcomeReason;
import com.danishakh.att.devicemgmt.statemachine.TrailingEvidence;
import com.danishakh.att.devicemgmt.statemachine.DeviceUpgradeRow;
import com.danishakh.att.devicemgmt.statemachine.DeviceUpgradeState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
/**
 * Reads and writes the device_upgrade table. It has no transactions of its own: the service that
 * calls it owns the transaction.
 */
@Repository
public class DeviceUpgradeRepository {
    private final JdbcClient jdbc;
    public DeviceUpgradeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // The SELECT_UPGRADE query is used in multiple methods to retrieve upgrade rows with campaign details.
    private static final String SELECT_UPGRADE = """
            SELECT du.id, du.campaign_id, du.device_id, du.version, du.state, du.from_version,
                   c.target_version, c.image_url, du.command_key, du.cr_attempts, du.download_attempts,
                   du.deadline_at, du.last_reported_version, du.fault_code, du.outcome_reason,
                   du.outcome_detail, du.late_evidence, du.reported_version_mismatch,
                   du.trailing_evidence, du.trailing_fault_code,
                   du.transfer_start_time, du.transfer_complete_time FROM device_upgrade du
              JOIN campaign c ON c.id = du.campaign_id
            """;


    /**
     * Inserts a PENDING upgrade for each device, skipping devices that already have an open upgrade.
     *
     * @param campaignId the campaign the upgrades belong to
     * @param fromVersion the firmware version the devices start from
     * @param deviceIds the devices to add
     * @param now the time to record as created and updated
     * @return how many upgrades were inserted
     */
    public int insert(UUID campaignId, String fromVersion, List<String> deviceIds, Instant now) {
        return jdbc.sql("""
                INSERT INTO device_upgrade (campaign_id, device_id, from_version, state, created_at, updated_at)
                SELECT ?, t.device_id, ?, 'PENDING', ?, ?
                  FROM unnest(CAST(? AS text[])) AS t(device_id)
                ON CONFLICT DO NOTHING""")
                .param(campaignId)
                .param(fromVersion)
                .param(Timestamp.from(now))
                .param(Timestamp.from(now))
                .param(deviceIds.toArray(String[]::new))
                .update();
    }

    /**
     * Counts a campaign's device upgrades grouped by state, outcome reason and trailing evidence.
     *
     * @param campaignId the campaign to count
     * @return one entry per group that has rows
     */
    public List<UpgradeBreakdown> getCampaignProgressById(UUID campaignId) {
        return jdbc.sql("""
                SELECT state, outcome_reason, trailing_evidence, trailing_fault_code, count(*) AS total
                  FROM device_upgrade
                 WHERE campaign_id = :campaignId
                 GROUP BY state, outcome_reason, trailing_evidence, trailing_fault_code""")
                .param("campaignId", campaignId)
                .query((rs, rowNum) -> {
                    String reason = rs.getString("outcome_reason");
                    String trailing = rs.getString("trailing_evidence");
                    int faultCode = rs.getInt("trailing_fault_code");
                    return UpgradeBreakdown.builder()
                            .state(DeviceUpgradeState.valueOf(rs.getString("state")))
                            .outcomeReason(reason == null ? null : OutcomeReason.valueOf(reason))
                            .trailingEvidence(trailing == null ? null : TrailingEvidence.valueOf(trailing))
                            .trailingFaultCode(rs.wasNull() ? null : faultCode)
                            .count(rs.getInt("total"))
                            .build();
                })
                .list();
    }

    /**
     * Finds the upgrade a Download command key identifies.
     *
     * @param commandKey the command key to look up
     * @return the upgrade, or empty when no row has that key
     */
    public Optional<DeviceUpgradeRow> findByCommandKey(String commandKey) {
        return jdbc.sql(SELECT_UPGRADE + " WHERE du.command_key = :commandKey")
                .param("commandKey", commandKey)
                .query(DeviceUpgradeRepository::mapRow)
                .optional();
    }

    /**
     * Finds the device's in-flight upgrade (NOTIFIED, DOWNLOAD_SENT or ACCEPTED).
     *
     * @param deviceId the device to look up
     * @return the in-flight upgrade, or empty if the device has none
     */
    public Optional<DeviceUpgradeRow> findInFlightByDevice(String deviceId) {
        return jdbc.sql(SELECT_UPGRADE + """
                 WHERE du.device_id = :deviceId
                   AND du.state IN ('NOTIFIED', 'DOWNLOAD_SENT', 'ACCEPTED')""")
                .param("deviceId", deviceId)
                .query(DeviceUpgradeRepository::mapRow)
                .optional();
    }

    /**
     * Finds the device's most recent timed-out upgrade with NO_ACK or NO_COMPLETION and no trailing evidence.
     *
     * @param deviceId the device to look up
     * @return the upgrade, or empty if none matches
     */
    public Optional<DeviceUpgradeRow> findMostRecentTimedOutByDevice(String deviceId) {
        return jdbc.sql(SELECT_UPGRADE + """
                 WHERE du.device_id = :deviceId
                   AND du.state = 'TIMED_OUT'
                   AND du.outcome_reason IN ('NO_ACK', 'NO_COMPLETION')
                   AND du.trailing_evidence IS NULL
                 ORDER BY du.terminal_at DESC, du.id DESC
                 LIMIT 1""")
                .param("deviceId", deviceId)
                .query(DeviceUpgradeRepository::mapRow)
                .optional();
    }

    /**
     * Writes the next state of an upgrade, only if the row has not changed since it was loaded.
     *
     * @param original the row as it was loaded, used for the id, state and version check
     * @param next the row state to write
     * @param now the time of the write
     * @return true when the row was written, false when it had changed since {@code original} was loaded
     */
    public boolean update(DeviceUpgradeRow original, DeviceUpgradeRow next, Instant now) {
        int rows = jdbc.sql("""
                UPDATE device_upgrade
                   SET state                     = :nextState,
                       outcome_reason            = :outcomeReason,
                       outcome_detail            = :outcomeDetail,
                       command_key               = :commandKey,
                       cr_attempts               = :crAttempts,
                       download_attempts         = :downloadAttempts,
                       deadline_at               = :deadlineAt,
                       last_reported_version     = :lastReportedVersion,
                       fault_code                = :faultCode,
                       late_evidence             = :lateEvidence,
                       reported_version_mismatch = :reportedVersionMismatch,
                       trailing_evidence         = :trailingEvidence,
                       trailing_fault_code       = :trailingFaultCode,
                       transfer_start_time       = :transferStartTime,
                       transfer_complete_time    = :transferCompleteTime,
                       terminal_at = CASE WHEN :nextState IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED')
                                          THEN COALESCE(terminal_at, :now) ELSE terminal_at END,
                       updated_at                = :now,
                       version                   = version + 1
                 WHERE id = :id
                   AND state = :expectedState
                   AND version = :expectedVersion""")
                .param("nextState", next.getState().name())
                .param("outcomeReason", name(next.getOutcomeReason()))
                .param("outcomeDetail", next.getOutcomeDetail())
                .param("commandKey", next.getCommandKey())
                .param("crAttempts", next.getCrAttempts())
                .param("downloadAttempts", next.getDownloadAttempts())
                .param("deadlineAt", timestamp(next.getDeadlineAt()))
                .param("lastReportedVersion", next.getLastReportedVersion())
                .param("faultCode", next.getFaultCode())
                .param("lateEvidence", next.isLateEvidence())
                .param("reportedVersionMismatch", next.isReportedVersionMismatch())
                .param("trailingEvidence", name(next.getTrailingEvidence()))
                .param("trailingFaultCode", next.getTrailingFaultCode())
                .param("transferStartTime", timestamp(next.getTransferStartTime()))
                .param("transferCompleteTime", timestamp(next.getTransferCompleteTime()))
                .param("now", Timestamp.from(now))
                .param("id", original.getId())
                .param("expectedState", original.getState().name())
                .param("expectedVersion", original.getVersion())
                .update();
        return rows == 1;
    }

    /**
     * Maps one device_upgrade row, joined with its campaign, to a DeviceUpgradeRow.
     *
     * @param rs the result set, positioned on the row
     * @param rowNum the row number
     * @return the mapped upgrade row
     * @throws SQLException if a column cannot be read
     */
    private static DeviceUpgradeRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp deadline = rs.getTimestamp("deadline_at");
        String reason = rs.getString("outcome_reason");
        String trailing = rs.getString("trailing_evidence");
        return DeviceUpgradeRow.builder()
                .id(rs.getLong("id"))
                .campaignId(rs.getObject("campaign_id", UUID.class))
                .deviceId(rs.getString("device_id"))
                .version(rs.getInt("version"))
                .state(DeviceUpgradeState.valueOf(rs.getString("state")))
                .fromVersion(rs.getString("from_version"))
                .targetVersion(rs.getString("target_version"))
                .imageUrl(rs.getString("image_url"))
                .commandKey(rs.getString("command_key"))
                .crAttempts(rs.getInt("cr_attempts"))
                .downloadAttempts(rs.getInt("download_attempts"))
                .deadlineAt(deadline == null ? null : deadline.toInstant())
                .lastReportedVersion(rs.getString("last_reported_version"))
                .faultCode(rs.getObject("fault_code", Integer.class))
                .outcomeReason(reason == null ? null : OutcomeReason.valueOf(reason))
                .outcomeDetail(rs.getString("outcome_detail"))
                .lateEvidence(rs.getBoolean("late_evidence"))
                .reportedVersionMismatch(rs.getBoolean("reported_version_mismatch"))
                .trailingEvidence(trailing == null ? null : TrailingEvidence.valueOf(trailing))
                .trailingFaultCode(rs.getObject("trailing_fault_code", Integer.class))
                .transferStartTime(instant(rs.getTimestamp("transfer_start_time")))
                .transferCompleteTime(instant(rs.getTimestamp("transfer_complete_time")))
                .build();
    }

    /**
     * Converts a nullable timestamp to an instant.
     *
     * @param timestamp the timestamp, or null
     * @return the instant, or null when the timestamp is null
     */
    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * Returns an enum's name for a text parameter.
     *
     * @param value the enum value, or null
     * @return the name, or null when the value is null
     */
    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    /**
     * Converts a nullable instant to a timestamp.
     *
     * @param instant the instant, or null
     * @return the timestamp, or null when the instant is null
     */
    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
