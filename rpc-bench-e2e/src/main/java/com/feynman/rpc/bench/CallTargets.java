package com.feynman.rpc.bench;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 按卡片的 {@code target.transport} 名字创建 {@link CallTarget}：内置 {@code rpc} / {@code http}，其余从 classpath 上的
 * {@link CallTargetProvider}（{@link ServiceLoader}）里找。找不到时的错误列出当前可用的全部 transport——
 * 最常见的原因是没有把对比模块加进 classpath（{@code BENCH_EXTRA_CP}）。
 */
final class CallTargets {

	static final List<String> BUILTIN = List.of(Profile.TRANSPORT_RPC, Profile.TRANSPORT_HTTP);
	static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");

	private CallTargets() {
	}

	/** 内置 + classpath 上发现的全部 transport 名（排序：内置在前）。 */
	static Set<String> available() {
		Set<String> names = new java.util.LinkedHashSet<>(BUILTIN);
		names.addAll(new TreeSet<>(providers().keySet()));
		return Collections.unmodifiableSet(names);
	}

	/**
	 * classpath 上的提供者，按 transport 名。名字不合法、与内置重名、两个提供者同名都是装配错误，直接抛出
	 * （不能让"哪个 jar 先被加载"决定压的是谁）。每次重新发现：只在读卡与建目标时调用，不在压测路径上。
	 */
	static Map<String, CallTargetProvider> providers() {
		Map<String, CallTargetProvider> byName = new LinkedHashMap<>();
		for (CallTargetProvider provider : ServiceLoader.load(CallTargetProvider.class)) {
			String name = provider.transport();
			if (name == null || !NAME.matcher(name).matches()) {
				throw new IllegalStateException(provider.getClass().getName() + ".transport() = \"" + name + "\" must match " + NAME.pattern());
			}
			if (BUILTIN.contains(name)) {
				throw new IllegalStateException(provider.getClass().getName() + " claims the built-in transport \"" + name + "\"");
			}
			CallTargetProvider previous = byName.putIfAbsent(name, provider);
			if (previous != null) {
				throw new IllegalStateException("two CallTargetProviders for transport \"" + name + "\": " + previous.getClass().getName()
						+ " and " + provider.getClass().getName());
			}
		}
		return byName;
	}

	/** 对比对象的提供者；没有时抛 {@link IllegalArgumentException}，消息里列出可用的 transport。 */
	static CallTargetProvider provider(String transport) {
		CallTargetProvider provider = providers().get(transport);
		if (provider == null) {
			throw new IllegalArgumentException(unknownMessage(transport));
		}
		return provider;
	}

	static String unknownMessage(String transport) {
		return "unknown transport \"" + transport + "\"; available: " + available()
				+ " (comparison transports come from CallTargetProvider jars on the classpath, e.g. BENCH_EXTRA_CP=rpc-bench-compare-grpc/target/classes:rpc-bench-compare-grpc/target/lib/*)";
	}

	/**
	 * @param metrics 非 null 时被压的 RpcClient 带指标 Filter 与 Gauge（{@code --metrics-port}，只对 rpc 卡）
	 * @param options {@link HarnessOptions#ioThreads()} 对 rpc 卡传给 {@code RpcClientConfig.ioThreads}，对 http 卡是 netty 客户端的事件循环数，
	 *                对比对象由提供者使用；{@code --http-client} / {@code --io-busy-poll} 只对 http 卡有效
	 */
	static CallTarget create(Profile profile, CallTarget.Endpoint endpoint, MeterRegistry metrics, HarnessOptions options) {
		return create(profile, endpoint, metrics, options, Map.of());
	}

	/**
	 * @param controlPorts 提供者公布地址 → HTTP 控制端口（{@code --topology} 的第 6 列）：rpc 卡的每提供者统计改经 HTTP 取，不经业务执行器
	 */
	static CallTarget create(Profile profile, CallTarget.Endpoint endpoint, MeterRegistry metrics, HarnessOptions options,
			Map<String, Integer> controlPorts) {
		String transport = profile.target().transport();
		if (!profile.target().http() && options.httpOnlyOptionsGiven()) {
			throw new IllegalArgumentException("--http-client / --io-busy-poll apply to transport = http only");
		}
		return switch (transport) {
			case Profile.TRANSPORT_RPC -> RpcCallTarget.connect(profile, endpoint, metrics, options.ioThreads(), options.flushConsolidation(),
					ClusterChoice.resolve(profile, options), controlPorts);
			case Profile.TRANSPORT_HTTP -> {
				if (metrics != null) {
					throw new IllegalArgumentException("--metrics-port does not apply to transport = http");
				}
				yield HarnessOptions.HTTP_CLIENT_BLOCKING.equals(options.effectiveHttpClient(profile)) ? BlockingHttpCallTarget.connect(profile)
						: HttpCallTarget.connect(profile, options.effectiveIoThreads(profile), options.effectiveIoBusyPoll(profile));
			}
			default -> {
				if (metrics != null) {
					throw new IllegalArgumentException("--metrics-port instruments the RPC client and does not apply to transport = " + transport);
				}
				if (!CallTarget.Endpoint.DIRECT.equals(endpoint.mode())) {
					throw new IllegalArgumentException("transport = " + transport + " is addressed with --direct host:port (comparison targets do not use a registry)");
				}
				CallTargetProvider provider = provider(transport);
				yield provider.create(profile, endpoint, options);
			}
		};
	}
}
