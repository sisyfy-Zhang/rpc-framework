package com.feynman.rpc.bench;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * {@code bench server --http-port <p>}：与 RPC 端点同进程、独立端口的 HTTP/1.1 对照端点，供 harness 交叉验证
 * （同一个目标分别用 harness 的 {@code transport: http} 与 wrk / wrk2 打，比较两个发压工具测出的数字）。
 *
 * <ul>
 *   <li>{@code POST /echo}：原样返回请求体（{@code Content-Type: application/octet-stream}），回包直接复用请求的
 *       ByteBuf（零拷贝）；</li>
 *   <li>{@code GET /ping}：返回 {@code ok}；</li>
 *   <li>{@code GET /environment}：服务端环境（与 RPC 端点 {@code BenchService.environment()} 同一份采集），JSON 对象、
 *       值全是字符串，供 harness 写进报告的 {@code server_environment}；</li>
 *   <li>{@code GET /stats}：此刻的累计 GC 次数 / 停顿时间与堆用量（与 RPC 端点 {@code BenchService.stats()} 同一份），
 *       JSON 对象、值全是字符串，harness 每轮统计窗口起止各取一次，差值进 {@code rounds[].gc.server}；</li>
 *   <li>其它路径 404，方法不对 405。</li>
 * </ul>
 * keep-alive（HTTP/1.1 缺省；请求带 {@code Connection: close} 或 HTTP/1.0 不带 keep-alive 时回包后关闭）；
 * {@link HttpServerCodec} + {@link HttpObjectAggregator}（上限 8MiB）。业务在 IO 线程上完成（回显没有阻塞），
 * 线程模型：1 个 boss + {@code 2 × 可用核数} 个 worker（Netty 缺省，与 RpcServer 的 IO 线程数同一规则）。
 */
final class BenchHttpServer implements AutoCloseable {

	static final int MAX_CONTENT_BYTES = 8 * 1024 * 1024;
	static final String ECHO_PATH = "/echo";
	static final String PING_PATH = "/ping";
	static final String ENVIRONMENT_PATH = "/environment";
	static final String STATS_PATH = "/stats";

	private final EventLoopGroup boss;
	private final EventLoopGroup workers;
	private final Channel channel;

	private BenchHttpServer(EventLoopGroup boss, EventLoopGroup workers, Channel channel) {
		this.boss = boss;
		this.workers = workers;
		this.channel = channel;
	}

	/**
	 * 绑定 {@code 0.0.0.0:port}（0 = 随机端口）。
	 *
	 * @param environment {@code GET /environment} 的内容（启动时采集一次）
	 */
	static BenchHttpServer start(int port, Map<String, String> environment) throws InterruptedException {
		byte[] environmentJson = Json.write(new LinkedHashMap<String, Object>(environment)).getBytes(StandardCharsets.UTF_8);
		EventLoopGroup boss = new NioEventLoopGroup(1, new DefaultThreadFactory("bench-http-boss"));
		EventLoopGroup workers = new NioEventLoopGroup(0, new DefaultThreadFactory("bench-http-worker"));
		try {
			Channel channel = new ServerBootstrap()
					.group(boss, workers)
					.channel(NioServerSocketChannel.class)
					.option(ChannelOption.SO_BACKLOG, 1024)
					.childOption(ChannelOption.TCP_NODELAY, true)
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						protected void initChannel(SocketChannel ch) {
							ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(MAX_CONTENT_BYTES),
									new Handler(environmentJson));
						}
					})
					.bind(port).sync().channel();
			return new BenchHttpServer(boss, workers, channel);
		} catch (InterruptedException | RuntimeException e) {
			boss.shutdownGracefully(0, 0, java.util.concurrent.TimeUnit.SECONDS);
			workers.shutdownGracefully(0, 0, java.util.concurrent.TimeUnit.SECONDS);
			throw e;
		}
	}

	int port() {
		return ((java.net.InetSocketAddress) channel.localAddress()).getPort();
	}

	@Override
	public void close() {
		channel.close().syncUninterruptibly();
		boss.shutdownGracefully(0, 2, java.util.concurrent.TimeUnit.SECONDS).syncUninterruptibly();
		workers.shutdownGracefully(0, 2, java.util.concurrent.TimeUnit.SECONDS).syncUninterruptibly();
	}

	private static final class Handler extends SimpleChannelInboundHandler<FullHttpRequest> {

		private final byte[] environmentJson;

		Handler(byte[] environmentJson) {
			super(true); // 方法返回后释放请求；回显时先 retain 一次内容，由回包写出后释放
			this.environmentJson = environmentJson;
		}

		@Override
		protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
			if (!request.decoderResult().isSuccess()) {
				respond(ctx, request, HttpResponseStatus.BAD_REQUEST, Unpooled.EMPTY_BUFFER, null);
				return;
			}
			String uri = request.uri();
			int query = uri.indexOf('?');
			String path = query >= 0 ? uri.substring(0, query) : uri;
			HttpMethod method = request.method();
			switch (path) {
				case ECHO_PATH -> {
					if (HttpMethod.POST.equals(method)) {
						respond(ctx, request, HttpResponseStatus.OK, request.content().retain(), HttpHeaderValues.APPLICATION_OCTET_STREAM);
					} else {
						respond(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED, Unpooled.EMPTY_BUFFER, null);
					}
				}
				case PING_PATH -> {
					if (HttpMethod.GET.equals(method)) {
						respond(ctx, request, HttpResponseStatus.OK, Unpooled.wrappedBuffer(new byte[] { 'o', 'k' }),
								HttpHeaderValues.TEXT_PLAIN);
					} else {
						respond(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED, Unpooled.EMPTY_BUFFER, null);
					}
				}
				case ENVIRONMENT_PATH -> {
					if (HttpMethod.GET.equals(method)) {
						respond(ctx, request, HttpResponseStatus.OK, Unpooled.wrappedBuffer(environmentJson),
								HttpHeaderValues.APPLICATION_JSON);
					} else {
						respond(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED, Unpooled.EMPTY_BUFFER, null);
					}
				}
				case STATS_PATH -> {
					if (HttpMethod.GET.equals(method)) {
						byte[] stats = Json.write(new LinkedHashMap<String, Object>(ServerProbes.stats()))
								.getBytes(StandardCharsets.UTF_8);
						respond(ctx, request, HttpResponseStatus.OK, Unpooled.wrappedBuffer(stats), HttpHeaderValues.APPLICATION_JSON);
					} else {
						respond(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED, Unpooled.EMPTY_BUFFER, null);
					}
				}
				default -> respond(ctx, request, HttpResponseStatus.NOT_FOUND, Unpooled.EMPTY_BUFFER, null);
			}
		}

		private static void respond(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status, ByteBuf body,
				CharSequence contentType) {
			FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, body);
			if (contentType != null) {
				response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
			}
			HttpUtil.setContentLength(response, body.readableBytes());
			boolean keepAlive = HttpUtil.isKeepAlive(request) && request.decoderResult().isSuccess();
			if (keepAlive) {
				if (!request.protocolVersion().isKeepAliveDefault()) {
					response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
				}
				ctx.writeAndFlush(response, ctx.voidPromise());
			} else {
				response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
				ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
			}
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			ctx.close();
		}
	}
}
