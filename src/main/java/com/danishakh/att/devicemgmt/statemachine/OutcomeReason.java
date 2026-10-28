package com.danishakh.att.devicemgmt.statemachine;

/**
 * Why an upgrade finished the way it did. FAULT is stored as FAULT_&lt;code&gt; using the row's fault code.
 */
public enum OutcomeReason {
    /** NOTIFIED timed out: the device never called in after any connection request attempt. */
    NO_CALLBACK,
    /** DOWNLOAD_SENT timed out: the device never acknowledged the Download. */
    NO_ACK,
    /** ACCEPTED timed out: no TransferComplete or version evidence arrived. */
    NO_COMPLETION,
    /** The device's command-response rejected the Download. */
    REJECTED,
    /** A TransferComplete with fault code 0 finished the row. */
    TRANSFER_COMPLETE,
    /** A TransferComplete with a non-zero fault code finished the row. */
    FAULT,
    /** No TransferComplete arrived, but the device reported the target version. */
    INFERRED_FROM_VERSION,
    /** The device rebooted and came back on its from-version, with no TransferComplete. */
    ROLLED_BACK,
    /** The device rebooted on a version that is neither the from-version nor the target. */
    UNEXPECTED_VERSION,
    /** The campaign halted before the device was given a Download (state CANCELLED). */
    CAMPAIGN_HALTED
}


