package com.danishakh.att.devicemgmt.model;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
/** One row of the campaign table. */
@Getter
@Builder(toBuilder = true)
public class Campaign {
    private final UUID id;
    private final String targetVersion;
    private final String imageUrl;
    private final String fromVersion;
    private final int maxConcurrent;
    private final BigDecimal failureThresholdPct;
    private final int minSample;
    private final CampaignStatus status;
    /** Why the campaign is in its status, for example the numbers behind a halt. Null when there is nothing to say. */
    private final String statusReason;
    /** Devices left out at creation because they already had an open upgrade. */
    private final int excludedDevices;
    private final Instant createdAt;
    private final Instant updatedAt;
    /** Set when the campaign reaches HALTED or COMPLETED. */
    private final Instant completedAt;
}
