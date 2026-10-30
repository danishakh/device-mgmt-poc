package com.danishakh.att.devicemgmt.model;
/** Lifecycle of a campaign (HLD §6). */
public enum CampaignStatus {
    /** Starting devices within its concurrency limit. */
    RUNNING,
    /** No new devices start. Devices already in flight carry on. */
    PAUSED,
    /** Terminal: the failure rate passed the threshold. */
    HALTED,
    /** Terminal: no device upgrade is open any more. */
    COMPLETED
}
