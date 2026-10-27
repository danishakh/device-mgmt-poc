package com.danishakh.att.devicemgmt.statemachine;

/** The device's answer to a Download command. */
public enum AckStatus {
    /** The device will attempt the Download. */
    ACCEPTED,
    /** The device refused the Download. */
    REJECTED
}


