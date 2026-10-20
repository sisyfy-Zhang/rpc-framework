package com.feynman.rpc.bench.dubbo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.MethodConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.bootstrap.DubboBootstrap;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.StatusRpcException;
import org.apache.dubbo.rpc.TriRpcStatus;
import org.apache.dubbo.rpc.model.FrameworkModel;

import com.feynman.rpc.bench.BenchCallException;
import com.feynman.rpc.bench.BenchService;
import com.feynman.rpc.bench.CallTarget;
import com.feynman.rpc.bench.HarnessOptions;
import com.feynman.rpc.bench.Profile;
import com.feynman.rpc.bench.ServerProbes;

/**
 * {@code transport: dubbo}：Dubbo 3 triple 协议的 {@link BenchService} 引用（Java 接口模式，直连 {@code tri://host:port}，
 * 不经注册中心），在调用方线程上同步调用代理方法——与 rpc 卡的同步代理调用完全同构（同一个接口、同一个方法）。
 *
 * <ul>
 *   <li><b>超时与重试</b>：{@code timeout} = {@code load.timeout}（毫秒，向上取整），{@code retries = 0}；
 *       {@code environment} / {@code stats} 两个元数据方法单独 30s（{@link MethodConfig}）。</li>
 *   <li><b>线程</b>：同步调用的响应由调用方线程处理（Dubbo 的 ThreadlessExecutor）；客户端 IO 线程是 Dubbo 的进程级共享事件循环，
 *       线程数固定为 Dubbo 的缺省 min(可用核数 + 1, 32)，没有配置项——所以 {@code --io-threads} 对 dubbo 卡是用法错误。</li>
 *   <li><b>连接</b>：缺省 triple 对一个地址一条 HTTP/2 连接，并发调用是多路复用的流（与 gRPC 相同；本框架每个提供者 2 条 TCP 连接）；
 *       卡片写了 {@code target.connections: N} 时建 N 个独立的客户端实例（各自的 FrameworkModel 与引用，各一条连接），每次调用轮询取一个
 *       （"连接数对齐"的对比卡用 2）。不用 reference 的 {@code connections} 参数：Dubbo 3.3.6 的 triple 忽略它（实测仍是 1 条连接）。</li>
 *   <li><b>序列化</b>：不指定，用 Dubbo 3.3 的缺省（实际生效的值从调用 URL 读回，进 {@code run.target.serializer}；卡片的
 *       {@code target.serializer} 必须与它一致，否则开压前就失败）。</li>
 *   <li><b>指标关闭</b>：同 {@link DubboBenchServer#metricsOff()}。</li>
 *   <li><b>错误分桶</b>：超时 → {@code timeout}；triple 状态 → {@code tri_status:<CODE>}；其余 Dubbo {@link RpcException} →
 *       {@code dubbo_rpc:<类别>}；回显长度不对 → {@code payload_mismatch}。</li>
 * </ul>
 */
final class DubboCallTarget implements CallTarget {

	static final int METADATA_TIMEOUT_MS = 30_000;
	private static final List<String> RPC_EXCEPTION_NAMES = List.of("UNKNOWN", "NETWORK", "TIMEOUT", "BIZ", "FORBIDDEN", "SERIALIZATION",
			"NO_INVOKER_AVAILABLE_AFTER_FILTER", "LIMIT_EXCEEDED", "TIMEOUT_TERMINATE", "REGISTRY", "ROUTER_CACHE_NOT_BUILD", "METHOD_NOT_FOUND",
			"VALIDATION", "AUTHORIZATION");

	/** 一个独立的 Dubbo 客户端实例（自己的 FrameworkModel / bootstrap / 引用 → 自己的一条 triple 连接）。 */
	private record Client(FrameworkModel framework, DubboBootstrap bootstrap, BenchService service, URL url) {

		void close() {
			bootstrap.stop();
			bootstrap.destroy();
			framework.destroy();
		}
	}

	private final List<Client> clients;
	private final BenchService[] services;
	/** 多个客户端实例时每次调用轮询取一个（与 gRPC 的多 channel、本框架连接池的轮询同一种做法）。 */
	private final AtomicInteger next = new AtomicInteger();
	private final byte[] payload;
	private final Map<String, Object> description;

	private DubboCallTarget(List<Client> clients, byte[] payload, Map<String, Object> description) {
		this.clients = clients;
		this.services = clients.stream().map(Client::service).toArray(BenchService[]::new);
		this.payload = payload;
		this.description = description;
	}

	private BenchService service() {
		return services.length == 1 ? services[0] : services[Math.floorMod(next.getAndIncrement(), services.length)];
	}

	static DubboCallTarget connect(Profile profile, CallTarget.Endpoint endpoint, HarnessOptions options) {
		List<String> addresses = endpoint.directAddresses();
		if (addresses.size() != 1 || profile.target().providers() != 1) {
			throw new IllegalArgumentException("transport: dubbo takes exactly one provider (target.providers = 1, --direct host:port), got "
					+ addresses + " with target.providers = " + profile.target().providers());
		}
		if (!"bytes".equals(profile.target().payloadKind())) {
			throw new IllegalArgumentException("transport: dubbo only implements BenchService#echo with payload.kind = bytes");
		}
		if (options.ioThreads() != null) {
			throw new IllegalArgumentException("--io-threads does not apply to transport: dubbo (the Dubbo 3.3 client shares one process-wide"
					+ " event loop sized min(available cores + 1, 32); it has no setting)");
		}
		long timeoutMs = Math.max(1, (profile.load().timeout().toNanos() + 999_999) / 1_000_000);
		if (timeoutMs > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("load.timeout is too large for Dubbo: " + profile.load().timeout());
		}
		// target.connections = N：N 个独立的客户端实例（各自一条 triple 连接），每次调用轮询取一个。不用 reference 的 connections 参数：
		// Dubbo 3.3.6 的 triple 忽略它（实测 connections=2 时服务端仍只有 1 条 TCP 连接、1 个 IO 线程在干活）。不写 = 1 个实例（Dubbo 缺省）。
		int connections = profile.target().connections() == null ? 1 : profile.target().connections();
		List<Client> clients = new ArrayList<>();
		try {
			for (int i = 0; i < connections; i++) {
				clients.add(client(addresses.get(0), (int) timeoutMs));
			}
			URL url = clients.get(0).url();
			String serialization = org.apache.dubbo.remoting.utils.UrlUtils.serializationOrDefault(url);
			if (!serialization.equals(profile.target().serializer())) {
				throw new IllegalArgumentException("target.serializer = " + profile.target().serializer() + " but Dubbo " + Version.getVersion()
						+ " uses \"" + serialization + "\" for this reference (prefer.serialization = "
						+ url.getParameter("prefer.serialization", "unset") + "); fix the card");
			}
			Map<String, Object> d = new LinkedHashMap<>();
			d.put("transport", "dubbo");
			d.put("mode", endpoint.mode());
			d.put("address", endpoint.describe());
			d.put("providers", addresses);
			d.put("serializer", serialization + " (Dubbo default for the Java-interface triple mode, wrapped in triple's protobuf envelope)");
			d.put("protocol", "tri (HTTP/2)");
			d.put("load_balance", "n/a (single direct url)");
			d.put("connections_per_node", connections);
			d.put("connection_model", connections == 1
					? "1 triple HTTP/2 connection per address; concurrent calls are multiplexed streams (rpc cards: 2 TCP connections per provider)"
					: connections + " independent Dubbo client instances = " + connections
							+ " triple HTTP/2 connections, calls take an instance round-robin; concurrent calls are multiplexed streams");
			d.put("connections_meaning", "target.connections = number of independent Dubbo client instances (FrameworkModel + reference), one triple"
					+ " connection each; the reference 'connections' parameter is ignored by triple in Dubbo 3.3.6 (default: 1)");
			d.put("connections_source", profile.target().connections() == null ? "default" : "target.connections");
			d.put("io_transport", "nio (Dubbo netty4, epoll off by default)");
			d.put("io_threads", "dubbo default (shared client event loop: min(available cores + 1, 32) = "
					+ Math.min(io.netty.util.NettyRuntime.availableProcessors() + 1, 32) + ")");
			d.put("retries", 0);
			d.put("call_style", "sync proxy method on the caller thread (ThreadlessExecutor), timeout = load.timeout");
			d.put("dubbo_version", Version.getVersion());
			d.put("url", url.toString());
			return new DubboCallTarget(List.copyOf(clients), ServerProbes.payload(profile.target().payloadSize()), d);
		} catch (RuntimeException e) {
			clients.forEach(Client::close);
			throw e;
		}
	}

	/** 一个独立的客户端实例：自己的 FrameworkModel（不共享 Dubbo 的进程级连接）、关掉 QoS 与指标、直连 {@code tri://address}。 */
	private static Client client(String address, int timeoutMs) {
		FrameworkModel framework = new FrameworkModel();
		DubboBootstrap bootstrap = DubboBootstrap.newInstance(framework);
		try {
			ApplicationConfig application = new ApplicationConfig("rpc-bench-dubbo-client");
			application.setQosEnable(false);
			ReferenceConfig<BenchService> reference = new ReferenceConfig<>();
			reference.setInterface(BenchService.class);
			reference.setUrl("tri://" + address);
			reference.setTimeout(timeoutMs);
			reference.setRetries(0);
			MethodConfig environment = new MethodConfig();
			environment.setName("environment");
			environment.setTimeout(METADATA_TIMEOUT_MS);
			MethodConfig stats = new MethodConfig();
			stats.setName("stats");
			stats.setTimeout(METADATA_TIMEOUT_MS);
			reference.setMethods(List.of(environment, stats));
			bootstrap.application(application).metrics(DubboBenchServer.metricsOff()).reference(reference).start();
			BenchService service = reference.get();
			return new Client(framework, bootstrap, service, reference.getInvoker().getUrl());
		} catch (RuntimeException e) {
			bootstrap.destroy();
			framework.destroy();
			throw e;
		}
	}

	@Override
	public void call() {
		byte[] echoed;
		try {
			echoed = service().echo(payload);
		} catch (RpcException e) {
			throw classify(e);
		}
		if (echoed == null || echoed.length != payload.length) {
			throw BenchCallException.payloadMismatch("echo returned " + (echoed == null ? "null" : echoed.length + " bytes") + ", sent " + payload.length);
		}
	}

	@Override
	public void verify() {
		byte[] echoed;
		try {
			echoed = services[0].echo(payload);
		} catch (RpcException e) {
			throw classify(e);
		}
		if (!Arrays.equals(payload, echoed)) {
			throw BenchCallException.payloadMismatch("echo did not return the payload unchanged");
		}
	}

	/** 超时 → {@code timeout}；triple 状态 → {@code tri_status:<CODE>}；其余 → {@code dubbo_rpc:<类别>}。 */
	static BenchCallException classify(RpcException e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof StatusRpcException status) {
				TriRpcStatus.Code code = status.getStatus().code;
				return code == TriRpcStatus.Code.DEADLINE_EXCEEDED ? BenchCallException.timeout(e.getMessage(), e)
						: new BenchCallException("tri_status:" + code.name(), e.getMessage(), e);
			}
		}
		if (e.isTimeout() || e.getCode() == RpcException.TIMEOUT_TERMINATE) {
			return BenchCallException.timeout(e.getMessage(), e);
		}
		int code = e.getCode();
		String name = code >= 0 && code < RPC_EXCEPTION_NAMES.size() ? RPC_EXCEPTION_NAMES.get(code) : String.valueOf(code);
		return new BenchCallException("dubbo_rpc:" + name, e.getMessage(), e);
	}

	@Override
	public Map<String, String> serverEnvironment() {
		try {
			return services[0].environment();
		} catch (RpcException e) {
			throw classify(e);
		}
	}

	@Override
	public Map<String, String> serverStats() {
		try {
			return services[0].stats();
		} catch (RpcException e) {
			throw classify(e);
		}
	}

	@Override
	public Map<String, Object> describe() {
		return description;
	}

	@Override
	public void close() {
		clients.forEach(Client::close);
	}
}
