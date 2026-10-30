package com.danishakh.att.devicemgmt.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.danishakh.att.devicemgmt.config.DeviceMgmtProperties;
import com.danishakh.att.devicemgmt.config.RestClientConfig;
import com.danishakh.att.devicemgmt.exception.FleetUnavailableException;
import com.danishakh.att.devicemgmt.model.FleetDevice;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Tests the simulator adapter against a throwaway JDK HTTP server. No Spring context and no
 * simulator: the server plays the simulator's part.
 */
class SimulatorFleetClientTest {

    private static final int POOL_SIZE = 3;
    private static final int MAX_RETRIES = 2;
    private static final String DEVICES_JSON =
            "[{\"deviceId\":\"d1\",\"firmwareVersion\":\"1.0.0\"},{\"deviceId\":\"d2\",\"firmwareVersion\":\"1.0.0\"}]";

    private HttpServer server;
    private ExecutorService serverThreads;
    private ExecutorService connectionRequestExecutor;
    private MeterRegistry meters;
    private SimulatorFleetClient fleetClient;

    @BeforeEach
    void startServerAndFleet() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.start();
        fleetClient = fleetClientFor(server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        serverThreads.shutdownNow();
        connectionRequestExecutor.shutdownNow();
    }

    private SimulatorFleetClient fleetClientFor(int port) {
        DeviceMgmtProperties properties = new DeviceMgmtProperties();
        properties.setSimulatorUrl("http://localhost:" + port);
        DeviceMgmtProperties.HttpClient http = properties.getHttpClient();
        http.setConnectTimeoutMs(500);
        http.setReadTimeoutMs(300);
        http.setMaxRetries(MAX_RETRIES);
        http.setPoolSize(POOL_SIZE);

        RestClientConfig config = new RestClientConfig();
        RestClient restClient = config.simulatorRestClient(RestClient.builder(), properties);
        connectionRequestExecutor = config.connectionRequestExecutor(properties);
        meters = new SimpleMeterRegistry();
        return new SimulatorFleetClient(restClient, new Retry(properties), connectionRequestExecutor, meters);
    }

    private void serve(String path, HttpHandler handler) {
        server.createContext(path, handler);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private double count(String endpoint, String status) {
        var counter = meters.find("dm_outbound_requests_total").tag("endpoint", endpoint).tag("status", status)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition not met within 5 s").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    @Test
    void listsDevicesOnTheRequestedVersionAndCountsTheCall() {
        AtomicInteger requests = new AtomicInteger();
        serve("/devices", exchange -> {
            requests.incrementAndGet();
            assertThat(exchange.getRequestURI().getQuery()).isEqualTo("version=1.0.0");
            reply(exchange, 200, DEVICES_JSON);
        });

        List<FleetDevice> devices = fleetClient.listDevices("1.0.0");

        assertThat(devices).extracting(FleetDevice::deviceId).containsExactly("d1", "d2");
        assertThat(devices).extracting(FleetDevice::firmwareVersion).containsOnly("1.0.0");
        assertThat(requests).hasValue(1);
        assertThat(count("list_devices", "200")).isEqualTo(1);
    }

    @Test
    void retriesA5xxAndSucceeds() {
        AtomicInteger requests = new AtomicInteger();
        serve("/devices", exchange -> {
            if (requests.incrementAndGet() <= 2) {
                reply(exchange, 503, "");
            } else {
                reply(exchange, 200, DEVICES_JSON);
            }
        });

        assertThat(fleetClient.listDevices("1.0.0")).hasSize(2);

        assertThat(requests).hasValue(3);
        assertThat(count("list_devices", "503")).isEqualTo(2);
        assertThat(count("list_devices", "200")).isEqualTo(1);
    }

    @Test
    void givesUpAfterTheRetriesAreUsedUp() {
        AtomicInteger requests = new AtomicInteger();
        serve("/devices", exchange -> {
            requests.incrementAndGet();
            reply(exchange, 500, "");
        });

        assertThatThrownBy(() -> fleetClient.listDevices("1.0.0")).isInstanceOf(FleetUnavailableException.class);

        assertThat(requests).hasValue(1 + MAX_RETRIES);
        assertThat(count("list_devices", "500")).isEqualTo(1 + MAX_RETRIES);
    }

    @Test
    void doesNotRetryA4xx() {
        AtomicInteger requests = new AtomicInteger();
        serve("/devices", exchange -> {
            requests.incrementAndGet();
            reply(exchange, 404, "");
        });

        assertThatThrownBy(() -> fleetClient.listDevices("1.0.0")).isInstanceOf(FleetUnavailableException.class);

        assertThat(requests).hasValue(1);
        assertThat(count("list_devices", "404")).isEqualTo(1);
    }

    @Test
    void retriesWhenTheServerIsTooSlowAndCountsIoErrors() {
        AtomicInteger requests = new AtomicInteger();
        serve("/devices", exchange -> {
            requests.incrementAndGet();
            try {
                Thread.sleep(1500); // longer than the 300 ms read timeout
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });

        assertThatThrownBy(() -> fleetClient.listDevices("1.0.0")).isInstanceOf(FleetUnavailableException.class);

        assertThat(requests).hasValue(1 + MAX_RETRIES);
        assertThat(count("list_devices", "IO_ERROR")).isEqualTo(1 + MAX_RETRIES);
    }

    @Test
    void retriesATransportErrorWhenNothingIsListening() throws IOException {
        int closedPort;
        try (var socket = new java.net.ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        SimulatorFleetClient unreachable = fleetClientFor(closedPort);

        assertThatThrownBy(() -> unreachable.listDevices("1.0.0")).isInstanceOf(FleetUnavailableException.class);

        assertThat(count("list_devices", "IO_ERROR")).isEqualTo(1 + MAX_RETRIES);
    }

    @Test
    void sendsAConnectionRequestInTheBackgroundAndCountsIt() throws InterruptedException {
        CountDownLatch received = new CountDownLatch(1);
        serve("/devices/d1/connection-request", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            received.countDown();
            reply(exchange, 200, "");
        });

        fleetClient.sendConnectionRequest("d1");

        assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
        eventually(() -> count("connection_request", "200") == 1);
    }

    @Test
    void aFailingConnectionRequestNeverThrowsToTheCaller() throws InterruptedException {
        serve("/devices/d1/connection-request", exchange -> reply(exchange, 500, ""));

        fleetClient.sendConnectionRequest("d1");

        eventually(() -> count("connection_request", "500") == 1 + MAX_RETRIES);
    }

    @Test
    void neverHasMoreThanPoolSizeConnectionRequestsInFlight() throws InterruptedException {
        int total = 12;
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(total);
        serve("/devices", exchange -> {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            reply(exchange, 200, "");
            done.countDown();
        });

        for (int i = 0; i < total; i++) {
            fleetClient.sendConnectionRequest("d" + i);
        }

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(maxInFlight.get()).isLessThanOrEqualTo(POOL_SIZE);
    }
}

