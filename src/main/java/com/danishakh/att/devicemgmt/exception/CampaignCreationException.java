package com.danishakh.att.devicemgmt.exception;
/** The database failed while a campaign was being created. Nothing was saved: the transaction rolled back. */
public class CampaignCreationException extends RuntimeException {
    public CampaignCreationException(String message, Throwable cause) {
        super(message, cause);
    }
}
