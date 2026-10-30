package com.danishakh.att.devicemgmt.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.pool.PoolStats;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The {@link RestClient} used to call the simulator, and the thread pool that sends connection
 * requests. The client is built from the auto-configured builder so Spring records
 * {@code http.client.requests}, on an Apache HttpClient 5 connection pool.
 */
@Slf4j
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient simulatorRestClient(RestClient.Builder builder, DeviceMgmtProperties properties) {
        DeviceMgmtProperties.HttpClient http = properties.getHttpClient();
        Timeout connectTimeout = Timeout.ofMilliseconds(http.getConnectTimeoutMs());
        // The pool is sized to the connection-request pool, so every pooled thread can hold a connection.
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(http.getPoolSize())
                .setMaxConnPerRoute(http.getPoolSize())
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(connectTimeout).build())
                .build();
        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(connectTimeout)
                        .setResponseTimeout(Timeout.ofMilliseconds(http.getReadTimeoutMs()))
                        .build())
                .build();

        PoolStats totalStats = connectionManager.getTotalStats();
        log.info("Leased (Active Outbound Connections): {}", totalStats.getLeased());
        log.info("Pending (Requests waiting for a route): {}", totalStats.getPending());
        log.info("Available (Idle connections): {}", totalStats.getAvailable());

        return builder
                .baseUrl(properties.getSimulatorUrl())
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .build();
    }

    /**
     * A pool that does nothing but send connection requests, so a slow simulator can never occupy
     * HTTP request threads or the scheduler thread. The queue is unbounded on purpose: only NOTIFIED
     * upgrades are queued, so its length is already limited by the campaigns' concurrency limits.
     */
    @Bean
    public ExecutorService connectionRequestExecutor(DeviceMgmtProperties properties) {
        return Executors.newFixedThreadPool(properties.getHttpClient().getPoolSize(),
                Thread.ofPlatform().name("connection-request-thread-", 1).factory());
    }
}
