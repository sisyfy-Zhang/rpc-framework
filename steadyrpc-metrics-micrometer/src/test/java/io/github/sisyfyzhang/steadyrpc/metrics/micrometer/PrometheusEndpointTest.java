package io.github.sisyfyzhang.steadyrpc.metrics.micrometer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import io.github.sisyfyzhang.steadyrpc.core.ServiceKey;
import io.github.sisyfyzhang.steadyrpc.core.filter.Invocation;
import io.github.sisyfyzhang.steadyrpc.core.filter.Result;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 内嵌抓取端点：起停、{@code GET /metrics} 的文本含服务端 Timer 的直方图桶、其它路径 404、关闭后端口不再接受连接；
 * registry 类型不对给出说明；classpath 上没有 micrometer-registry-prometheus 时给出"加哪个依赖"的异常，而不是 NoClassDefFoundError。
 */
class PrometheusEndpointTest {

	public interface Echo {
		String echo(String s);
	}

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	private HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
		return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10)).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void servesThePrometheusTextFormatOnMetricsAndStopsOnClose() throws Exception {
		MeterRegistry registry = PrometheusEndpoint.newRegistry();
		MicrometerServerFilter filter = new MicrometerServerFilter(registry);
		Invocation invocation = Invocation.inbound(ServiceKey.of(Echo.class), Echo.class.getMethod("echo", String.class),
				() -> new Object[] { "x" }, Map.of(), Duration.ZERO, System.nanoTime());
		filter.invoke(inv -> CompletableFuture.completedFuture(Result.value("x")), invocation).join();

		int port;
		try (PrometheusEndpoint endpoint = new PrometheusEndpoint("127.0.0.1", 0, registry)) {
			port = endpoint.port();
			assertTrue(port > 0);
			HttpResponse<String> metrics = get(port, "/metrics");
			assertEquals(200, metrics.statusCode());
			assertTrue(metrics.headers().firstValue("Content-Type").orElse("").startsWith("text/plain; version=0.0.4"),
					metrics.headers().toString());
			String body = metrics.body();
			assertTrue(body.contains("rpc_server_calls_seconds_bucket{"), body);
			assertTrue(body.contains("status=\"OK\""), body);
			assertTrue(body.contains("service=\"" + Echo.class.getName() + "\""), body);
			assertTrue(body.contains("rpc_server_calls_seconds_count{"), body);

			assertEquals(404, get(port, "/").statusCode());
			assertEquals(404, get(port, "/metricsx").statusCode());
			assertTrue(Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.getName().equals("rpc-metrics-http-" + port)));
		}
		assertThrows(IOException.class, () -> get(port, "/metrics"), "closed endpoint no longer listens");
		org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> Thread.getAllStackTraces().keySet().stream()
				.noneMatch(t -> t.getName().equals("rpc-metrics-http-" + port)));
	}

	@Test
	void acceptsACompositeContainingAPrometheusRegistry() throws Exception {
		CompositeMeterRegistry composite = new CompositeMeterRegistry();
		composite.add(new SimpleMeterRegistry());
		composite.add(PrometheusEndpoint.newRegistry());
		composite.counter("probe.counter").increment();
		try (PrometheusEndpoint endpoint = new PrometheusEndpoint("127.0.0.1", 0, composite)) {
			assertTrue(get(endpoint.port(), "/metrics").body().contains("probe_counter_total"));
		}
	}

	@Test
	void rejectsARegistryThatCannotBeScraped() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> new PrometheusEndpoint("127.0.0.1", 0, new SimpleMeterRegistry()));
		assertTrue(e.getMessage().contains("PrometheusMeterRegistry"), e.getMessage());
	}

	@Test
	void withoutThePrometheusRegistryOnTheClasspathTheErrorSaysWhichDependencyToAdd() throws Exception {
		ClassLoader hiding = new HidingClassLoader(getClass().getClassLoader());
		Class<?> endpoint = Class.forName(PrometheusEndpoint.class.getName(), true, hiding);
		assertFalse((Boolean) endpoint.getMethod("isAvailable").invoke(null));
		Method newRegistry = endpoint.getMethod("newRegistry");
		InvocationTargetException e = assertThrows(InvocationTargetException.class, () -> newRegistry.invoke(null));
		IllegalStateException cause = assertInstanceOf(IllegalStateException.class, e.getCause());
		assertTrue(cause.getMessage().contains("io.micrometer:micrometer-registry-prometheus"), cause.getMessage());

		Object simple = new SimpleMeterRegistry();
		InvocationTargetException ctor = assertThrows(InvocationTargetException.class, () -> endpoint
				.getConstructor(int.class, MeterRegistry.class).newInstance(0, simple));
		assertInstanceOf(IllegalStateException.class, ctor.getCause());
	}

	/** 隐藏 io.micrometer.prometheusmetrics 包，并自己定义本模块的类（让它们在"没有 Prometheus 依赖"的视图下链接）。 */
	static final class HidingClassLoader extends ClassLoader {
		HidingClassLoader(ClassLoader parent) {
			super(parent);
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			synchronized (getClassLoadingLock(name)) {
				if (name.startsWith("io.micrometer.prometheusmetrics.")) {
					throw new ClassNotFoundException(name + " (hidden by test)");
				}
				if (name.startsWith("io.github.sisyfyzhang.steadyrpc.metrics.micrometer.") && !name.contains("Test")) {
					Class<?> loaded = findLoadedClass(name);
					if (loaded == null) {
						try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
							if (in == null) {
								throw new ClassNotFoundException(name);
							}
							byte[] bytes = in.readAllBytes();
							loaded = defineClass(name, bytes, 0, bytes.length);
						} catch (IOException e) {
							throw new ClassNotFoundException(name, e);
						}
					}
					return loaded;
				}
				return super.loadClass(name, resolve);
			}
		}
	}
}
