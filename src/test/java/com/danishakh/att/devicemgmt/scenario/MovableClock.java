package com.danishakh.att.devicemgmt.scenario;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A {@link Clock} that only moves when a test says so. Replaces the system clock in scenario tests. */
public final class MovableClock extends Clock {

    private volatile Instant now;

    public MovableClock(Instant start) {
        this.now = start;
    }

    public void set(Instant instant) {
        this.now = instant;
    }

    public void advance(Duration duration) {
        this.now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("The scenario clock is always UTC");
    }

    @Override
    public Instant instant() {
        return now;
    }
}

