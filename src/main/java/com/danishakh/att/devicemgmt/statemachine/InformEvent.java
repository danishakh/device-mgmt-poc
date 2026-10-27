package com.danishakh.att.devicemgmt.statemachine;

/** The event a device reports in an inform. */
public enum InformEvent {
    /** The device rebooted. */
    BOOT,
    /** The device is calling in response to a connection request. */
    CONNECTION_REQUEST,
    /** A routine, unprompted check-in. */
    PERIODIC,
    /** The device is reporting a TransferComplete. See decision D12: the payload is what counts. */
    TRANSFER_COMPLETE
}

