package com.feynman.rpc.bench;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.feynman.rpc.core.RpcClient;
import com.feynman.rpc.core.RpcException;
import com.feynman.rpc.core.RpcOverloadedException;
import com.feynman.rpc.core.config.ReferenceOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段一审核后的三条规则：传输失败的错误分桶（{@code transport} / {@code no_provider}）、{@code target.server_deregister_grace}、
 * 故障注入 / 阶梯卡上 {@code dispatcher_delayed} 只记标志不判无效。
 */
class FaultRulingsTest {

	@Test
	void clientSideTransportFailuresGetTheirOwnBucket() {
		for (String prefix : ErrorClassifier.TRANSPORT_PREFIXES) {
			assertEquals(ErrorClassifier.TRANSPORT, ErrorClassifier.classify(new RpcException(prefix + "127.0.0.1:1")), prefix);
		}
		assertEquals(ErrorClassifier.NO_PROVIDER, ErrorClassifier.classify(new RpcException("No available provider for: x")));
		// 服务端回的错误、子类异常照旧按状态码
		assertEquals("status:SERVER_ERROR", ErrorClassifier.classify(new RpcException("BAD_REQUEST from server for x: y")));
		assertEquals("status:SERVER_OVERLOADED", ErrorClassifier.classify(new RpcOverloadedException("SERVER_OVERLOADED from x")));
		assertEquals(ErrorClassifier.TIMEOUT, ErrorClassifier.classify(new com.feynman.rpc.core.RpcDeadlineExceededException("late")));
	}

	/** 守着 core 的消息前缀：真的连一个没人监听的端口，真实的传输失败必须落进 transport 桶（改了 core 的消息这里会失败）。 */
	@Test
	void aRealConnectionFailureIsClassifiedAsTransport() throws Exception {
		int dead;
		try (ServerSocket socket = new ServerSocket(0)) {
			dead = socket.getLocalPort();
		}
		try (RpcClient client = RpcClient.builder().registry(DirectRegistryFactory.config("127.0.0.1:" + dead)).serializer("protobuf").build()) {
			BenchService service = client.reference(BenchService.class, ReferenceOptions.defaults().timeout(Duration.ofSeconds(2)));
			Throwable first = assertThrows(RuntimeException.class, () -> service.echo(new byte[1]));
			assertEquals(ErrorClassifier.TRANSPORT, ErrorClassifier.classify(first), String.valueOf(first));
			Throwable again = assertThrows(RuntimeException.class, () -> service.echo(new byte[1]));
			assertEquals(ErrorClassifier.TRANSPORT, ErrorClassifier.classify(again), String.valueOf(again));
		}
	}

	@Test
	void deregisterGraceIsACardFieldCheckedAgainstTheServer() throws Exception {
		Profile p = ProfileLoader.parse(FaultCardSchemaTest.card("  server_deregister_grace: 2s", "  rate: 100"), null, "t.yaml");
		assertEquals(Duration.ofSeconds(2), p.target().rpcOptions().serverDeregisterGrace());
		assertEquals("2s", ((Map<?, ?>) p.toJson().get("target")).get("server_deregister_grace"));
		ProfileException e = assertThrows(ProfileException.class,
				() -> ProfileLoader.parse(FaultCardSchemaTest.card("  server_deregister_grace: 31s", "  rate: 100"), null, "t.yaml"));
		assertTrue(e.problems().stream().anyMatch(x -> x.contains("server_deregister_grace: 31s is longer than 30s")), e.problems().toString());
		try (BenchServer graceful = BenchServer.start(0, "127.0.0.1", null, "protobuf", null, 0,
				com.feynman.rpc.core.config.ExecutorConfig.defaults(), true, true, null, null, 0, Duration.ofSeconds(2))) {
			assertEquals("2000", graceful.environment().get("server_deregister_grace_ms"));
			assertEquals(Duration.ofSeconds(2), graceful.rpcServer().config().deregisterGrace());
		}
	}

	/** 有时间线的卡（阶梯 / 故障）：发出滞后超限只记 dispatcher_delayed 标志，本轮仍有效；没有时间线的卡照旧判无效。 */
	@Test
	void dispatcherDelayedIsInformationalOnFaultAndStepCards() {
		Profile stepCard = ProfileLoader.parse(DispatcherDelayedTest.openCard("it-dispatch-steps",
				"steps: [{ rate: 500, duration: 500ms }, { rate: 1500, duration: 500ms }]", "1s", "400ms", "[400ms, 1s]", "1s"), null, "test");
		Profile plainCard = ProfileLoader.parse(DispatcherDelayedTest.openCard("it-dispatch-plain", "rate: 1000", "1s", "400ms", "[400ms, 1s]",
				"1s"), null, "test");
		Map<String, Object> informational = delayedRound(stepCard);
		assertEquals(Boolean.TRUE, informational.get("valid"), String.valueOf(informational.get("invalid_reasons")));
		assertEquals(Boolean.TRUE, informational.get("dispatcher_delayed"));
		assertEquals(Boolean.TRUE, informational.get("dispatcher_delayed_informational"));
		Map<String, Object> strict = delayedRound(plainCard);
		assertEquals(Boolean.FALSE, strict.get("valid"));
		assertFalse(strict.containsKey("dispatcher_delayed_informational"));

		Map<String, Object> summary = BenchRunner.summary(stepCard, List.of(informational));
		Map<String, Object> validity = BenchRunner.validity(stepCard, List.of(informational), summary, Map.of("providers", List.of(), "address", "x"));
		assertEquals(Boolean.TRUE, validity.get("dispatcher_delayed"));
		assertEquals(Boolean.TRUE, validity.get("all_rounds_valid"));
		assertTrue(String.valueOf(validity.get("statements")).contains("只记标志、不判无效"), String.valueOf(validity.get("statements")));
	}

	private static Map<String, Object> delayedRound(Profile profile) {
		RoundRecorder recorder = new RoundRecorder(1, profile);
		for (int i = 0; i < 1000; i++) {
			recorder.issued();
			recorder.dispatchLag(i < 20 ? 2_000_000 : 10_000);
		}
		long beforeWindow = recorder.startNanos + 100_000_000L;
		recorder.success(beforeWindow - 300_000, beforeWindow - 300_000, beforeWindow);
		for (int i = 0; i < 100; i++) {
			long done = recorder.startNanos + 500_000_000L + i * 1_000_000L;
			recorder.success(done - 300_000, done - 300_000, done);
		}
		recorder.finish();
		return recorder.toJson();
	}
}
