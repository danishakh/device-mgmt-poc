package com.danishakh.att.devicemgmt.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Every devicemgmt.* value from application.yaml. The app refuses to start if any is invalid,
 * and reports all violations together. Durations are milliseconds (the Ms suffix).
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "devicemgmt")
public class DeviceMgmtProperties {

    /** Base URL of the device simulator: http or https, with a host. */
    @NotBlank
    @Pattern(regexp = "^https?://[^\\s/]+(/\\S*)?$", message = "must be an http or https URL with a host")
    private String simulatorUrl;

    @Valid
    private Scheduler scheduler = new Scheduler();

    @Valid
    private Campaign campaign = new Campaign();

    @Valid
    private Timeouts timeouts = new Timeouts();

    @Valid
    private HttpClient httpClient = new HttpClient();

    @Getter
    @Setter
    public static class Scheduler {
        @Positive
        private long tickIntervalMs;

        @Positive
        private long progressLogIntervalMs;
    }

    @Getter
    @Setter
    public static class Campaign {
        @Min(1)
        private int defaultMaxConcurrent;

        @Min(1)
        private int defaultMinSample;
    }

    @Getter
    @Setter
    public static class Timeouts {
        /** NOTIFIED: how long to wait for the device to inform after a connection request. */
        @Positive
        private long crWaitMs;

        /** NOTIFIED: max number of connection requests to send before giving up (NO_CALLBACK). */
        @Min(1)
        private int maxCr;

        /** DOWNLOAD_SENT: how long to wait for the acknowledgement. */
        @Positive
        private long ackWaitMs;

        /** DOWNLOAD_SENT: max number of times the same command key is issued, one per inform. */
        @Min(1)
        private int maxDl;

        /** ACCEPTED: how long to wait for a TransferComplete or version evidence. */
        @Positive
        private long completionWaitMs;
    }

    @Getter
    @Setter
    public static class HttpClient {
        @Positive
        private long connectTimeoutMs;

        @Positive
        private long readTimeoutMs;

        /** Boxed so that a missing key is caught: 0 is a legal value, and would silently turn retries off. */
        @NotNull
        @Min(0)
        private Integer maxRetries;

        @Min(1)
        private int poolSize;
    }
}

