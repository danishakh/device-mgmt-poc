package com.danishakh.att.devicemgmt;

import static org.assertj.core.api.Assertions.assertThat;

import com.danishakh.att.devicemgmt.config.DeviceMgmtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Checks every devicemgmt.* value in application.yaml. A typo in a key is a test failure,
 * instead of a silent fallback to a default. Durations are milliseconds (the Ms suffix).
 */
@SpringBootTest
class ConfigBindingTest {

    @Autowired
    DeviceMgmtProperties properties;

    @Test
    void bindsEveryValueFromApplicationYaml() {
        assertThat(properties.getSimulatorUrl()).isEqualTo("http://localhost:9000");

        assertThat(properties.getScheduler().getTickIntervalMs()).isEqualTo(1000);
        assertThat(properties.getScheduler().getProgressLogIntervalMs()).isEqualTo(10000);

        assertThat(properties.getCampaign().getDefaultMaxConcurrent()).isEqualTo(100);
        assertThat(properties.getCampaign().getDefaultMinSample()).isEqualTo(50);

        assertThat(properties.getTimeouts().getCrWaitMs()).isEqualTo(30000);
        assertThat(properties.getTimeouts().getMaxCr()).isEqualTo(2);
        assertThat(properties.getTimeouts().getAckWaitMs()).isEqualTo(30000);
        assertThat(properties.getTimeouts().getMaxDl()).isEqualTo(2);
        assertThat(properties.getTimeouts().getCompletionWaitMs()).isEqualTo(300000);

        assertThat(properties.getHttpClient().getConnectTimeoutMs()).isEqualTo(2000);
        assertThat(properties.getHttpClient().getReadTimeoutMs()).isEqualTo(5000);
        assertThat(properties.getHttpClient().getMaxRetries()).isEqualTo(2);
        assertThat(properties.getHttpClient().getPoolSize()).isEqualTo(32);
    }
}

