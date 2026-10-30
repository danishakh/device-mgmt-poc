package com.danishakh.att.devicemgmt.repository;
import com.danishakh.att.devicemgmt.model.Campaign;
import com.danishakh.att.devicemgmt.model.CampaignStatus;
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
 * Reads and writes the campaign table. It has no transactions of its own: the service that calls
 * it owns the transaction.
 */
@Repository
public class CampaignRepository {
    private static final String COLUMNS = """
            id, target_version, image_url, from_version, max_concurrent, failure_threshold_pct, min_sample,
            status, status_reason, excluded_devices, created_at, updated_at, completed_at""";
    private final JdbcClient jdbc;

    public CampaignRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a new campaign exactly as given, including its status and timestamps. */
    public void insert(Campaign campaign) {
        jdbc.sql("""
                INSERT INTO campaign (id, target_version, image_url, from_version, max_concurrent,
                                      failure_threshold_pct, min_sample, status, status_reason,
                                      excluded_devices, created_at, updated_at, completed_at)
                VALUES (:id, :targetVersion, :imageUrl, :fromVersion, :maxConcurrent,
                        :failureThresholdPct, :minSample, :status, :statusReason,
                        :excludedDevices, :createdAt, :updatedAt, :completedAt)""")
                .param("id", campaign.getId())
                .param("targetVersion", campaign.getTargetVersion())
                .param("imageUrl", campaign.getImageUrl())
                .param("fromVersion", campaign.getFromVersion())
                .param("maxConcurrent", campaign.getMaxConcurrent())
                .param("failureThresholdPct", campaign.getFailureThresholdPct())
                .param("minSample", campaign.getMinSample())
                .param("status", campaign.getStatus().name())
                .param("statusReason", campaign.getStatusReason())
                .param("excludedDevices", campaign.getExcludedDevices())
                .param("createdAt", toTimestamp(campaign.getCreatedAt()))
                .param("updatedAt", toTimestamp(campaign.getUpdatedAt()))
                .param("completedAt", toTimestamp(campaign.getCompletedAt()))
                .update();
    }

    /** Returns the campaign with the given id, or empty if none exists. */
    public Optional<Campaign> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM campaign WHERE id = :id")
                .param("id", id)
                .query(CampaignRepository::map)
                .optional();
    }

    /** Returns all campaigns, newest first. */
    public List<Campaign> findAll() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM campaign ORDER BY created_at DESC, id")
                .query(CampaignRepository::map)
                .list();
    }

    /**
     * Moves a campaign to {@code next} only if it is still in {@code expected}, so two callers
     * cannot both win. HALTED and COMPLETED also set {@code completed_at}.
     *
     * @return true if the campaign was in {@code expected} and has been moved, false otherwise
     */
    public boolean updateStatus(UUID id, CampaignStatus expected, CampaignStatus next, String reason, Instant now) {
        boolean terminal = next == CampaignStatus.HALTED || next == CampaignStatus.COMPLETED;
        int rows = jdbc.sql("""
                UPDATE campaign
                   SET status = :next, status_reason = :reason, updated_at = :now,
                       completed_at = CASE WHEN :terminal THEN :now ELSE completed_at END
                 WHERE id = :id AND status = :expected""")
                .param("id", id)
                .param("expected", expected.name())
                .param("next", next.name())
                .param("reason", reason)
                .param("now", toTimestamp(now))
                .param("terminal", terminal)
                .update();
        return rows == 1;
    }
    /** Records how many devices were left out at creation because they already had an open upgrade. */
    public void updateExcludedDevices(UUID id, int excludedDevices) {
        jdbc.sql("UPDATE campaign SET excluded_devices = :excluded WHERE id = :id")
                .param("id", id)
                .param("excluded", excludedDevices)
                .update();
    }
    private static Campaign map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return Campaign.builder()
                .id(rs.getObject("id", UUID.class))
                .targetVersion(rs.getString("target_version"))
                .imageUrl(rs.getString("image_url"))
                .fromVersion(rs.getString("from_version"))
                .maxConcurrent(rs.getInt("max_concurrent"))
                .failureThresholdPct(rs.getBigDecimal("failure_threshold_pct"))
                .minSample(rs.getInt("min_sample"))
                .status(CampaignStatus.valueOf(rs.getString("status")))
                .statusReason(rs.getString("status_reason"))
                .excludedDevices(rs.getInt("excluded_devices"))
                .createdAt(rs.getTimestamp("created_at").toInstant())
                .updatedAt(rs.getTimestamp("updated_at").toInstant())
                .completedAt(completedAt == null ? null : completedAt.toInstant())
                .build();
    }
    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
