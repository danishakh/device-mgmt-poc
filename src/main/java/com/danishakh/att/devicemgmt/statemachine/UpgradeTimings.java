package com.danishakh.att.devicemgmt.statemachine;

import java.time.Duration;

/**
 * The timing and retry limits the state machine needs, sourced from {@code devicemgmt.timeouts.*}
 * in {@code application.yaml}.
 *
 * <p>Written as a plain final class rather than a record, with the same accessor method names
 * a record would generate.
 */
public final class UpgradeTimings {
    private final Duration crWait;
    private final int maxCr;
    private final Duration ackWait;
    private final int maxDl;
    private final Duration completionWait;

    public UpgradeTimings(Duration crWait, int maxCr, Duration ackWait, int maxDl, Duration completionWait) {
        this.crWait = crWait;
        this.maxCr = maxCr;
        this.ackWait = ackWait;
        this.maxDl = maxDl;
        this.completionWait = completionWait;
    }

    /** How long to wait for a device to call in after a connection request. */
    public Duration crWait() {
        return crWait;
    }

    /** How many connection requests to send before giving up (NO_CALLBACK). */
    public int maxCr() {
        return maxCr;
    }

    /** How long to wait for a command-response after a Download is sent. */
    public Duration ackWait() {
        return ackWait;
    }

    /** How many times to re-send the same Download before giving up. */
    public int maxDl() {
        return maxDl;
    }

    /** How long to wait for a TransferComplete or version evidence after ACCEPTED. */
    public Duration completionWait() {
        return completionWait;
    }
}
