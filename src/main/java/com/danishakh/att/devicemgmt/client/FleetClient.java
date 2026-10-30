package com.danishakh.att.devicemgmt.client;

import com.danishakh.att.devicemgmt.exception.FleetUnavailableException;
import com.danishakh.att.devicemgmt.model.FleetDevice;
import java.util.List;

/**
 * The controller's only way to reach the device fleet. Implemented by an HTTP adapter for the
 * simulator and by an in-memory adapter in tests.
 */
public interface FleetClient {

    /**
     * Lists the devices currently on the given firmware version.
     *
     * @throws FleetUnavailableException if the fleet cannot be reached after retries
     */
    List<FleetDevice> listDevices(String firmwareVersion);

    /**
     * Asks a device to call in. Fire-and-forget: returns at once and never throws. A lost request
     * is covered by the NOTIFIED deadline.
     */
    void sendConnectionRequest(String deviceId);
}

