package io.github.sisyfyzhang.steadyrpc.bench.grpc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.sisyfyzhang.steadyrpc.bench.BenchCallException;
import io.github.sisyfyzhang.steadyrpc.bench.CallTarget;
import io.github.sisyfyzhang.steadyrpc.bench.HarnessOptions;
import io.github.sisyfyzhang.steadyrpc.bench.Profile;
import io.github.sisyfyzhang.steadyrpc.bench.ServerProbes;
import io.github.sisyfyzhang.steadyrpc.bench.grpc.proto.BenchEchoGrpc;
import io.github.sisyfyzhang.steadyrpc.bench.grpc.proto.Bytes;
import io.github.sisyfyzhang.steadyrpc.bench.grpc.proto.Empty;
import com.google.protobuf.ByteString;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioSocketChannel;
import io.grpc.netty.shaded.io.netty.util.concurrent.DefaultThreadFactory;

/**
 * {@code transport: grpc}：缺省一个 {@link ManagedChannel}（一条 HTTP/2 连接，并发调用是这条连接上的多路复用流）+ 阻塞 stub；
 * 卡片写了 {@code target.connections: N} 时建 N 个 channel（N 条连接），每次调用轮询取一个（"连接数对齐"的对比卡用 2）。
 * 在调用方线程（闭环虚拟用户 / 开环工作者）上同步调用——与 rpc 卡的同步代理调用路径对称。
 *
 * <ul>
 *   <li><b>连接模型差异</b>：本框架每个提供者 2 条 TCP 连接（{@code connectionsPerNode} 缺省），请求按 requestId 多路复用；
 *       gRPC 是 1 个 channel、1 条 HTTP/2 连接、每次调用一个流。都用各自的缺省，差异写进 {@code run.target.connection_model}。</li>
 *   <li><b>线程</b>：客户端事件循环 = {@code --io-threads}（缺省 0 = Netty 缺省 2 × 可用核数，与 RpcClient 相同），传输显式用 NIO。
 *       阻塞 stub 的响应回调在调用方线程上执行（gRPC 的 ThreadlessExecutor），channel 的缺省执行器不参与。</li>
 *   <li><b>超时与重试</b>：每次调用 {@code withDeadlineAfter(load.timeout)}；{@code disableRetry()}（gRPC 缺省开着透明重试，
 *       与卡片"重试 0"的口径不符）。</li>
 *   <li><b>错误分桶</b>：{@code DEADLINE_EXCEEDED} 记 {@code timeout}（与 rpc 卡同一个桶），其余按 {@link Status.Code} 记
 *       {@code grpc_status:<CODE>}；回显长度不对记 {@code payload_mismatch}。</li>
 *   <li><b>负载</b>：{@link ServerProbes#payload(int)}（与 rpc / http 卡同一份字节），请求消息只构造一次、每次调用重新序列化
 *       （与 rpc 卡复用同一个 {@code byte[]}、每次调用重新序列化对称）。</li>
 * </ul>
 */
final class GrpcCallTarget implements CallTarget {

	/** environment / stats 的超时：一次性的元数据调用，与压测超时无关（同 RpcCallTarget）。 */
	static final long METADATA_TIMEOUT_SECONDS = 30;

	private final EventLoopGroup group;
	private final List<ManagedChannel> channels;
	private final BenchEchoGrpc.BenchEchoBlockingStub[] stubs;
	/** 多个 channel 时每次调用轮询取一个（{@code target.connections}，与本框架连接池的轮询取连接同一种做法）。 */
	private final AtomicInteger next = new AtomicInteger();
	private final Bytes request;
	private final byte[] payload;
	private final long timeoutNanos;
	private final Map<String, Object> description;

	private GrpcCallTarget(EventLoopGroup group, List<ManagedChannel> channels, byte[] payload, long timeoutNanos,
			Map<String, Object> description) {
		this.group = group;
		this.channels = channels;
		this.stubs = channels.stream().map(BenchEchoGrpc::newBlockingStub).toArray(BenchEchoGrpc.BenchEchoBlockingStub[]::new);
		this.payload = payload;
		this.request = Bytes.newBuilder().setPayload(ByteString.copyFrom(payload)).build();
		this.timeoutNanos = timeoutNanos;
		this.description = description;
	}

	static GrpcCallTarget connect(Profile profile, CallTarget.Endpoint endpoint, HarnessOptions options) {
		List<String> addresses = endpoint.directAddresses();
		if (addresses.size() != 1 || profile.target().providers() != 1) {
			throw new IllegalArgumentException("transport: grpc takes exactly one provider (target.providers = 1, --direct host:port), got "
					+ addresses + " with target.providers = " + profile.target().providers());
		}
		if (!"bytes".equals(profile.target().payloadKind())) {
			throw new IllegalArgumentException("transport: grpc only implements BenchService#echo with payload.kind = bytes");
		}
		String address = addresses.get(0);
		int colon = address.lastIndexOf(':');
		String host = address.substring(0, colon);
		int port = Integer.parseInt(address.substring(colon + 1));
		int ioThreads = options.ioThreads() == null ? 0 : options.ioThreads();
		// target.connections = ManagedChannel 数（每个 channel 一条 HTTP/2 连接，分到不同的事件循环上）；缺省 1 = gRPC 的常规用法
		int channelCount = profile.target().connections() == null ? 1 : profile.target().connections();
		EventLoopGroup group = new NioEventLoopGroup(ioThreads, new DefaultThreadFactory("grpc-client-io", true));
		List<ManagedChannel> channels = new ArrayList<>();
		try {
			for (int i = 0; i < channelCount; i++) {
				channels.add(NettyChannelBuilder.forAddress(host, port)
						.usePlaintext()
						.eventLoopGroup(group)
						.channelType(NioSocketChannel.class)
						.disableRetry()
						.maxInboundMessageSize(GrpcBenchServer.MAX_MESSAGE_BYTES)
						.build());
			}
		} catch (RuntimeException e) {
			channels.forEach(ManagedChannel::shutdownNow);
			group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
			throw e;
		}
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("transport", "grpc");
		d.put("mode", endpoint.mode());
		d.put("address", endpoint.describe());
		d.put("providers", addresses);
		d.put("serializer", "protobuf (generated message: bytes payload = 1)");
		d.put("load_balance", "pick_first (single address)");
		d.put("connections_per_node", channelCount);
		d.put("connection_model", channelCount == 1
				? "1 ManagedChannel = 1 HTTP/2 connection; concurrent calls are multiplexed streams (rpc cards: 2 TCP connections per provider)"
				: channelCount + " ManagedChannels = " + channelCount + " HTTP/2 connections, calls take a channel round-robin; concurrent calls are multiplexed streams");
		d.put("connections_meaning", "target.connections = number of ManagedChannels to the provider (default 1)");
		d.put("connections_source", profile.target().connections() == null ? "default" : "target.connections");
		d.put("io_transport", "nio (grpc-netty-shaded)");
		d.put("io_threads", GrpcBenchServer.ioThreadsText(group, ioThreads));
		d.put("retries", 0);
		d.put("retry_policy", "disableRetry()");
		d.put("call_style", "blocking stub on the caller thread (ThreadlessExecutor), withDeadlineAfter(load.timeout) per call");
		d.put("grpc_version", GrpcBenchServer.grpcVersion());
		d.put("max_inbound_message_bytes", GrpcBenchServer.MAX_MESSAGE_BYTES);
		byte[] payload = ServerProbes.payload(profile.target().payloadSize());
		return new GrpcCallTarget(group, List.copyOf(channels), payload, profile.load().timeout().toNanos(), d);
	}

	@Override
	public void call() {
		Bytes echoed;
		try {
			echoed = stub().withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).echo(request);
		} catch (StatusRuntimeException e) {
			throw classify(e);
		}
		if (echoed.getPayload().size() != payload.length) {
			throw BenchCallException.payloadMismatch("echo returned " + echoed.getPayload().size() + " bytes, sent " + payload.length);
		}
	}

	@Override
	public void verify() {
		Bytes echoed;
		try {
			echoed = stubs[0].withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).echo(request);
		} catch (StatusRuntimeException e) {
			throw classify(e);
		}
		if (!Arrays.equals(payload, echoed.getPayload().toByteArray())) {
			throw BenchCallException.payloadMismatch("echo did not return the payload unchanged");
		}
	}

	/** 本次调用用哪个 channel 的 stub：只有一个时不碰计数器。 */
	private BenchEchoGrpc.BenchEchoBlockingStub stub() {
		return stubs.length == 1 ? stubs[0] : stubs[Math.floorMod(next.getAndIncrement(), stubs.length)];
	}

	/** {@code DEADLINE_EXCEEDED} → {@code timeout}；其余 → {@code grpc_status:<CODE>}。 */
	static BenchCallException classify(StatusRuntimeException e) {
		Status.Code code = e.getStatus().getCode();
		if (code == Status.Code.DEADLINE_EXCEEDED) {
			return BenchCallException.timeout(e.getMessage(), e);
		}
		return new BenchCallException("grpc_status:" + code.name(), e.getMessage(), e);
	}

	@Override
	public Map<String, String> serverEnvironment() {
		Map<String, String> environment;
		try {
			environment = stubs[0].withDeadlineAfter(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS).environment(Empty.getDefaultInstance()).getValuesMap();
		} catch (StatusRuntimeException e) {
			throw classify(e);
		}
		// 服务端执行器决定业务回调是否离开 IO 线程，对结果影响大：同时记进 run.target（harness 在开压前取一次服务端环境，JSON 最后才写出）
		if (environment.get("server_executor") != null) {
			description.put("server_executor", environment.get("server_executor"));
		}
		return environment;
	}

	@Override
	public Map<String, String> serverStats() {
		try {
			return stubs[0].withDeadlineAfter(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS).stats(Empty.getDefaultInstance()).getValuesMap();
		} catch (StatusRuntimeException e) {
			throw classify(e);
		}
	}

	@Override
	public Map<String, Object> describe() {
		return description;
	}

	@Override
	public void close() {
		channels.forEach(ManagedChannel::shutdownNow);
		try {
			for (ManagedChannel channel : channels) {
				channel.awaitTermination(5, TimeUnit.SECONDS);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
	}
}
