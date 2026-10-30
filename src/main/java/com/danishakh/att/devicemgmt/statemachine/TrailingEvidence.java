package com.danishakh.att.devicemgmt.statemachine;

/**
 * Kind of evidence that arrived after an outcome was already known and was recorded without
 * changing that outcome (HLD §7.2). Stored in {@code device_upgrade.trailing_evidence}.
 */
public enum TrailingEvidence {
    /** A matching TransferComplete arrived after the row finished on other evidence. */
    FAULT,
    /** A device that timed out after being given a Download later reported the target version. */
    REPORTED_TARGET_VERSION
}

