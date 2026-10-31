package com.danishakh.att.devicemgmt.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.danishakh.att.devicemgmt.statemachine.DeviceUpgradeTimings;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** The state machine's timings come from devicemgmt.timeouts.* in application.yaml, and time comes from a Clock bean. */
@SpringBootTest
class AppConfigTest {

    @Autowired
    DeviceUpgradeTimings timings;

    @Autowired
    Clock clock;

    @Test
    void timingsAreBuiltFromApplicationYaml() {
        assertThat(timings.crWait()).isEqualTo(Duration.ofSeconds(30));
        assertThat(timings.maxCr()).isEqualTo(2);
        assertThat(timings.ackWait()).isEqualTo(Duration.ofSeconds(30));
        assertThat(timings.maxDl()).isEqualTo(2);
        assertThat(timings.completionWait()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void clockIsAvailable() {
        assertThat(clock.instant()).isNotNull();
    }
}

