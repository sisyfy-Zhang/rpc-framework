package io.github.sisyfyzhang.steadyrpc.metrics.micrometer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.RpcDeadlineExceededException;
import io.github.sisyfyzhang.steadyrpc.core.RpcException;
import io.github.sisyfyzhang.steadyrpc.core.RpcOverloadedException;
import io.github.sisyfyzhang.steadyrpc.core.RpcSerializationException;
import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;
import io.github.sisyfyzhang.steadyrpc.core.filter.DeadlineClientFilter;
import io.github.sisyfyzhang.steadyrpc.core.filter.DeadlineServerFilter;
import io.github.sisyfyzhang.steadyrpc.core.filter.Filter;
import io.github.sisyfyzhang.steadyrpc.core.filter.FilterChain;
import io.github.sisyfyzhang.steadyrpc.core.filter.Invocation;
import io.github.sisyfyzhang.steadyrpc.core.filter.Result;
import io.github.sisyfyzhang.steadyrpc.core.protocol.Status;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Filter 层面的单元测试（不起网络）：status tag 的映射、计时非零且按 future 完成时刻计、Filter 不阻塞调用线程、
 * 链上位置在内置 Filter 之外、tag 取值有界（不含异常类名）。时钟用 MockClock，计时是精确值。
 */
class MicrometerFiltersTest {

	public interface Echo {
		String echo(String s);
	}

	/** status tag 允许的全部取值：协议状态 + 客户端独有的两个。 */
	static final Set<String> ALLOWED_STATUSES;
	static {
		java.util.HashSet<String> allowed = new java.util.HashSet<>();
		for (Status status : Status.values()) {
			allowed.add(status.name());
		}
		allowed.add(CallStatus.TIMEOUT);
		allowed.add(CallStatus.TRANSPORT_FAILURE);
		ALLOWED_STATUSES = Set.copyOf(allowed);
	}

	private final MockClock clock = new MockClock();
	private final SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
	private final Method echo = method();

	private static Method method() {
		try {
			return Echo.class.getMethod("echo", String.class);
		} catch (NoSuchMethodException e) {
			throw new AssertionError(e);
		}
	}

	private Invocation clientInvocation() {
		return new Invocation(ServiceKey.of(Echo.class), echo, new Object[] { "x" }, Map.of(), Duration.ofSeconds(1));
	}

	private Invocation serverInvocation() {
		return Invocation.inbound(ServiceKey.of(Echo.class), echo, () -> new Object[] { "x" }, Map.of(), Duration.ofSeconds(1),
				System.nanoTime());
	}

	private Timer timer(String name, String status) {
		return registry.find(name).tag("service", Echo.class.getName()).tag("method", "echo").tag("status", status).timer();
	}

	/** 下游返回一个未完成的 future，推进 7ms 后按 {@code outcome} 完成它；返回该次调用的 status 对应的 Timer。 */
	private void runClient(Function<CompletableFuture<Result>, Object> outcome) {
		MicrometerClientFilter filter = new MicrometerClientFilter(registry);
		CompletableFuture<Result> downstream = new CompletableFuture<>();
		CompletableFuture<Result> returned = filter.invoke(inv -> downstream, clientInvocation());
		assertSame(downstream, returned, "the filter hands back the downstream future unchanged");
		clock.add(7, TimeUnit.MILLISECONDS);
		outcome.apply(downstream);
	}

	private void runServer(Invocation invocation, Function<CompletableFuture<Result>, Object> outcome) {
		MicrometerServerFilter filter = new MicrometerServerFilter(registry);
		CompletableFuture<Result> downstream = new CompletableFuture<>();
		CompletableFuture<Result> returned = filter.invoke(inv -> downstream, invocation);
		assertSame(downstream, returned);
		clock.add(7, TimeUnit.MILLISECONDS);
		outcome.apply(downstream);
	}

	private void assertRecorded(String name, String status) {
		Timer timer = timer(name, status);
		assertTrue(timer != null, name + " with status=" + status + " not recorded; meters: " + registry.getMetersAsString());
		assertEquals(1, timer.count());
		assertEquals(7.0, timer.totalTime(TimeUnit.MILLISECONDS), 1e-9, "timed until the future completed");
	}

	@Test
	void clientSuccessIsOkAndTimedUntilTheFutureCompletes() {
		runClient(f -> f.complete(Result.value("x")));
		assertRecorded(MicrometerClientFilter.METER_NAME, "OK");
	}

	@Test
	void clientBusinessExceptionIsBizExceptionNotTheClassName() {
		runClient(f -> f.complete(Result.exception(new IllegalStateException("out of stock"))));
		assertRecorded(MicrometerClientFilter.METER_NAME, "BIZ_EXCEPTION");
	}

	@Test
	void clientTransportFailureIsTransportFailure() {
		runClient(f -> f.completeExceptionally(new RpcException("Connection refused: 127.0.0.1:1")));
		assertRecorded(MicrometerClientFilter.METER_NAME, "TRANSPORT_FAILURE");
	}

	@Test
	void clientLocalTimeoutIsTimeoutButServerSideDeadlineStaysDeadlineExceeded() {
		runClient(f -> f.complete(Result.exception(new RpcDeadlineExceededException("no response", new TimeoutException()))));
		assertRecorded(MicrometerClientFilter.METER_NAME, "TIMEOUT");
		runClient(f -> f.complete(Result.exception(new RpcDeadlineExceededException("server rejected expired request"))));
		assertRecorded(MicrometerClientFilter.METER_NAME, "DEADLINE_EXCEEDED");
	}

	@Test
	void clientOverloadRejectionKeepsItsStatusAlthoughItTravelsAsAFailedFuture() {
		runClient(f -> f.completeExceptionally(new java.util.concurrent.CompletionException(new RpcOverloadedException("server overloaded"))));
		assertRecorded(MicrometerClientFilter.METER_NAME, "SERVER_OVERLOADED");
	}

	@Test
	void clientSerializationFailureUsesTheExceptionStatus() {
		runClient(f -> f.complete(Result.exception(new RpcSerializationException("cannot decode"))));
		assertRecorded(MicrometerClientFilter.METER_NAME, "SERIALIZATION_ERROR");
	}

	@Test
	void serverSuccessIsOk() {
		runServer(serverInvocation(), f -> f.complete(Result.value("x")));
		assertRecorded(MicrometerServerFilter.METER_NAME, "OK");
	}

	@Test
	void serverBusinessExceptionIsBizException() {
		Invocation invocation = serverInvocation();
		invocation.markTargetInvoked();
		runServer(invocation, f -> f.complete(Result.exception(new IllegalArgumentException("bad order"))));
		assertRecorded(MicrometerServerFilter.METER_NAME, "BIZ_EXCEPTION");
	}

	@Test
	void serverRejectionBeforeTheBusinessMethodUsesTheExceptionStatus() {
		runServer(serverInvocation(), f -> f.complete(Result.exception(new RpcDeadlineExceededException("expired"))));
		assertRecorded(MicrometerServerFilter.METER_NAME, "DEADLINE_EXCEEDED");
		runServer(serverInvocation(), f -> f.completeExceptionally(new RpcSerializationException("bad argument bytes")));
		assertRecorded(MicrometerServerFilter.METER_NAME, "SERIALIZATION_ERROR");
	}

	@Test
	void serverFrameworkFailuresAreServerError() {
		Invocation invoked = serverInvocation();
		invoked.markTargetInvoked();
		// 业务方法里的下游调用失败（RpcException）：分发器按 SERVER_ERROR 回包，不沿用下游的状态
		runServer(invoked, f -> f.complete(Result.exception(new RpcOverloadedException("downstream overloaded"))));
		assertRecorded(MicrometerServerFilter.METER_NAME, "SERVER_ERROR");
		Timer before = timer(MicrometerServerFilter.METER_NAME, "SERVER_ERROR");
		runServer(serverInvocation(), f -> f.completeExceptionally(new IllegalStateException("filter bug")));
		assertEquals(2, before.count(), "a failed chain future is SERVER_ERROR too");
	}

	@Test
	void theFilterDoesNotBlockTheCallingThread() throws Exception {
		MicrometerClientFilter filter = new MicrometerClientFilter(registry);
		CompletableFuture<Result> downstream = new CompletableFuture<>();
		long started = System.nanoTime();
		CompletableFuture<Result> returned = filter.invoke(inv -> downstream, clientInvocation());
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
		assertSame(downstream, returned);
		assertFalse(returned.isDone(), "the call is still in flight: nothing waited for it");
		assertTrue(elapsedMillis < 1000, "invoke returned in " + elapsedMillis + "ms");
		assertNull(registry.find(MicrometerClientFilter.METER_NAME).timer(), "nothing recorded before completion");

		// 由另一个线程完成（像 IO 线程那样），记录发生在完成的那一刻
		Thread completer = new Thread(() -> downstream.complete(Result.value("x")));
		completer.start();
		completer.join(5000);
		assertEquals(1, timer(MicrometerClientFilter.METER_NAME, "OK").count());
	}

	@Test
	void aSynchronouslyThrowingDownstreamIsRecordedAndTurnedIntoAFailedFuture() {
		MicrometerClientFilter filter = new MicrometerClientFilter(registry);
		CompletableFuture<Result> returned = filter.invoke(inv -> {
			throw new IllegalStateException("boom");
		}, clientInvocation());
		assertTrue(returned.isCompletedExceptionally());
		assertEquals(1, timer(MicrometerClientFilter.METER_NAME, "TRANSPORT_FAILURE").count());
	}

	@Test
	void metricsFiltersSitOutsideTheBuiltInDeadlineFilters() {
		assertTrue(MicrometerClientFilter.ORDER < DeadlineClientFilter.ORDER);
		assertTrue(MicrometerServerFilter.ORDER < DeadlineServerFilter.ORDER);
		MicrometerClientFilter clientMetrics = new MicrometerClientFilter(registry);
		MicrometerServerFilter serverMetrics = new MicrometerServerFilter(registry);
		List<Filter> client = FilterChain.ordered(List.of(new DeadlineClientFilter(), (next, inv) -> next.invoke(inv), clientMetrics));
		List<Filter> server = FilterChain.ordered(List.of(new DeadlineServerFilter(), serverMetrics));
		assertSame(clientMetrics, client.get(0), "outermost on the client");
		assertSame(serverMetrics, server.get(0), "outermost on the server");
	}

	@Test
	void serverTimersPublishBucketsFromOneMicrosecondToTenSeconds() {
		// SimpleMeterRegistry 不导出桶；用 Prometheus registry 的抓取文本检查真正发出去的桶边界
		io.micrometer.core.instrument.MeterRegistry prometheus = PrometheusEndpoint.newRegistry();
		new MicrometerServerFilter(prometheus).invoke(inv -> CompletableFuture.completedFuture(Result.value("x")), serverInvocation()).join();
		assertBuckets(PrometheusSupport.scraper(prometheus).get(), "rpc_server_calls_seconds_bucket", 1e-6);
	}

	@Test
	void clientTimersPublishBucketsFromOneHundredMicrosecondsToTenSeconds() {
		io.micrometer.core.instrument.MeterRegistry prometheus = PrometheusEndpoint.newRegistry();
		new MicrometerClientFilter(prometheus).invoke(inv -> CompletableFuture.completedFuture(Result.value("x")), clientInvocation()).join();
		assertBuckets(PrometheusSupport.scraper(prometheus).get(), "rpc_client_calls_seconds_bucket", 1e-4);
	}

	/** 桶上界全部落在 [lowest, 10s]，且最小的那个就是 lowest（下界确实生效）；+Inf 之外的桶数有界。 */
	private static void assertBuckets(String text, String series, double lowest) {
		java.util.regex.Matcher le = java.util.regex.Pattern.compile(series + "\\{[^}]*le=\"([^\"]+)\"").matcher(text);
		int buckets = 0;
		double min = Double.MAX_VALUE;
		while (le.find()) {
			if (le.group(1).equals("+Inf")) {
				continue;
			}
			buckets++;
			double upperSeconds = Double.parseDouble(le.group(1));
			min = Math.min(min, upperSeconds);
			assertTrue(upperSeconds >= lowest * (1 - 1e-9) && upperSeconds <= 10.0 * (1 + 1e-9),
					series + " le=" + upperSeconds + "s outside [" + lowest + "s, 10s]");
		}
		assertEquals(lowest, min, lowest * 1e-6, "smallest bucket is the configured minimum");
		assertTrue(buckets > 10 && buckets < 150, series + ": bounded percentile histogram, got " + buckets + " buckets\n" + text);
		System.out.printf("[buckets] %s: %d finite buckets (+Inf extra), lowest le=%s%n", series, buckets, min);
	}

	@Test
	void statusTagValuesAreBoundedAndNeverExceptionClassNames() {
		runClient(f -> f.complete(Result.exception(new IllegalStateException("x"))));
		runClient(f -> f.complete(Result.exception(new UnsupportedOperationException("y"))));
		runClient(f -> f.completeExceptionally(new java.io.UncheckedIOException(new java.io.IOException("reset"))));
		runServer(serverInvocation(), f -> f.complete(Result.exception(new NullPointerException())));
		for (Meter meter : registry.getMeters()) {
			for (Tag tag : meter.getId().getTags()) {
				if (tag.getKey().equals("status")) {
					assertTrue(ALLOWED_STATUSES.contains(tag.getValue()), "unbounded status value: " + tag.getValue());
				}
				assertFalse(tag.getValue().endsWith("Exception"), "exception class leaked into a tag: " + tag);
			}
		}
	}
}
