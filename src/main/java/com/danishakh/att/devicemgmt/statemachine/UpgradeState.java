package com.danishakh.att.devicemgmt.statemachine;

/** Lifecycle of one device upgrade (IMPLEMENTATION-PLAN.md §2.3). */
public enum UpgradeState {
    /** Snapshotted into the campaign, waiting for the scheduler to give it a slot. */
    PENDING,
    /** A connection request has been sent; waiting for the device to call in. */
    NOTIFIED,
    /** A Download has been returned to the device; waiting for its command-response. */
    DOWNLOAD_SENT,
    /** The device accepted the Download; waiting for a TransferComplete or version evidence. */
    ACCEPTED,
    /** Terminal: the upgrade worked. */
    SUCCEEDED,
    /** Terminal: the upgrade did not work. */
    FAILED,
    /** Terminal: no evidence arrived before the deadline. */
    TIMED_OUT
}


