package com.danishakh.att.devicemgmt.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
/**
 * Body of {@code POST /campaigns} (HLD §9.1). Bean Validation reports every violation together,
 * so an operator can fix the whole request in one go.
 */
@Getter
@Setter
public class CreateCampaignRequest {
    @NotBlank(message = "must be null or a non-empty string")
    @NotNull
    private String targetVersion;

    @NotBlank
    private String imageUrl;

    @NotNull
    @Valid
    private DeviceFilter deviceFilter;

    @NotNull
    @Min(1)
    private Integer maxConcurrent;

    @NotNull
    @DecimalMin("0")
    @DecimalMax("100")
    private BigDecimal failureThresholdPercent;

    /** Optional: when null, the service uses {@code devicemgmt.campaign.default-min-sample}. */
    @Min(1)
    private Integer minSample;

    @AssertTrue(message = "must differ from deviceFilter.currentVersion")
    public boolean isTargetVersionDifferentFromCurrent() {
        if (targetVersion == null || deviceFilter == null || deviceFilter.getCurrentVersion() == null) {
            return true;
        }
        return !targetVersion.equals(deviceFilter.getCurrentVersion());
    }

    /** Which devices join the campaign. */
    @Getter
    @Setter
    public static class DeviceFilter {
        /** The firmware version the devices run now. */
        @NotBlank
        private String currentVersion;
    }
}
