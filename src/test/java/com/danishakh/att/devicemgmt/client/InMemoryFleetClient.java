package com.danishakh.att.devicemgmt.client;

import com.danishakh.att.devicemgmt.exception.FleetUnavailableException;
import com.danishakh.att.devicemgmt.model.FleetDevice;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link FleetClient} for scenario tests: a configurable device list, a record of every connection
 * request, and an optional failure mode. No network, no threads.
 */
public class InMemoryFleetClient implements FleetClient {

    private final List<FleetDevice> devices = new CopyOnWriteArrayList<>();
    private final List<String> connectionRequests = new CopyOnWriteArrayList<>();
    private volatile boolean failing;

    /** Replaces the devices the fleet reports. */
    public void setDevices(List<FleetDevice> newDevices) {
        devices.clear();
        devices.addAll(newDevices);
    }

    /** While true, {@link #listDevices} throws {@link FleetUnavailableException}. */
    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    /** Every device id that was sent a connection request, in order. */
    public List<String> connectionRequests() {
        return List.copyOf(connectionRequests);
    }

    /** Back to an empty fleet that works, with no recorded requests. */
    public void reset() {
        devices.clear();
        connectionRequests.clear();
        failing = false;
    }

    @Override
    public List<FleetDevice> listDevices(String firmwareVersion) {
        if (failing) {
            throw new FleetUnavailableException("In-memory fleet set to fail", null);
        }
        return devices.stream().filter(d -> d.firmwareVersion().equals(firmwareVersion)).toList();
    }

    @Override
    public void sendConnectionRequest(String deviceId) {
        connectionRequests.add(deviceId);
    }
}

