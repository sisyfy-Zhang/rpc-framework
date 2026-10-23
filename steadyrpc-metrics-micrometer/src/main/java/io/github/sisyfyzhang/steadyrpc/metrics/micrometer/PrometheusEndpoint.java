package io.github.sisyfyzhang.steadyrpc.metrics.micrometer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 纯 Java 进程内嵌的 Prometheus 抓取端点：JDK 自带的 {@code com.sun.net.httpserver.HttpServer} 在 {@code GET /metrics}
 * 上返回 registry 的 Prometheus 文本格式（0.0.4），不引入任何 Web 框架。Spring Boot 应用用 Actuator 的
 * {@code /actuator/prometheus} 即可，不需要它。
 *
 * <pre>{@code
 * MeterRegistry registry = PrometheusEndpoint.newRegistry();
 * RpcServer server = RpcServer.builder().serverFilter(new MicrometerServerFilter(registry)).build();
 * RpcServerMetrics.bind(server, registry);
 * PrometheusEndpoint endpoint = new PrometheusEndpoint(9464, registry);   // http://<host>:9464/metrics
 * }</pre>
 *
 * <p><b>需要 {@code io.micrometer:micrometer-registry-prometheus}</b>：本模块对它是 optional 依赖，用到本类的进程要自己声明。
 * 缺失时构造与 {@link #newRegistry()} 抛 {@link IllegalStateException} 并说明要加哪个依赖，而不是 NoClassDefFoundError。
 * 本类的公开签名只用 micrometer-core 的类型，所以没有该依赖时本类照样能加载、能被引用。
 *
 * <p>传入的 registry 必须是 {@code PrometheusMeterRegistry}，或包含它的 {@code CompositeMeterRegistry}。缺省监听全部网卡
 * （容器里的 Prometheus 经 {@code host.docker.internal} 访问宿主机）；只想本机访问时用 {@link #PrometheusEndpoint(String, int, MeterRegistry)}
 * 传 {@code "127.0.0.1"}。处理请求的是一个守护线程 {@code rpc-metrics-http-<port>}，{@link #close()} 停止监听并结束它。
 */
public final class PrometheusEndpoint implements AutoCloseable {

	/** micrometer-registry-prometheus（1.13 起的新客户端）里的 registry 类。 */
	public static final String PROMETHEUS_REGISTRY_CLASS = "io.micrometer.prometheusmetrics.PrometheusMeterRegistry";
	public static final String PATH = "/metrics";
	static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

	private final HttpServer server;
	private final ExecutorService executor;
	private final int port;

	/** 监听全部网卡的 {@code port}（0 = 系统分配，实际端口见 {@link #port()}）。 */
	public PrometheusEndpoint(int port, MeterRegistry registry) {
		this(null, port, registry);
	}

	/**
	 * @param host 监听地址；null 表示全部网卡
	 * @throws IllegalStateException    classpath 上没有 micrometer-registry-prometheus
	 * @throws IllegalArgumentException registry 不是（也不包含）PrometheusMeterRegistry
	 * @throws UncheckedIOException     端口绑定失败
	 */
	public PrometheusEndpoint(String host, int port, MeterRegistry registry) {
		requireAvailable();
		if (registry == null) {
			throw new IllegalArgumentException("registry must not be null");
		}
		Supplier<String> scraper = PrometheusSupport.scraper(registry);
		InetSocketAddress address = host == null ? new InetSocketAddress(port) : new InetSocketAddress(host, port);
		try {
			this.server = HttpServer.create(address, 0);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to bind Prometheus endpoint on " + address, e);
		}
		this.port = server.getAddress().getPort();
		this.executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "rpc-metrics-http-" + this.port);
			thread.setDaemon(true);
			return thread;
		});
		server.setExecutor(executor);
		server.createContext("/", exchange -> handle(exchange, scraper));
		server.start();
	}

	/** classpath 上是否有 micrometer-registry-prometheus。 */
	public static boolean isAvailable() {
		try {
			Class.forName(PROMETHEUS_REGISTRY_CLASS, false, PrometheusEndpoint.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}

	/**
	 * 新建一个缺省配置的 {@code PrometheusMeterRegistry}（以 {@link MeterRegistry} 类型返回，调用方不必编译期依赖 Prometheus 类型）。
	 *
	 * @throws IllegalStateException classpath 上没有 micrometer-registry-prometheus
	 */
	public static MeterRegistry newRegistry() {
		requireAvailable();
		return PrometheusSupport.newRegistry();
	}

	/** 实际监听的端口。 */
	public int port() {
		return port;
	}

	/** 停止监听（不等待进行中的请求）并结束处理线程。可重复调用。 */
	@Override
	public void close() {
		server.stop(0);
		executor.shutdownNow();
	}

	private static void handle(HttpExchange exchange, Supplier<String> scraper) throws IOException {
		try (exchange) {
			String method = exchange.getRequestMethod();
			if (!PATH.equals(exchange.getRequestURI().getPath())) {
				reply(exchange, 404, "text/plain; charset=utf-8", "not found; metrics are at " + PATH + "\n", true);
				return;
			}
			if (!"GET".equals(method) && !"HEAD".equals(method)) {
				exchange.getResponseHeaders().set("Allow", "GET, HEAD");
				reply(exchange, 405, "text/plain; charset=utf-8", "method not allowed\n", true);
				return;
			}
			reply(exchange, 200, CONTENT_TYPE, scraper.get(), "GET".equals(method));
		}
	}

	private static void reply(HttpExchange exchange, int status, String contentType, String body, boolean withBody) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", contentType);
		if (!withBody) {
			exchange.sendResponseHeaders(status, -1);
			return;
		}
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static void requireAvailable() {
		if (!isAvailable()) {
			throw new IllegalStateException("PrometheusEndpoint needs io.micrometer:micrometer-registry-prometheus on the classpath "
					+ "(class " + PROMETHEUS_REGISTRY_CLASS + " not found). steadyrpc-metrics-micrometer declares it as an optional "
					+ "dependency: add it to the application that embeds the /metrics endpoint.");
		}
	}
}
