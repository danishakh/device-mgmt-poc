package com.danishakh.att.devicemgmt.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/** Scenarios for creating campaigns and the snapshot of devices they take (HLD §9.1, spec 3.7). */
class CampaignCreationScenarioTest extends ScenarioTest {

    @Test
    void createReturnsCountsWithEveryDevicePending() throws Exception {
        fleet.setDevices(devices("gw", 5, "1.0.0"));

        postCampaign(campaignJson("2.0.0", "1.0.0"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/campaigns/")))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.fromVersion").value("1.0.0"))
                .andExpect(jsonPath("$.targetVersion").value("2.0.0"))
                .andExpect(jsonPath("$.deviceCount").value(5))
                .andExpect(jsonPath("$.excludedDeviceCount").value(0))
                .andExpect(jsonPath("$.countsByState.PENDING").value(5))
                .andExpect(jsonPath("$.countsByState.CANCELLED").value(0))
                .andExpect(jsonPath("$.inFlight").value(0))
                .andExpect(jsonPath("$.failureRate").value(0.0))
                .andExpect(jsonPath("$.reasons").isEmpty());
    }

    @Test
    void aSecondCampaignOverTheSameDevicesExcludesThemAll() throws Exception {
        fleet.setDevices(devices("gw", 3, "1.0.0"));
        String firstId = idOf(postCampaign(campaignJson("2.0.0", "1.0.0")));

        postCampaign(campaignJson("3.0.0", "1.0.0"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceCount").value(0))
                .andExpect(jsonPath("$.excludedDeviceCount").value(3))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        mvc.perform(get("/campaigns/{id}", firstId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.countsByState.PENDING").value(3));
    }

    @Test
    void aCampaignWithNoEligibleDevicesIsCompleted() throws Exception {
        fleet.setDevices(devices("gw", 2, "2.0.0"));

        postCampaign(campaignJson("3.0.0", "1.0.0"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.statusReason").value("No eligible devices on version 1.0.0"))
                .andExpect(jsonPath("$.deviceCount").value(0));
    }

    @Test
    void everyInvalidFieldIsReportedTogetherAndNothingIsCreated() throws Exception {
        String body = """
                {"targetVersion":" ","imageUrl":"",
                 "maxConcurrent":0,"failureThresholdPercent":101,"minSample":0}""";

        postCampaign(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.targetVersion").exists())
                .andExpect(jsonPath("$.imageUrl").exists())
                .andExpect(jsonPath("$.deviceFilter").exists())
                .andExpect(jsonPath("$.maxConcurrent").exists())
                .andExpect(jsonPath("$.failureThresholdPercent").exists())
                .andExpect(jsonPath("$.minSample").exists());

        assertThat(countCampaigns()).isZero();
    }

    @Test
    void anUnavailableFleetGives502AndCreatesNothing() throws Exception {
        fleet.setFailing(true);

        postCampaign(campaignJson("2.0.0", "1.0.0"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value(containsString("not be fetched")));

        assertThat(countCampaigns()).isZero();
    }
}



