package io.github.sisyfyzhang.steadyrpc.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 被压的对象：一次 {@link #call()} = 一次完整的同步请求（发出、等待响应、校验回显）。负载驱动只依赖这个接口，
 * {@code target.transport = rpc} 由 {@link RpcCallTarget} 实现，{@code http} 由 {@link HttpCallTarget} /
 * {@link BlockingHttpCallTarget} 实现，其它 transport（gRPC、Dubbo 等对比对象）由 classpath 上的
 * {@link CallTargetProvider} 提供（见 {@link CallTargets}）；驱动、计时与报告对它们完全相同。
 *
 * <p>实现要求（对比对象与本框架同一口径）：{@link #call()} 在调用方线程（闭环虚拟用户 / 开环工作者）上<b>同步</b>完成一次调用；
 * 超时 = 卡片的 {@code load.timeout}、不重试；失败以异常抛出（{@link BenchCallException} 可指定错误分桶名）。
 * 这是公开 API：对比模块（{@code steadyrpc-bench-compare-*}）在本模块之外实现它。
 */
public interface CallTarget extends AutoCloseable {

	/** 发出一次调用并等待结果；失败（超时、远端错误、回显不一致）以异常抛出，由驱动分桶计数。 */
	void call() throws Exception;

	/**
	 * 开压之前做一次完整校验（逐字节 / 全字段比对回显）。回显不一致抛 {@link BenchCallException#payloadMismatch(String)}
	 * （harness 或框架有 bug，压出来的数字没有意义）；其它失败（例如卡片故意设了极小的 timeout）照常抛出，由调用方决定。
	 */
	void verify();

	/** 被压服务端的运行环境（进 JSON 的 {@code server_environment}）；取不到时抛异常，由调用方记 unknown。 */
	Map<String, String> serverEnvironment();

	/**
	 * 被压服务端此刻的累计 GC 次数 / 时间与堆用量（{@link ServerProbes#gcStats()} 的形式），每轮统计窗口起止各取一次。
	 * 取不到时抛异常，由调用方把 {@code rounds[].gc.server} 记为 unknown；缺省实现（不是 bench server 的目标）直接抛。
	 */
	default Map<String, String> serverStats() {
		throw new UnsupportedOperationException("this target does not expose server stats");
	}

	/**
	 * 压测端客户端此刻的累计计数（与 {@link #serverStats()} 同一形式，值压平成字符串），每轮统计窗口起止各取一次。
	 * rpc 卡：出站背压的 {@code RpcClient.sendBufferRejections()} / {@code unwritableConnectionSwitches()}（键见
	 * {@link BackpressureStats}）。缺省实现（对比对象、HTTP 卡）返回空表。
	 */
	default Map<String, String> clientStats() {
		return Map.of();
	}

	/**
	 * 每个提供者此刻的轻量累计计数（地址 → {@code BenchService.counters()} 的内容），故障注入时间线每秒取一次。取某个提供者失败
	 * （已被杀、连接挂住）时该地址的值为 null。缺省实现（对比对象、HTTP 卡）返回空表。调用方保证不在压测线程上调用。
	 */
	default Map<String, Map<String, String>> providerCounters() {
		return Map.of();
	}

	/**
	 * 进 JSON 的 {@code run.target}：怎么连的、客户端的关键配置。约定的键：{@code transport}、{@code mode}、{@code address}、
	 * {@code providers}（实际提供者地址列表）、{@code serializer}、{@code connection_model}（连接与多路复用方式，一句话）、
	 * {@code io_threads}、{@code retries}、{@code call_style}。
	 */
	Map<String, Object> describe();

	@Override
	void close();

	/**
	 * 命令行给出的目标：{@code --registry zk://host:port} 或 {@code --direct host:port[,host:port]}；
	 * {@code transport = http} 的卡由卡片上的 {@code target.http.url} 决定（mode = http）。
	 */
	record Endpoint(String mode, String address) {

		public static final String REGISTRY = "registry";
		public static final String DIRECT = "direct";
		public static final String HTTP = "http";

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

		/** {@code direct} 模式下的地址列表（{@code host:port}，已校验格式）；其它模式抛 {@link IllegalStateException}。 */
		public List<String> directAddresses() {
			if (!DIRECT.equals(mode)) {
				throw new IllegalStateException("not a --direct endpoint: " + describe());
			}
			List<String> result = new ArrayList<>();
			for (DirectRegistryFactory.HostPort hp : DirectRegistryFactory.parse(address)) {
				result.add(hp.host() + ":" + hp.port());
			}
			return result;
		}

		public String describe() {
			return REGISTRY.equals(mode) ? "zk://" + address : HTTP.equals(mode) ? address : "direct " + address;
		}
	}
}
