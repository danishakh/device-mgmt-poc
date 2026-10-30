package com.danishakh.att.devicemgmt.dto;
import static org.assertj.core.api.Assertions.assertThat;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
/** Validation of the create-campaign request: every bad field is reported, not just the first. */
class CreateCampaignRequestTest {
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static CreateCampaignRequest valid() {
        CreateCampaignRequest request = new CreateCampaignRequest();
        request.setTargetVersion("2.0.0");
        request.setImageUrl("http://images/2.0.0.bin");
        CreateCampaignRequest.DeviceFilter filter = new CreateCampaignRequest.DeviceFilter();
        filter.setCurrentVersion("1.0.0");
        request.setDeviceFilter(filter);
        request.setMaxConcurrent(100);
        request.setFailureThresholdPercent(new BigDecimal("20"));
        return request;
    }
    private static List<String> violatedFields(CreateCampaignRequest request) {
        return VALIDATOR.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .sorted()
                .toList();
    }
    @Test
    void aCompleteRequestIsValid() {
        assertThat(violatedFields(valid())).isEmpty();
    }
    @Test
    void minSampleIsOptional() {
        CreateCampaignRequest request = valid();
        request.setMinSample(null);
        assertThat(violatedFields(request)).isEmpty();
    }
    @Test
    void everyViolationIsReportedTogether() {
        CreateCampaignRequest request = new CreateCampaignRequest();
        request.setTargetVersion(" ");
        request.setImageUrl("");
        request.setMaxConcurrent(0);
        request.setFailureThresholdPercent(new BigDecimal("101"));
        request.setMinSample(0);
        assertThat(violatedFields(request)).containsExactly(
                "deviceFilter", "failureThresholdPercent", "imageUrl", "maxConcurrent", "minSample", "targetVersion");
    }
    @Test
    void missingNumbersAreReported() {
        CreateCampaignRequest request = valid();
        request.setMaxConcurrent(null);
        request.setFailureThresholdPercent(null);
        assertThat(violatedFields(request)).containsExactly("failureThresholdPercent", "maxConcurrent");
    }
    @Test
    void aBlankCurrentVersionIsReportedWithItsPath() {
        CreateCampaignRequest request = valid();
        request.getDeviceFilter().setCurrentVersion("");
        assertThat(violatedFields(request)).containsExactly("deviceFilter.currentVersion");
    }
    @Test
    void targetVersionMustDifferFromCurrentVersion() {
        CreateCampaignRequest request = valid();
        request.setTargetVersion("1.0.0");
        assertThat(violatedFields(request)).containsExactly("targetVersionDifferentFromCurrent");
    }
    @Test
    void thresholdBoundsAreInclusive() {
        CreateCampaignRequest request = valid();
        request.setFailureThresholdPercent(BigDecimal.ZERO);
        assertThat(violatedFields(request)).isEmpty();
        request.setFailureThresholdPercent(new BigDecimal("100"));
        assertThat(violatedFields(request)).isEmpty();
        request.setFailureThresholdPercent(new BigDecimal("-0.1"));
        assertThat(violatedFields(request)).containsExactly("failureThresholdPercent");
        request.setFailureThresholdPercent(new BigDecimal("100.1"));
        assertThat(violatedFields(request)).containsExactly("failureThresholdPercent");
    }
}
