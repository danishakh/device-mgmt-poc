package com.danishakh.att.devicemgmt.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

/** One line of {@code GET /campaigns}: enough to find a campaign. Use the progress view for the numbers. */
@Getter
@Builder
public class CampaignSummaryResponse {
    private final UUID id;
    private final String status;
    /** Why the campaign is in its status, for example the numbers behind a halt. */
    private final String statusReason;
    private final String targetVersion;
    private final String fromVersion;
    private final int maxConcurrent;
    private final BigDecimal failureThresholdPercent;
    private final int minSample;
    private final Instant createdAt;
}

