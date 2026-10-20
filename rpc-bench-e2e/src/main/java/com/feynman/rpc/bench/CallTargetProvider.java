package com.feynman.rpc.bench;

import java.util.Optional;
import java.util.Set;

/**
 * 对比对象的接入点：卡片的 {@code target.transport} 不是内置的 {@code rpc} / {@code http} 时，harness 用
 * {@link java.util.ServiceLoader} 在 classpath 上找 {@link #transport()} 同名的提供者
 * （{@code META-INF/services/com.feynman.rpc.bench.CallTargetProvider}），用它创建 {@link CallTarget}，
 * 可选地用它的 {@link ServerLauncher} 起对应的服务端（{@code bench server --transport <name>}）。
 * 对比模块（{@code rpc-bench-compare-grpc} 等）的 {@code target/classes:target/lib/*} 经 {@code BENCH_EXTRA_CP} 加进脚本的 classpath。
 *
 * <p>口径约定（与本框架的 rpc 卡对等）：
 * <ul>
 *   <li>服务契约与 {@code BenchService} 相同：{@code echo(byte[])} 原样回显，另有 environment / stats 两个元数据调用
 *       （返回 {@link ServerProbes} 采集的同一套字段）；</li>
 *   <li>{@link CallTarget#call()} 在调用方线程上同步调用，超时 = {@code load.timeout}，不重试；</li>
 *   <li>{@link HarnessOptions#ioThreads()}（{@code --io-threads}）：给了就作为客户端事件循环线程数，没给 = Netty 缺省（2 × 可用核数）。</li>
 * </ul>
 */
public interface CallTargetProvider {

	/** 卡片 {@code target.transport} 的取值（小写字母、数字、连字符）；不能是内置的 {@code rpc} / {@code http}。 */
	String transport();

	/** 卡片 {@code target.serializer} 允许的取值（描述这个 transport 自带的编码方式，例如 gRPC 的 {@code protobuf}）。 */
	Set<String> serializers();

	/** 卡片 {@code target.service} 允许的取值；缺省只有 {@code BenchService#echo}。 */
	default Set<String> services() {
		return Set.of("BenchService#echo");
	}

	/**
	 * 连接目标并返回可压的 {@link CallTarget}（建连、等就绪都在这里完成；失败直接抛出，不压）。
	 *
	 * @param endpoint 只会是 {@code --direct host:port[,...]}（{@link CallTarget.Endpoint#directAddresses()}）：
	 *                 对比对象不经注册中心，基础性能对比排除注册中心变量
	 */
	CallTarget create(Profile profile, CallTarget.Endpoint endpoint, HarnessOptions options);

	/** 对应的压测服务端（{@code bench server --transport <name> ...}）；没有时为空。 */
	default Optional<ServerLauncher> serverLauncher() {
		return Optional.empty();
	}
}
