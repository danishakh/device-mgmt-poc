package com.danishakh.att.devicemgmt.statemachine;

/** The event a device reports in an inform. */
public enum InformEvent {
    BOOT,
    CONNECTION_REQUEST,
    PERIODIC,
    /** The device is reporting a TransferComplete. See HLD §7.2: the payload is what counts. */
    TRANSFER_COMPLETE
}

