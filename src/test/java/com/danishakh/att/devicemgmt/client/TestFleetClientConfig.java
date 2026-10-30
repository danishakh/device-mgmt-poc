package com.danishakh.att.devicemgmt.client;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Import this in a scenario test to replace the simulator adapter with an {@link InMemoryFleetClient}. */
@TestConfiguration
public class TestFleetClientConfig {

    @Bean
    @Primary
    public InMemoryFleetClient inMemoryFleetClient() {
        return new InMemoryFleetClient();
    }
}

