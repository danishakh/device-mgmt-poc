package com.danishakh.att.devicemgmt.model;

/** A device as the fleet reports it: its id and current firmware version. */
public final class FleetDevice {
    private final String deviceId;
    private final String firmwareVersion;

    public FleetDevice(String deviceId, String firmwareVersion) {
        this.deviceId = deviceId;
        this.firmwareVersion = firmwareVersion;
    }

    public String deviceId() {
        return deviceId;
    }

    public String firmwareVersion() {
        return firmwareVersion;
    }
}

