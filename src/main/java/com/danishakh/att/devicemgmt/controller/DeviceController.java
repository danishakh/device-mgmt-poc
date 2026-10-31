package com.danishakh.att.devicemgmt.controller;

import com.danishakh.att.devicemgmt.dto.CommandResponseRequest;
import com.danishakh.att.devicemgmt.dto.InformRequest;
import com.danishakh.att.devicemgmt.dto.InformResponse;
import com.danishakh.att.devicemgmt.service.DeviceCallbackService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The device API (HLD §9.2). A well-formed callback is answered 200, whatever the service decided. Only a
 * malformed body, a mismatched device id (400) or an unavailable database (503) gets another status.
 */
@Slf4j
@RestController
@RequestMapping("/devices/{deviceId}")
public class DeviceController {

    private final DeviceCallbackService deviceCallbackService;

    public DeviceController(DeviceCallbackService deviceCallbackService) {
        this.deviceCallbackService = deviceCallbackService;
    }

    /** 200 with no commands or one Download; 400 if the body names another device; 503 if the database fails. */
    @PostMapping("/inform")
    public ResponseEntity<Object> inform(@PathVariable String deviceId, @Valid @RequestBody InformRequest request) {
        if (!deviceId.equals(request.getDeviceId())) {
            return error(HttpStatus.BAD_REQUEST, "The deviceId in the body does not match the path");
        }
        try {
            InformResponse response = deviceCallbackService.handleInform(deviceId, request);
            return ResponseEntity.ok(response);
        } catch (DataAccessException e) {
            log.error("inform() - Database unavailable for device {}", deviceId, e);
            return error(HttpStatus.SERVICE_UNAVAILABLE, "The database is unavailable; the device should retry");
        }
    }

    /** 200 with an empty body; 400 for a malformed body; 503 if the database fails. */
    @PostMapping("/command-response")
    public ResponseEntity<Object> commandResponse(@PathVariable String deviceId, @Valid @RequestBody CommandResponseRequest request) {
        try {
            deviceCallbackService.handleDownloadCommandAcknowledgement(deviceId, request);
            return ResponseEntity.ok().build();
        } catch (DataAccessException e) {
            log.error("commandResponse() - Database unavailable for device {}", deviceId, e);
            return error(HttpStatus.SERVICE_UNAVAILABLE, "The database is unavailable; the device should retry");
        }
    }

    private static ResponseEntity<Object> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}

