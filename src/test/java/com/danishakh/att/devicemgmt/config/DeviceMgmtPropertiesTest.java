package com.danishakh.att.devicemgmt.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.FieldError;

/** Startup validation of devicemgmt.*: a bad value stops the app, and every bad value is reported. */
class DeviceMgmtPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(DeviceMgmtProperties.class)
    static class TestConfig {
    }

    private static final String[] DEFAULTS = {
            "devicemgmt.simulator-url=http://localhost:9000",
            "devicemgmt.scheduler.tick-interval-ms=1000",
            "devicemgmt.scheduler.progress-log-interval-ms=10000",
            "devicemgmt.campaign.default-max-concurrent=100",
            "devicemgmt.campaign.default-min-sample=50",
            "devicemgmt.timeouts.cr-wait-ms=30000",
            "devicemgmt.timeouts.max-cr=2",
            "devicemgmt.timeouts.ack-wait-ms=30000",
            "devicemgmt.timeouts.max-dl=2",
            "devicemgmt.timeouts.completion-wait-ms=300000",
            "devicemgmt.http-client.connect-timeout-ms=2000",
            "devicemgmt.http-client.read-timeout-ms=5000",
            "devicemgmt.http-client.max-retries=2",
            "devicemgmt.http-client.pool-size=32"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(DEFAULTS);

    /** A runner with every default except the given key, to check that a missing key is caught. */
    private static ApplicationContextRunner runnerWithout(String key) {
        String[] withoutKey = Arrays.stream(DEFAULTS)
                .filter(property -> !property.startsWith(key + "="))
                .toArray(String[]::new);
        return new ApplicationContextRunner()
                .withUserConfiguration(TestConfig.class)
                .withPropertyValues(withoutKey);
    }

    /** The property paths that failed validation, found in the startup failure's cause chain. */
    private static List<String> violatedProperties(Throwable startupFailure) {
        Throwable cause = startupFailure;
        while (cause != null && !(cause instanceof BindValidationException)) {
            cause = cause.getCause();
        }
        assertThat(cause).as("a BindValidationException in the cause chain").isNotNull();
        return ((BindValidationException) cause).getValidationErrors().getAllErrors().stream()
                .filter(FieldError.class::isInstance)
                .map(error -> ((FieldError) error).getField())
                .toList();
    }

    @Test
    void acceptsTheDefaults() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void acceptsTheLowestAllowedValues() {
        runner.withPropertyValues(
                        "devicemgmt.campaign.default-min-sample=1",
                        "devicemgmt.http-client.max-retries=0")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void rejectsANonHttpOrMalformedSimulatorUrl() {
        runner.withPropertyValues("devicemgmt.simulator-url=ftp://sim:9000")
                .run(context -> assertThat(violatedProperties(context.getStartupFailure()))
                        .containsExactly("simulatorUrl"));
        runner.withPropertyValues("devicemgmt.simulator-url=http://bad host")
                .run(context -> assertThat(violatedProperties(context.getStartupFailure()))
                        .containsExactly("simulatorUrl"));
    }

    @Test
    void rejectsAMinSampleBelowOne() {
        runner.withPropertyValues("devicemgmt.campaign.default-min-sample=0")
                .run(context -> assertThat(violatedProperties(context.getStartupFailure()))
                        .containsExactly("campaign.defaultMinSample"));
    }

    @Test
    void rejectsAMissingSimulatorUrl() {
        runnerWithout("devicemgmt.simulator-url")
                .run(context -> assertThat(violatedProperties(context.getStartupFailure()))
                        .containsExactly("simulatorUrl"));
    }

    @Test
    void rejectsAMissingMaxRetriesBecauseZeroIsALegalValue() {
        runnerWithout("devicemgmt.http-client.max-retries")
                .run(context -> assertThat(violatedProperties(context.getStartupFailure()))
                        .containsExactly("httpClient.maxRetries"));
    }

    @Test
    void reportsEveryViolationTogether() {
        runner.withPropertyValues(
                        "devicemgmt.scheduler.tick-interval-ms=0",
                        "devicemgmt.timeouts.cr-wait-ms=-1",
                        "devicemgmt.timeouts.max-cr=0",
                        "devicemgmt.http-client.max-retries=-1",
                        "devicemgmt.http-client.pool-size=0")
                .run(context -> assertThat(violatedProperties(context.getStartupFailure()))
                        .containsExactlyInAnyOrder(
                                "scheduler.tickIntervalMs",
                                "timeouts.crWaitMs",
                                "timeouts.maxCr",
                                "httpClient.maxRetries",
                                "httpClient.poolSize"));
    }
}

