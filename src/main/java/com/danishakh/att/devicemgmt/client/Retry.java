package com.danishakh.att.devicemgmt.client;

import com.danishakh.att.devicemgmt.config.DeviceMgmtProperties;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Wraps every outbound call to the simulator. Makes up to {@code max-retries} extra attempts, only
 * on transport errors and 5xx. A 4xx is the caller's mistake, so it is never retried. There is no
 * wait between attempts.
 */
@Component
public class Retry {

    private final int maxRetries;

    public Retry(DeviceMgmtProperties properties) {
        this.maxRetries = properties.getHttpClient().getMaxRetries();
    }

    /** Runs the call, rethrowing the last failure once the retries are used up. */
    public <T> T call(Supplier<T> call) {
        int attempt = 0;
        while (true) {
            try {
                return call.get();
            } catch (ResourceAccessException | HttpServerErrorException e) {
                if (attempt++ >= maxRetries) {
                    throw e;
                }
            }
        }
    }
}

