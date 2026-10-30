package com.danishakh.att.devicemgmt.exception;

/** The fleet could not be reached or kept failing after retries. Mapped to 502 for operators. */
public class FleetUnavailableException extends RuntimeException {

    public FleetUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

