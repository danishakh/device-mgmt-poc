package com.danishakh.att.devicemgmt.config;

import com.danishakh.att.devicemgmt.statemachine.DeviceUpgradeTimings;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans that the state machine and the services share: the timings and the clock. */
@Configuration
@EnableConfigurationProperties(DeviceMgmtProperties.class)
public class AppConfig {

    /** The one source of "now". Tests replace it with a clock they can move forward. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public DeviceUpgradeTimings upgradeTimings(DeviceMgmtProperties properties) {
        DeviceMgmtProperties.Timeouts timeouts = properties.getTimeouts();
        return new DeviceUpgradeTimings(
                Duration.ofMillis(timeouts.getCrWaitMs()),
                timeouts.getMaxCr(),
                Duration.ofMillis(timeouts.getAckWaitMs()),
                timeouts.getMaxDl(),
                Duration.ofMillis(timeouts.getCompletionWaitMs()));
    }
}

