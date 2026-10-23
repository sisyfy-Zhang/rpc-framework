package io.github.sisyfyzhang.steadyrpc.bench;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用的对比对象（{@code META-INF/services} 注册，transport = {@value #NAME}）：进程内"回显"，不走网络，只用来验证 SPI 的发现、
 * 卡片校验、{@code run} 与 {@code server --transport} 的接线。负载 = {@link ServerProbes#payload(int)}，元数据 = {@link ServerProbes}。
 */
public final class FakeCallTargetProvider implements CallTargetProvider {

	static final String NAME = "fake-echo";
	/** 最近一次 {@code server --transport fake-echo} 收到的参数（测试断言用）。 */
	static final AtomicReference<List<String>> LAST_SERVER_ARGS = new AtomicReference<>();

	@Override
	public String transport() {
		return NAME;
	}

	@Override
	public Set<String> serializers() {
		return Set.of("identity");
	}

	@Override
	public CallTarget create(Profile profile, CallTarget.Endpoint endpoint, HarnessOptions options) {
		List<String> addresses = endpoint.directAddresses();
		if (addresses.size() != profile.target().providers()) {
			throw new IllegalArgumentException("target.providers = " + profile.target().providers() + " but --direct gives " + addresses);
		}
		byte[] payload = ServerProbes.payload(profile.target().payloadSize());
		Map<String, String> environment = new LinkedHashMap<>(ServerProbes.environment());
		environment.put("transport", NAME);
		Map<String, Object> description = new LinkedHashMap<>();
		description.put("transport", NAME);
		description.put("mode", endpoint.mode());
		description.put("address", endpoint.describe());
		description.put("providers", addresses);
		description.put("serializer", profile.target().serializer());
		description.put("connection_model", "in-process copy (test double)");
		description.put("io_threads", options.ioThreads() == null ? HarnessOptions.nettyDefaultIoThreadsText() : options.ioThreads());
		description.put("retries", 0);
		description.put("call_style", "synchronous copy on the caller thread");
		return new CallTarget() {
			@Override
			public void call() {
				byte[] echoed = Arrays.copyOf(payload, payload.length);
				if (echoed.length != payload.length) {
					throw BenchCallException.payloadMismatch("length");
				}
			}

			@Override
			public void verify() {
				if (!Arrays.equals(payload, Arrays.copyOf(payload, payload.length))) {
					throw BenchCallException.payloadMismatch("echo differs");
				}
			}

			@Override
			public Map<String, String> serverEnvironment() {
				return environment;
			}

			@Override
			public Map<String, String> serverStats() {
				return ServerProbes.gcStats();
			}

			@Override
			public Map<String, Object> describe() {
				return description;
			}

			@Override
			public void close() {
			}
		};
	}

	@Override
	public Optional<ServerLauncher> serverLauncher() {
		return Optional.of((String[] args, PrintStream out) -> {
			LAST_SERVER_ARGS.set(List.of(args));
			out.println("bench-server ready port=0 pid=" + ProcessHandle.current().pid() + " transport=" + NAME);
			return Bench.OK;
		});
	}
}
