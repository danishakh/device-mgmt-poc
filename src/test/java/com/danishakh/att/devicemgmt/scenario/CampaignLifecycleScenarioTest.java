package com.danishakh.att.devicemgmt.scenario;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Scenarios for pausing, resuming and looking up campaigns (HLD §9.1, spec 3.7). */
class CampaignLifecycleScenarioTest extends ScenarioTest {

    @Test
    void pauseAndResumeMoveTheCampaignAndRejectAnInvalidMoveWith409() throws Exception {
        fleet.setDevices(devices("gw", 3, "1.0.0"));
        String id = idOf(postCampaign(campaignJson("2.0.0", "1.0.0")));

        mvc.perform(post("/campaigns/{id}/pause", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));

        mvc.perform(post("/campaigns/{id}/pause", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("its status is PAUSED, but it must be RUNNING")));

        mvc.perform(post("/campaigns/{id}/resume", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));

        mvc.perform(post("/campaigns/{id}/resume", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("its status is RUNNING, but it must be PAUSED")));
    }

    @Test
    void anUnknownCampaignGives404() throws Exception {
        String unknown = UUID.randomUUID().toString();

        mvc.perform(get("/campaigns/{id}", unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(containsString("not found")));

        mvc.perform(post("/campaigns/{id}/pause", unknown))
                .andExpect(status().isNotFound());

        mvc.perform(post("/campaigns/{id}/resume", unknown))
                .andExpect(status().isNotFound());
    }
}


