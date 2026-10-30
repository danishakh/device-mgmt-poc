package com.danishakh.att.devicemgmt.client;
import com.danishakh.att.devicemgmt.exception.FleetUnavailableException;
import com.danishakh.att.devicemgmt.model.FleetDevice;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
/**
 * {@link FleetClient} backed by the simulator's HTTP API. Builds the requests and parses the replies;
 * the client and the connection-request pool are configured in {@code RestClientConfig}.
 */
@Slf4j
@Component
public class SimulatorFleetClient implements FleetClient {
    private static final String LIST_DEVICES = "list_devices";
    private static final String CONNECTION_REQUEST = "connection_request";
    private static final String IO_ERROR = "IO_ERROR";
    private final RestClient restClient;
    private final Retry retry;
    private final ExecutorService connectionRequestExecutor;
    private final MeterRegistry meterRegistry;

    public SimulatorFleetClient(RestClient simulatorRestClient, Retry retry,
            @Qualifier("connectionRequestExecutor") ExecutorService connectionRequestExecutor,
            MeterRegistry meterRegistry) {
        this.restClient = simulatorRestClient;
        this.retry = retry;
        this.connectionRequestExecutor = connectionRequestExecutor;
        this.meterRegistry = meterRegistry;
    }

    /** Runs on the caller's thread: the operator is waiting for the answer. */
    @Override
    public List<FleetDevice> listDevices(String firmwareVersion) {
        try {
            List<DeviceDto> deviceDtoList = retry.call(() -> counted(LIST_DEVICES, () -> restClient.get()
                    .uri(uri -> uri.path("/devices").queryParam("version", firmwareVersion).build())
                    .retrieve()
                    .toEntity(new ParameterizedTypeReference<List<DeviceDto>>() {
                    }))).getBody();

            return deviceDtoList.stream().map(d -> new FleetDevice(d.getDeviceId(), d.getFirmwareVersion())).toList();
        } catch (RestClientException e) {
            throw new FleetUnavailableException("Could not list devices on version " + firmwareVersion, e);
        }
    }

    /** Hands the call to the connection-request pool and returns at once. */
    @Override
    public void sendConnectionRequest(String deviceId) {
        connectionRequestExecutor.execute(() -> postConnectionRequest(deviceId));
    }
    /** One call through {@link Retry}. Logs the final outcome and never throws, so a pooled thread survives. */
    private void postConnectionRequest(String deviceId) {
        try {
            retry.call(() -> counted(CONNECTION_REQUEST, () -> restClient.post()
                    .uri("/devices/{id}/connection-request", deviceId)
                    .retrieve()
                    .toBodilessEntity()));
            log.debug("Connection request sent to device {}", deviceId);
        } catch (RuntimeException e) {
            log.warn("Connection request to device {} failed: {}", deviceId, e.toString());
        }
    }

    /**
     * Counts one attempt in {@code dm_outbound_requests_total}, by endpoint and status: the HTTP
     * status code, or IO_ERROR when there is no status code. Each retry is its own attempt.
     */
    private <T> ResponseEntity<T> counted(String endpoint, Supplier<ResponseEntity<T>> call) {
        try {
            ResponseEntity<T> response = call.get();
            count(endpoint, String.valueOf(response.getStatusCode().value()));
            return response;
        } catch (RestClientResponseException e) {
            count(endpoint, String.valueOf(e.getStatusCode().value()));
            throw e;
        } catch (RestClientException e) {
            count(endpoint, IO_ERROR);
            throw e;
        }
    }

    private void count(String endpoint, String status) {
        meterRegistry.counter("dm_outbound_requests_total", "endpoint", endpoint, "status", status).increment();
    }

    /** The simulator's JSON for one device. */
    @Getter
    @Setter
    private static class DeviceDto {
        private String deviceId;
        private String firmwareVersion;
    }
}
