package com.danishakh.att.devicemgmt.statemachine;

/** How the state machine classified one event, used by the caller to label callback metrics. */
public enum CallbackResult {
    /** The event changed the row in the usual way. */
    APPLIED,
    /** A real outcome replaced a TIMED_OUT row. */
    LATE,
    /** Evidence stored on a finished row without changing its outcome. */
    TRAILING,
    /** A REJECTED acknowledgement with reason BUSY; the row is unchanged. */
    BUSY_REJECTION,
    /** A repeat of something already applied; no effect. */
    DUPLICATE,
    /** The event does not apply to the row's current state; no effect. */
    IGNORED
}

