package com.danishakh.att.devicemgmt.config;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
/** Plain unit tests: no Spring context, so every rule is checked in milliseconds. */
class ConfigValidatorTest {
/** Arguments in the order of the constructor. Durations are in ms. */
private static ConfigValidator build(String url, long tickMs, long progressMs,
int maxConcurrent, int minSample,
long crWaitMs, int maxCr, long ackWaitMs, int maxDl, long completionWaitMs,
long connectTimeoutMs, long readTimeoutMs, int maxRetries, int poolSize) {
return new ConfigValidator(url, tickMs, progressMs, maxConcurrent, minSample,
crWaitMs, maxCr, ackWaitMs, maxDl, completionWaitMs,
connectTimeoutMs, readTimeoutMs, maxRetries, poolSize);
}
private static ConfigValidator valid() {
return build("http://localhost:9000", 1000, 10000,
100, 50,
30000, 2, 30000, 2, 300000,
2000, 5000, 2, 32);
}
@Test
void acceptsTheDefaults() {
assertThatCode(() -> valid()).doesNotThrowAnyException();
}
@Test
void rejectsANonHttpSimulatorUrl() {
assertThatThrownBy(() -> build("ftp://sim:9000", 1000, 10000,
100, 50, 30000, 2, 30000, 2, 300000, 2000, 5000, 2, 32))
.isInstanceOf(IllegalStateException.class)
.hasMessageContaining("devicemgmt.simulator-url");
}
@Test
void rejectsAMalformedSimulatorUrl() {
assertThatThrownBy(() -> build("http://bad host", 1000, 10000,
100, 50, 30000, 2, 30000, 2, 300000, 2000, 5000, 2, 32))
.isInstanceOf(IllegalStateException.class)
.hasMessageContaining("devicemgmt.simulator-url");
}
@Test
void rejectsZeroOrNegativeMilliseconds() {
assertThatThrownBy(() -> build("http://localhost:9000", 0, 10000,
100, 50, 30000, 2, 30000, 2, 300000, 2000, 5000, 2, 32))
.isInstanceOf(IllegalStateException.class)
.hasMessageContaining("devicemgmt.scheduler.tick-interval-ms");
assertThatThrownBy(() -> build("http://localhost:9000", 1000, 10000,
100, 50, -1, 2, 30000, 2, 300000, 2000, 5000, 2, 32))
.isInstanceOf(IllegalStateException.class)
.hasMessageContaining("devicemgmt.timeouts.cr-wait-ms");
}
@Test
void rejectsCountsBelowTheirMinimum() {
assertThatThrownBy(() -> build("http://localhost:9000", 1000, 10000,
0, 50, 30000, 0, 30000, 0, 300000, 2000, 5000, -1, 0))
.isInstanceOf(IllegalStateException.class)
.hasMessageContaining("devicemgmt.campaign.default-max-concurrent")
.hasMessageContaining("devicemgmt.timeouts.max-cr")
.hasMessageContaining("devicemgmt.timeouts.max-dl")
.hasMessageContaining("devicemgmt.http-client.max-retries")
.hasMessageContaining("devicemgmt.http-client.pool-size");
}
@Test
void allowsZeroMinSampleAndZeroRetries() {
assertThatCode(() -> build("http://localhost:9000", 1000, 10000,
100, 0, 30000, 2, 30000, 2, 300000, 2000, 5000, 0, 32))
.doesNotThrowAnyException();
}
}
