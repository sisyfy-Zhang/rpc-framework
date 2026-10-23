package io.github.sisyfyzhang.steadyrpc.core.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.registry.RegistryConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RpcClientConfig 的 outlierDetection：缺省开启、builder 可换、null 被拒，1.0.0 形式的记录构造器仍可用（取缺省）。 */
class OutlierDetectionClientConfigTest {

	private static final RegistryConfig REGISTRY = RegistryConfig.of("memory", "ns");

	@Test
	void defaultIsOnWithDefaults() {
		RpcClientConfig config = RpcClientConfig.builder().registry(REGISTRY).build();
		assertTrue(config.outlierDetection().enabled());
		assertSame(OutlierDetectionConfig.defaults(), config.outlierDetection());
	}

	@Test
	void builderReplacesIt() {
		OutlierDetectionConfig custom = OutlierDetectionConfig.builder().consecutiveFailures(0).minRequests(50).failureRatio(0.8)
				.baseEjection(Duration.ofSeconds(2)).maxEjection(Duration.ofSeconds(4)).maxEjectionPercent(30).build();
		assertEquals(custom, RpcClientConfig.builder().registry(REGISTRY).outlierDetection(custom).build().outlierDetection());
		assertFalse(RpcClientConfig.builder().registry(REGISTRY).outlierDetection(OutlierDetectionConfig.disabled()).build()
				.outlierDetection().enabled());
		assertThrows(IllegalArgumentException.class, () -> RpcClientConfig.builder().outlierDetection(null));
	}

	@Test
	void theOneDotZeroCanonicalConstructorStillWorks() {
		RpcClientConfig config = new RpcClientConfig(REGISTRY, null, null, null, 2, null, true, List.of(), Map.of(), null, List.of(), 0,
				null, true, 32 * 1024, 64 * 1024, -1);
		assertSame(OutlierDetectionConfig.defaults(), config.outlierDetection());
	}
}
