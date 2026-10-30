package com.danishakh.att.devicemgmt.exception;
import com.danishakh.att.devicemgmt.model.CampaignStatus;
import java.util.UUID;
/** The campaign is not in a status that allows the requested move, for example resuming a HALTED campaign. Mapped to 409. */
public class CampaignMoveNotAllowedException extends RuntimeException {
    public CampaignMoveNotAllowedException(UUID campaignId, CampaignStatus current, String action,
            CampaignStatus required) {
        super("Cannot " + action + " campaign " + campaignId + ": its status is " + current
                + ", but it must be " + required);
    }
}
