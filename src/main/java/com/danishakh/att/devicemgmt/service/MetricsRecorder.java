package com.danishakh.att.devicemgmt.service;

import com.danishakh.att.devicemgmt.statemachine.DeviceCallbackResult;
import com.danishakh.att.devicemgmt.statemachine.DeviceUpgradeRow;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Records the activity counters of HLD 10.6. Tag names live here and nowhere else. Callers decide when
 * to count, and this class only records.
 */
@Component
public class MetricsRecorder {

    private final MeterRegistry registry;

    public MetricsRecorder(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Counts a device callback that the state machine classified. */
    public void recordDeviceCallback(String endpoint, String event, DeviceCallbackResult result) {
        recordMetricToRegistry(endpoint, event, tag(result));
    }

    /** Counts a device callback whose command key matches no device upgrade. The state machine never sees it. */
    public void recordDeviceCallBackWithUnknownCommandKey(String endpoint, String event) {
        recordMetricToRegistry(endpoint, event, "unknown_command_key");
    }

    /** Counts a state change. Call it only when the row was written, so duplicates do not inflate it. */
    public void recordDeviceTransition(DeviceUpgradeRow from, DeviceUpgradeRow to) {
        String reason = to.getOutcomeReason() == null ? "none" : tag(to.getOutcomeReason());
        registry.counter("dm_transitions_total",
                "from", tag(from.getState()),
                "to", tag(to.getState()),
                "reason", reason).increment();
    }

    /** Counts a Download sent in a reply. A reissue is a repeat of a Download under the same command key. */
    public void recordDownloadCommandIssued(boolean reissue) {
        registry.counter("dm_commands_issued_total", "reissue", String.valueOf(reissue)).increment();
    }

    /** Counts a successful upgrade that reported a version other than the target. */
    public void recordVersionMismatch() {
        registry.counter("dm_version_mismatch_total").increment();
    }

    private void recordMetricToRegistry(String endpoint, String event, String result) {
        registry.counter("dm_callbacks_total", "endpoint", endpoint, "event", event, "result", result).increment();
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}

