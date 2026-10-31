package com.danishakh.att.devicemgmt.dto;

import com.danishakh.att.devicemgmt.statemachine.InformEvent;
import java.time.Instant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** The body of {@code POST /devices/{deviceId}/inform} (HLD 9.2). */
@Getter
@Setter
public class InformRequest {

    /** The device sending the inform. Must match the device id in the path. */
    @NotBlank
    private String deviceId;

    @NotNull
    private InformEvent event;

    @NotBlank
    private String firmwareVersion;

    /** Present only when the device reports the result of a Download. */
    @Valid
    private TransferComplete transferComplete;

    @Getter
    @Setter
    public static class TransferComplete {
        @NotBlank
        private String commandKey;

        @NotNull
        private Integer faultCode;

        /** When the device started the download, as reported by the device. Optional. */
        private Instant startTime;

        /** When the device finished the download, as reported by the device. Optional. */
        private Instant completeTime;
    }
}

