package com.danishakh.att.devicemgmt;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
/**
 * Checks every devicemgmt.* value in application.yaml. A typo in a key is a test failure,
 * instead of a silent fallback to a default. Durations are milliseconds (the Ms suffix).
 */
@SpringBootTest
class ConfigBindingTest {
@Value("${devicemgmt.simulator-url}")
String simulatorUrl;
@Value("${devicemgmt.scheduler.tick-interval-ms}")
long tickIntervalMs;
@Value("${devicemgmt.scheduler.progress-log-interval-ms}")
long progressLogIntervalMs;
@Value("${devicemgmt.campaign.default-max-concurrent}")
int defaultMaxConcurrent;
@Value("${devicemgmt.campaign.default-min-sample}")
int defaultMinSample;
@Value("${devicemgmt.timeouts.cr-wait-ms}")
long crWaitMs;
@Value("${devicemgmt.timeouts.max-cr}")
int maxCr;
@Value("${devicemgmt.timeouts.ack-wait-ms}")
long ackWaitMs;
@Value("${devicemgmt.timeouts.max-dl}")
int maxDl;
@Value("${devicemgmt.timeouts.completion-wait-ms}")
long completionWaitMs;
@Value("${devicemgmt.http-client.connect-timeout-ms}")
long connectTimeoutMs;
@Value("${devicemgmt.http-client.read-timeout-ms}")
long readTimeoutMs;
@Value("${devicemgmt.http-client.max-retries}")
int maxRetries;
@Value("${devicemgmt.http-client.pool-size}")
int poolSize;
@Test
void bindsEveryValueFromApplicationYaml() {
assertThat(simulatorUrl).isEqualTo("http://localhost:9000");
assertThat(tickIntervalMs).isEqualTo(1000);
assertThat(progressLogIntervalMs).isEqualTo(10000);
assertThat(defaultMaxConcurrent).isEqualTo(100);
assertThat(defaultMinSample).isEqualTo(50);
assertThat(crWaitMs).isEqualTo(30000);
assertThat(maxCr).isEqualTo(2);
assertThat(ackWaitMs).isEqualTo(30000);
assertThat(maxDl).isEqualTo(2);
assertThat(completionWaitMs).isEqualTo(300000);
assertThat(connectTimeoutMs).isEqualTo(2000);
assertThat(readTimeoutMs).isEqualTo(5000);
assertThat(maxRetries).isEqualTo(2);
assertThat(poolSize).isEqualTo(32);
}
}
