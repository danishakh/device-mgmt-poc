package com.danishakh.att.devicemgmt.controller;

import com.danishakh.att.devicemgmt.dto.CampaignProgressResponse;
import com.danishakh.att.devicemgmt.dto.CampaignSummaryResponse;
import com.danishakh.att.devicemgmt.dto.CreateCampaignRequest;
import com.danishakh.att.devicemgmt.exception.CampaignCreationException;
import com.danishakh.att.devicemgmt.exception.CampaignMoveNotAllowedException;
import com.danishakh.att.devicemgmt.exception.CampaignNotFoundException;
import com.danishakh.att.devicemgmt.exception.FleetUnavailableException;
import com.danishakh.att.devicemgmt.service.CampaignService;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** The operator API (HLD §9.1). Each method catches the service's exceptions and maps them to status codes. */
@Slf4j
@RestController
@RequestMapping("/campaigns")
public class CampaignController {

    private final CampaignService campaignService;

    public CampaignController(CampaignService campaignService) {
        this.campaignService = campaignService;
    }

    /** Creates a campaign: 201 with a {@code Location} header, 502 if the fleet is down, 500 if the database fails. */
    @PostMapping
    public ResponseEntity<Object> createNewCampaign(@Valid @RequestBody CreateCampaignRequest request) {
        try {
            CampaignProgressResponse campaign = campaignService.createCampaign(request);
            URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                    .path("/{id}")
                    .buildAndExpand(campaign.getId())
                    .toUri();
            return ResponseEntity.created(location).body(campaign);
        } catch (FleetUnavailableException e) {
            log.warn("createNewCampaign() - Fleet unavailable: {}", e.getMessage());
            return error(HttpStatus.BAD_GATEWAY, "The device list could not be fetched, so the campaign was not created");
        } catch (CampaignCreationException e) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "The campaign could not be saved, and nothing was created");
        }
    }

    @GetMapping
    public List<CampaignSummaryResponse> listCampaigns() {
        return campaignService.listAllCampaigns();
    }

    /** 200 with the progress view, or 404 if no campaign has this id. */
    @GetMapping("/{campaignId}")
    public ResponseEntity<Object> getCampaign(@PathVariable UUID campaignId) {
        try {
            return ResponseEntity.ok(campaignService.getCampaignById(campaignId));
        } catch (CampaignNotFoundException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** 200 with the progress view, 404 if no campaign has this id, or 409 if it is not RUNNING. */
    @PostMapping("/{campaignId}/pause")
    public ResponseEntity<Object> pauseCampaign(@PathVariable UUID campaignId) {
        try {
            return ResponseEntity.ok(campaignService.pauseCampaign(campaignId));
        } catch (CampaignNotFoundException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (CampaignMoveNotAllowedException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** 200 with the progress view, 404 if no campaign has this id, or 409 if it is not PAUSED. */
    @PostMapping("/{campaignId}/resume")
    public ResponseEntity<Object> resumeCampaign(@PathVariable UUID campaignId) {
        try {
            return ResponseEntity.ok(campaignService.resumeCampaign(campaignId));
        } catch (CampaignNotFoundException e) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (CampaignMoveNotAllowedException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private static ResponseEntity<Object> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}

