package com.danishakh.att.devicemgmt.exception;
import java.util.UUID;
/** No campaign has the requested id. Mapped to 404 for operators. */
public class CampaignNotFoundException extends RuntimeException {
    public CampaignNotFoundException(UUID campaignId) {
        super("Campaign " + campaignId + " not found");
    }
}
