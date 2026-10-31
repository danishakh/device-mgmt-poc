package com.danishakh.att.devicemgmt.dto;

import com.danishakh.att.devicemgmt.statemachine.DeviceAckStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** The body of {@code POST /devices/{deviceId}/command-response} (HLD 9.2). */
@Getter
@Setter
public class CommandResponseRequest {

    @NotBlank
    private String commandKey;

    @NotNull
    private DeviceAckStatus status;

    /** Why the device rejected the command, for example {@code BUSY}. Optional. */
    private String reason;
}

