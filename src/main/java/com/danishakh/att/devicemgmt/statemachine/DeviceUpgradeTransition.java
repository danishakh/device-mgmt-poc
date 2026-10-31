package com.danishakh.att.devicemgmt.statemachine;
/**
 * Result of applying one event to one row.
 *
 * <p>Written as a plain final class rather than a record: the fields are exposed through
 * explicit accessor methods with the same names a record would generate, so callers do not
 * need to change.
 */
public final class DeviceUpgradeTransition {
    private final DeviceUpgradeRow previous;
    private final DeviceUpgradeRow next;
    private final boolean changed;
    private final boolean sendConnectionRequest;
    private final boolean issueDownload;
    private final DeviceCallbackResult result;
    public DeviceUpgradeTransition(DeviceUpgradeRow previous, DeviceUpgradeRow next, boolean changed,
            boolean sendConnectionRequest, boolean issueDownload, DeviceCallbackResult result) {
        this.previous = previous;
        this.next = next;
        this.changed = changed;
        this.sendConnectionRequest = sendConnectionRequest;
        this.issueDownload = issueDownload;
        this.result = result;
    }
    /** A normal change: the event was applied in the usual way. */
    static DeviceUpgradeTransition applied(DeviceUpgradeRow previous, DeviceUpgradeRow next, boolean sendConnectionRequest,
            boolean issueDownload) {
        return new DeviceUpgradeTransition(previous, next, true, sendConnectionRequest, issueDownload, DeviceCallbackResult.APPLIED);
    }
    /** The row is returned unmodified, classified as DUPLICATE, IGNORED or BUSY_REJECTION. */
    public static DeviceUpgradeTransition unchanged(DeviceUpgradeRow row, DeviceCallbackResult result) {
        return new DeviceUpgradeTransition(row, row, false, false, false, result);
    }
    /** The row as it was before the event. Equal to {@link #next()} when nothing changed. */
    public DeviceUpgradeRow previous() {
        return previous;
    }
    /** The row the caller should write to the database. */
    public DeviceUpgradeRow next() {
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
     * True means: this inform's HTTP response should carry a Download command, using the key
     * stored on {@code next}.
     */
    public boolean issueDownload() {
        return issueDownload;
    }
    /** How this event is classified, for metrics labelling. Set on every path. */
    public DeviceCallbackResult result() {
        return result;
    }
}

