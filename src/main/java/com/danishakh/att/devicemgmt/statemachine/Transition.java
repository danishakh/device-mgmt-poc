package com.danishakh.att.devicemgmt.statemachine;

/**
 * Result of applying one event to one row.
 *
 * <p>Written as a plain final class rather than a record: the four fields are exposed through
 * explicit accessor methods with the same names a record would generate, so callers do not
 * need to change.
 */
public final class Transition {
    private final UpgradeRow next;
    private final boolean changed;
    private final boolean sendConnectionRequest;
    private final boolean issueDownload;

    public Transition(UpgradeRow next, boolean changed, boolean sendConnectionRequest, boolean issueDownload) {
        this.next = next;
        this.changed = changed;
        this.sendConnectionRequest = sendConnectionRequest;
        this.issueDownload = issueDownload;
    }

    /** The row the caller should write to the database. */
    public UpgradeRow next() {
        return next;
    }

    /**
     * False for duplicates and for events that do not apply to the row's current state.
     * The caller can skip the write entirely when this is false.
     */
    public boolean changed() {
        return changed;
    }

    /** True means: send a connection request to the device after the write commits. */
    public boolean sendConnectionRequest() {
        return sendConnectionRequest;
    }

    /**
     * True means: this inform's HTTP response should carry a Download command. If
     * {@code next.getCommandKey()} is null, the caller must generate a new key and store it
     * with the row, because the state machine itself never generates keys.
     */
    public boolean issueDownload() {
        return issueDownload;
    }

    /** A transition that changes nothing: the row is returned unmodified. */
    static Transition unchanged(UpgradeRow row) {
        return new Transition(row, false, false, false);
    }
}
