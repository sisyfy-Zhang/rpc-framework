package com.feynman.rpc.bench;

import java.util.Map;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 被压的对象：一次 {@link #call()} = 一次完整的同步请求（发出、等待响应、校验回显）。负载驱动只依赖这个接口，
 * {@code target.transport = rpc} 由 {@link RpcCallTarget} 实现，{@code http} 由 {@link HttpCallTarget} 实现；
 * 驱动、计时与报告对两者完全相同。
 */
interface CallTarget extends AutoCloseable {

	/** 发出一次调用并等待结果；失败（超时、远端错误、回显不一致）以异常抛出，由驱动分桶计数。 */
	void call() throws Exception;

	/**
	 * 开压之前做一次完整校验（逐字节 / 全字段比对回显）。回显不一致抛 {@link ErrorClassifier.PayloadMismatchException}
	 * （harness 或框架有 bug，压出来的数字没有意义）；其它失败（例如卡片故意设了极小的 timeout）照常抛出，由调用方决定。
	 */
	void verify();

	/** 被压服务端的运行环境（进 JSON 的 {@code server_environment}）；取不到时抛异常，由调用方记 unknown。 */
	Map<String, String> serverEnvironment();

	/**
	 * 被压服务端此刻的累计 GC 次数 / 时间与堆用量（{@link GcSampler.Snapshot#toStrings()} 的形式），每轮统计窗口起止各取一次。
	 * 取不到时抛异常，由调用方把 {@code rounds[].gc.server} 记为 unknown；缺省实现（不是 bench server 的目标）直接抛。
	 */
	default Map<String, String> serverStats() {
		throw new UnsupportedOperationException("this target does not expose server stats");
	}

	/** 进 JSON 的 {@code run.target}：怎么连的、客户端的关键配置。 */
	Map<String, Object> describe();

	@Override
	void close();

	/** 按 {@code target.transport} 创建。 */
	static CallTarget create(Profile profile, Endpoint endpoint) {
		return create(profile, endpoint, null);
	}

	/** @param metrics 非 null 时被压的客户端带指标 Filter 与 Gauge（{@code --metrics-port}）；null 时与上面相同 */
	static CallTarget create(Profile profile, Endpoint endpoint, MeterRegistry metrics) {
		return switch (profile.target().transport()) {
			case "rpc" -> RpcCallTarget.connect(profile, endpoint, metrics);
			case "http" -> {
				if (metrics != null) {
					throw new IllegalArgumentException("--metrics-port does not apply to transport = http");
				}
				yield HttpCallTarget.connect(profile);
			}
			default -> throw new IllegalArgumentException("unknown transport " + profile.target().transport());
		};
	}

	/**
	 * 命令行给出的目标：{@code --registry zk://host:port} 或 {@code --direct host:port[,host:port]}；
	 * {@code transport = http} 的卡由卡片上的 {@code target.http.url} 决定（mode = http）。
	 */
	record Endpoint(String mode, String address) {

		static final String REGISTRY = "registry";
		static final String DIRECT = "direct";
		static final String HTTP = "http";

		static Endpoint http(String url) {
			return new Endpoint(HTTP, url);
		}

		static Endpoint registry(String uri) {
			String prefix = uri.startsWith("zk://") ? "zk://" : uri.startsWith("zookeeper://") ? "zookeeper://" : null;
			if (prefix == null || uri.length() == prefix.length()) {
				throw new IllegalArgumentException("--registry must look like zk://host:port, got \"" + uri + "\"");
			}
			return new Endpoint(REGISTRY, uri.substring(prefix.length()));
		}

		static Endpoint direct(String addresses) {
			DirectRegistryFactory.parse(addresses); // 校验格式
			return new Endpoint(DIRECT, addresses);
		}

		String describe() {
			return REGISTRY.equals(mode) ? "zk://" + address : HTTP.equals(mode) ? address : "direct " + address;
		}
	}
}
