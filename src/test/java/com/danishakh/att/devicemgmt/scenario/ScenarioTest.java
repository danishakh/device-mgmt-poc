package com.danishakh.att.devicemgmt.scenario;

import com.danishakh.att.devicemgmt.client.InMemoryFleetClient;
import com.danishakh.att.devicemgmt.client.TestFleetClientConfig;
import com.danishakh.att.devicemgmt.model.FleetDevice;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Base class for scenario tests. They drive the real HTTP interface through MockMvc, with the fleet
 * replaced by {@link InMemoryFleetClient} and the clock replaced by {@link MovableClock}, against the
 * PostgreSQL from Docker Compose. Every test starts with empty tables, an empty fleet and the clock at {@link #START}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestFleetClientConfig.class, ScenarioTest.ScenarioClockConfig.class})
abstract class ScenarioTest {

    static final Instant START = Instant.parse("2026-10-30T09:00:00Z");

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected InMemoryFleetClient fleet;

    @Autowired
    protected MovableClock clock;

    @Autowired
    protected JdbcClient jdbc;

    @BeforeEach
    void resetScenario() {
        jdbc.sql("TRUNCATE device_upgrade, campaign, device").update();
        fleet.reset();
        clock.set(START);
    }

    protected ResultActions postCampaign(String body) throws Exception {
        return mvc.perform(post("/campaigns").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    protected long countCampaigns() {
        return jdbc.sql("SELECT count(*) FROM campaign").query(Long.class).single();
    }

    /** The id of the campaign in a response body. */
    protected static String idOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    /** A devices-of-one-version list. Ids are {@code prefix-0}, {@code prefix-1}, and so on. */
    protected static List<FleetDevice> devices(String prefix, int count, String version) {
        return IntStream.range(0, count)
                .mapToObj(i -> new FleetDevice(prefix + "-" + i, version))
                .toList();
    }

    /** A valid create-campaign body. The image URL is derived from the target version. */
    protected static String campaignJson(String targetVersion, String currentVersion) {
        return """
                {"targetVersion":"%s","imageUrl":"http://images/%s.bin",
                 "deviceFilter":{"currentVersion":"%s"},
                 "maxConcurrent":100,"failureThresholdPercent":20}""".formatted(targetVersion, targetVersion, currentVersion);
    }

    @TestConfiguration
    static class ScenarioClockConfig {

        @Bean
        @Primary
        MovableClock scenarioClock() {
            return new MovableClock(START);
        }
    }
}



