package com.danishakh.att.devicemgmt.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Checks the devicemgmt.* values at startup and refuses to start if any is invalid.
 * All violations are reported together, so one restart shows every problem.
 * Durations are milliseconds (the Ms suffix).
 */
@Component
public class ConfigValidator {

    public ConfigValidator(
            @Value("${devicemgmt.simulator-url}") String simulatorUrl,
            @Value("${devicemgmt.scheduler.tick-interval-ms}") long tickIntervalMs,
            @Value("${devicemgmt.scheduler.progress-log-interval-ms}") long progressLogIntervalMs,
            @Value("${devicemgmt.campaign.default-max-concurrent}") int defaultMaxConcurrent,
            @Value("${devicemgmt.campaign.default-min-sample}") int defaultMinSample,
            @Value("${devicemgmt.timeouts.cr-wait-ms}") long crWaitMs,
            @Value("${devicemgmt.timeouts.max-cr}") int maxCr,
            @Value("${devicemgmt.timeouts.ack-wait-ms}") long ackWaitMs,
            @Value("${devicemgmt.timeouts.max-dl}") int maxDl,
            @Value("${devicemgmt.timeouts.completion-wait-ms}") long completionWaitMs,
            @Value("${devicemgmt.http-client.connect-timeout-ms}") long connectTimeoutMs,
            @Value("${devicemgmt.http-client.read-timeout-ms}") long readTimeoutMs,
            @Value("${devicemgmt.http-client.max-retries}") int maxRetries,
            @Value("${devicemgmt.http-client.pool-size}") int poolSize
	) {

        List<String> errors = new ArrayList<>();

        checkHttpUrl(errors, "devicemgmt.simulator-url", simulatorUrl);

        requirePositiveMs(errors, "devicemgmt.scheduler.tick-interval-ms", tickIntervalMs);
        requirePositiveMs(errors, "devicemgmt.scheduler.progress-log-interval-ms",
                progressLogIntervalMs);

        requireAtLeast(errors, "devicemgmt.campaign.default-max-concurrent", defaultMaxConcurrent, 1);
        requireAtLeast(errors, "devicemgmt.campaign.default-min-sample", defaultMinSample, 0);

        requirePositiveMs(errors, "devicemgmt.timeouts.cr-wait-ms", crWaitMs);
        requireAtLeast(errors, "devicemgmt.timeouts.max-cr", maxCr, 1);
        requirePositiveMs(errors, "devicemgmt.timeouts.ack-wait-ms", ackWaitMs);
        requireAtLeast(errors, "devicemgmt.timeouts.max-dl", maxDl, 1);
        requirePositiveMs(errors, "devicemgmt.timeouts.completion-wait-ms", completionWaitMs);

        requirePositiveMs(errors, "devicemgmt.http-client.connect-timeout-ms", connectTimeoutMs);
        requirePositiveMs(errors, "devicemgmt.http-client.read-timeout-ms", readTimeoutMs);
        requireAtLeast(errors, "devicemgmt.http-client.max-retries", maxRetries, 0);
        requireAtLeast(errors, "devicemgmt.http-client.pool-size", poolSize, 1);

        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Invalid devicemgmt configuration:\n  - " + String.join("\n  - ", errors));
        }
    }

    private static void checkHttpUrl(List<String> errors, String key, String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
                errors.add(key + " must be an http or https URL with a host, was '" + value + "'");
            }
        } catch (IllegalArgumentException e) {
            errors.add(key + " is not a valid URI, was '" + value + "'");
        }
    }

    private static void requirePositiveMs(List<String> errors, String key, long valueMs) {
        if (valueMs <= 0) {
            errors.add(key + " must be greater than 0 ms, was " + valueMs);
        }
    }

    private static void requireAtLeast(List<String> errors, String key, int value, int min) {
        if (value < min) {
            errors.add(key + " must be at least " + min + ", was " + value);
        }
    }
}
