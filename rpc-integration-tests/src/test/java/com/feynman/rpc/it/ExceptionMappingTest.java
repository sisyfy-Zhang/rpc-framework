package com.feynman.rpc.it;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.feynman.rpc.core.RemoteStackTrace;
import com.feynman.rpc.core.RpcClient;
import com.feynman.rpc.core.RpcDeadlineExceededException;
import com.feynman.rpc.core.RpcException;
import com.feynman.rpc.core.RpcRemoteException;
import com.feynman.rpc.core.RpcServer;
import com.feynman.rpc.core.config.ReferenceOptions;
import com.feynman.rpc.core.config.RpcServerConfig;
import com.feynman.rpc.core.config.ServiceOptions;
import com.feynman.rpc.core.protocol.Status;
import com.feynman.rpc.examples.api.InsufficientStockException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A0-5：服务端异常跨进程按类型还原。
 * <ul>
 *   <li>自定义业务异常 {@code InsufficientStockException(String)} → 客户端 catch 到同类型、message 一致、远端堆栈可取；</li>
 *   <li>服务端抛一个客户端 classpath 上不存在的异常类 → {@link RpcRemoteException}，className 与 message 保留。
 *       该类在测试运行时用 javax.tools 编译到临时目录、只由服务端实现通过独立的 URLClassLoader 加载——
 *       接口的类加载器与线程上下文类加载器都看不到它，等价于"只存在于服务端模块"；</li>
 *   <li>方法声明的受检异常跨进程原样抛出；未声明的受检异常包成 RpcException；</li>
 *   <li>关闭 remoteStackTrace 时不回传堆栈；开启时堆栈不超过 remoteStackTraceMaxBytes，超出截断并带标记；</li>
 *   <li>异常还原白名单：接口所在包内的业务异常默认还原为同类型；白名单外的包（这里是 rpc-examples 的 api 包，
 *       客户端明明加载得到）默认降级为 RpcRemoteException，配置 {@code exceptionAllowlist} 后才还原。</li>
 * </ul>
 * 每项对 Protostuff 与 fastjson2 各跑一遍（错误信封不经过序列化器，两者应完全一致）。
 */
class ExceptionMappingTest {

	/** 与接口同包（com.feynman.rpc.it）的未声明业务异常：默认白名单（接口所在包）内。 */
	public static class QuotaExceededException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		public QuotaExceededException(String message) {
			super(message);
		}
	}

	/** rpc-examples 的 api 包不在 OrderService 所在包里：默认白名单之外。 */
	private static final String EXAMPLES_API = "com.feynman.rpc.examples.api.";

	/** 声明的受检业务异常。 */
	public static class OrderRejectedException extends Exception {
		private static final long serialVersionUID = 1L;

		public OrderRejectedException(String message) {
			super(message);
		}
	}

	public interface OrderService {
		int reserve(long skuId, Integer quantity, String orderNo);

		void ship(String orderNo) throws OrderRejectedException;

		String warehouse(String sku);

		String undeclared(String reason);

		CompletableFuture<String> reserveAsync(long skuId);

		void consumeQuota(String tenant);

		void deep(int depth);

		String downstream(String sku);
	}

	static final class OrderServiceImpl implements OrderService {
		final AtomicInteger calls = new AtomicInteger();
		private final Class<?> serverOnlyException;

		OrderServiceImpl(Class<?> serverOnlyException) {
			this.serverOnlyException = serverOnlyException;
		}

		@Override
		public int reserve(long skuId, Integer quantity, String orderNo) {
			calls.incrementAndGet();
			throw new InsufficientStockException("sku " + skuId + " has 0 left, order " + orderNo + " wants " + quantity);
		}

		@Override
		public void ship(String orderNo) throws OrderRejectedException {
			throw new OrderRejectedException("order " + orderNo + " is not paid");
		}

		@Override
		public String warehouse(String sku) {
			try {
				throw (RuntimeException) serverOnlyException.getConstructor(String.class).newInstance("warehouse for " + sku + " is offline");
			} catch (ReflectiveOperationException e) {
				throw new AssertionError(e);
			}
		}

		@Override
		public String undeclared(String reason) {
			return sneakyThrow(new IOException(reason));
		}

		@Override
		public CompletableFuture<String> reserveAsync(long skuId) {
			return CompletableFuture.failedFuture(new InsufficientStockException("async sku " + skuId));
		}

		@Override
		public void consumeQuota(String tenant) {
			throw new QuotaExceededException("tenant " + tenant + " is over quota");
		}

		@Override
		public String downstream(String sku) {
			// 业务方法自己的下游调用超时（框架异常）原样上抛
			throw new RpcDeadlineExceededException("downstream inventory call for " + sku + " timed out");
		}

		@Override
		public void deep(int depth) {
			if (depth <= 0) {
				throw new QuotaExceededException("bottom of a deep stack");
			}
			deep(depth - 1);
		}

		@SuppressWarnings("unchecked")
		private static <T extends Throwable, R> R sneakyThrow(Throwable t) throws T {
			throw (T) t;
		}
	}

	private static final String SERVER_ONLY_CLASS = "com.acme.warehouse.WarehouseOfflineException";

	@TempDir
	static Path classes;

	private static ZkFixture zk;
	private static RpcServer server;
	private static URLClassLoader serverOnlyLoader;
	private static OrderServiceImpl impl;

	@BeforeAll
	static void start() throws Exception {
		serverOnlyLoader = compileServerOnlyException();
		Class<?> serverOnly = serverOnlyLoader.loadClass(SERVER_ONLY_CLASS);
		// 前提：客户端能用到的类加载器都加载不到这个类
		assertThrows(ClassNotFoundException.class, () -> Class.forName(SERVER_ONLY_CLASS, false, OrderService.class.getClassLoader()));
		assertThrows(ClassNotFoundException.class,
				() -> Class.forName(SERVER_ONLY_CLASS, false, Thread.currentThread().getContextClassLoader()));

		zk = new ZkFixture();
		impl = new OrderServiceImpl(serverOnly);
		server = RpcServer.builder().port(0).host("127.0.0.1").registry(zk.registry()).build();
		server.export(OrderService.class, impl);
		server.start();
		server.registered().orTimeout(10, TimeUnit.SECONDS).join(); // 注册在后台进行：等到可被发现再发请求
	}

	@AfterAll
	static void stop() throws Exception {
		server.close();
		zk.close();
		serverOnlyLoader.close();
	}

	private static URLClassLoader compileServerOnlyException() throws IOException {
		Path source = classes.resolve("src/com/acme/warehouse/WarehouseOfflineException.java");
		Files.createDirectories(source.getParent());
		Files.writeString(source, """
				package com.acme.warehouse;
				public class WarehouseOfflineException extends RuntimeException {
					public WarehouseOfflineException(String message) {
						super(message);
					}
				}
				""");
		Path out = classes.resolve("out");
		Files.createDirectories(out);
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		int status = compiler.run(null, null, null, "-d", out.toString(), source.toString());
		assertEquals(0, status, "failed to compile the server-only exception");
		// 父加载器为平台加载器：不委托给应用类路径，也不会被应用类路径看到
		return new URLClassLoader(new URL[] { out.toUri().toURL() }, ClassLoader.getPlatformClassLoader());
	}

	static Stream<Arguments> serializers() {
		return Stream.of(Arguments.of("protobuf"), Arguments.of("fastjson2"));
	}

	private static <T> T withClient(String serializer, java.util.function.Function<RpcClient, T> body) {
		return withClient(serializer, new String[0], body);
	}

	private static <T> T withClient(String serializer, String[] exceptionAllowlist, java.util.function.Function<RpcClient, T> body) {
		try (RpcClient client = RpcClient.builder().registry(zk.registry()).serializer(serializer)
				.cluster("failover").defaultTimeout(Duration.ofSeconds(10)).exceptionAllowlist(exceptionAllowlist).build()) {
			return body.apply(client);
		}
	}

	/** 允许还原 rpc-examples api 包里的业务异常的客户端。 */
	private static <T> T withExamplesAllowed(String serializer, java.util.function.Function<RpcClient, T> body) {
		return withClient(serializer, new String[] { EXAMPLES_API }, body);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void customBusinessExceptionIsRestoredAsTheSameType(String serializer) {
		withExamplesAllowed(serializer, client -> {
			OrderService orders = client.reference(OrderService.class, ReferenceOptions.defaults().retries(2));
			int before = impl.calls.get();
			InsufficientStockException e = assertThrows(InsufficientStockException.class, () -> orders.reserve(42L, 3, "o-1"));
			assertEquals("sku 42 has 0 left, order o-1 wants 3", e.getMessage());
			RemoteStackTrace remote = assertInstanceOf(RemoteStackTrace.class, e.getCause(), "remote stack is attached as the cause");
			assertTrue(remote.remoteStack().contains("OrderServiceImpl.reserve"), remote.remoteStack());
			assertTrue(remote.remoteStack().startsWith(InsufficientStockException.class.getName()), remote.remoteStack());
			assertEquals(1, impl.calls.get() - before, "a business exception is a result, never retried");
			return null;
		});
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void exceptionInTheInterfacePackageIsRestoredByDefault(String serializer) {
		withClient(serializer, client -> {
			OrderService orders = client.reference(OrderService.class);
			QuotaExceededException e = assertThrows(QuotaExceededException.class, () -> orders.consumeQuota("t-9"));
			assertEquals("tenant t-9 is over quota", e.getMessage());
			return null;
		});
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void exceptionOutsideTheAllowlistDegradesUntilItsPackageIsAllowed(String serializer) {
		withClient(serializer, client -> {
			OrderService orders = client.reference(OrderService.class);
			// 类在客户端 classpath 上、也有 (String) 构造器，但包不在白名单内：不加载、不实例化
			RpcRemoteException e = assertThrows(RpcRemoteException.class, () -> orders.reserve(7L, 1, "o-x"));
			assertEquals(InsufficientStockException.class.getName(), e.remoteClassName());
			assertEquals("sku 7 has 0 left, order o-x wants 1", e.remoteMessage());
			assertEquals(Status.BIZ_EXCEPTION, e.status());
			return null;
		});
		withExamplesAllowed(serializer, client -> {
			OrderService orders = client.reference(OrderService.class);
			assertThrows(InsufficientStockException.class, () -> orders.reserve(7L, 1, "o-x"));
			return null;
		});
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void frameworkExceptionsAreRestoredByDefault(String serializer) {
		withClient(serializer, client -> {
			OrderService orders = client.reference(OrderService.class);
			// com.feynman.rpc.core. 在默认白名单内：调用方按类型处理下游的框架异常，而不是拿到 RpcRemoteException
			RpcDeadlineExceededException e = assertThrows(RpcDeadlineExceededException.class, () -> orders.downstream("sku-3"));
			assertEquals("downstream inventory call for sku-3 timed out", e.getMessage());
			assertInstanceOf(RemoteStackTrace.class, e.getCause(), "remote stack is attached as the cause");
			return null;
		});
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void exceptionClassMissingOnClientDegradesToRpcRemoteException(String serializer) {
		withClient(serializer, client -> {
			OrderService orders = client.reference(OrderService.class);
			RpcRemoteException e = assertThrows(RpcRemoteException.class, () -> orders.warehouse("sku-9"));
			assertEquals(SERVER_ONLY_CLASS, e.remoteClassName());
			assertEquals("warehouse for sku-9 is offline", e.remoteMessage());
			assertTrue(e.getMessage().contains(SERVER_ONLY_CLASS) && e.getMessage().contains("offline"), e.getMessage());
			assertTrue(e.remoteStackTrace().contains("OrderServiceImpl.warehouse"), e.remoteStackTrace());
			return null;
		});
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void declaredCheckedExceptionIsThrownAsIs(String serializer) {
		withClient(serializer, client -> {
			OrderService orders = client.reference(OrderService.class);
			OrderRejectedException e = assertThrows(OrderRejectedException.class, () -> orders.ship("o-7"));
			assertEquals("order o-7 is not paid", e.getMessage());
			return null;
		});
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void undeclaredCheckedExceptionIsWrappedInRpcException(String serializer) {
		withClient(serializer, client -> {
			OrderService orders = client.reference(OrderService.class);
			RpcException e = assertThrows(RpcException.class, () -> orders.undeclared("disk full"));
			IOException cause = assertInstanceOf(IOException.class, e.getCause());
			assertEquals("disk full", cause.getMessage());
			return null;
		});
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void asyncMethodsCompleteExceptionallyWithTheRestoredException(String serializer) {
		withExamplesAllowed(serializer, client -> {
			OrderService orders = client.reference(OrderService.class);
			ExecutionException e = assertThrows(ExecutionException.class, () -> orders.reserveAsync(5L).get(10, TimeUnit.SECONDS));
			InsufficientStockException cause = assertInstanceOf(InsufficientStockException.class, e.getCause());
			assertEquals("async sku 5", cause.getMessage());
			return null;
		});
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void remoteStackTraceCanBeTurnedOff(String serializer) {
		try (RpcServer quiet = RpcServer.builder().port(0).host("127.0.0.1").registry(zk.registry()).remoteStackTrace(false).build()) {
			quiet.export(OrderService.class, impl, ServiceOptions.defaults().group("quiet"));
			quiet.start();
			quiet.registered().orTimeout(10, TimeUnit.SECONDS).join(); // 注册在后台进行：等到可被发现再发请求
			withExamplesAllowed(serializer, client -> {
				OrderService orders = client.reference(OrderService.class, ReferenceOptions.defaults().group("quiet"));
				InsufficientStockException e = assertThrows(InsufficientStockException.class, () -> orders.reserve(1L, 1, "o"));
				assertNull(e.getCause(), "no remote stack when the server disables it");
				assertEquals(List.of(), List.of(e.getSuppressed()));
				return null;
			});
		}
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("serializers")
	void remoteStackTraceIsCappedAtTheConfiguredBytes(String serializer) {
		try (RpcServer capped = RpcServer.builder().port(0).host("127.0.0.1").registry(zk.registry())
				.remoteStackTraceMaxBytes(512).build()) {
			capped.export(OrderService.class, impl, ServiceOptions.defaults().group("capped"));
			capped.start();
			capped.registered().orTimeout(10, TimeUnit.SECONDS).join();
			withClient(serializer, client -> {
				OrderService orders = client.reference(OrderService.class, ReferenceOptions.defaults().group("capped"));
				QuotaExceededException e = assertThrows(QuotaExceededException.class, () -> orders.deep(200));
				String stack = assertInstanceOf(RemoteStackTrace.class, e.getCause()).remoteStack();
				int bytes = stack.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
				System.out.printf("[remote-stack] 200-frame recursion, remoteStackTraceMaxBytes=512: received %d bytes, ends with %s%n",
						bytes, stack.substring(stack.length() - RpcServerConfig.TRUNCATION_MARKER.length()));
				assertTrue(bytes <= 512, bytes + " bytes");
				assertTrue(stack.endsWith(RpcServerConfig.TRUNCATION_MARKER), stack);
				assertTrue(stack.startsWith(QuotaExceededException.class.getName()), stack);
				return null;
			});
		}
	}
}
