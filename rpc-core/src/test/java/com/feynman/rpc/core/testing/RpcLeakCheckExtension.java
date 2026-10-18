package com.feynman.rpc.core.testing;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import com.feynman.rpc.core.RpcClient;
import com.feynman.rpc.core.RpcServer;

/**
 * 泄漏断言：每个测试类结束后（{@code @AfterAll} 方法之后），名字以 {@value #THREAD_PREFIX} 开头的存活线程必须在
 * {@link #DEFAULT_GRACE} 内归零；测试类的字段（静态字段，或 PER_CLASS 生命周期下的实例字段）里若持有
 * {@link RpcClient} / {@link RpcServer}，它们的在途计数也必须为零。
 *
 * <p>为什么做成自动注册的扩展而不是每个测试自己断言：框架的所有线程（IO、业务、注册中心、停机）都带 {@code rpc-} 前缀，
 * "关完不留线程"是 RpcClient / RpcServer 的契约；靠每个测试记得写一行断言，漏写的那个就是会泄漏的那个。
 * 通过 {@code META-INF/services/org.junit.jupiter.api.extension.Extension} + {@code junit-platform.properties} 里的
 * {@code junit.jupiter.extensions.autodetection.enabled=true}，它对 rpc-core 与 rpc-integration-tests 的每个测试类生效
 * （后者经 rpc-core 的 test-jar 引入，只打包本包与服务文件）。
 *
 * <p>为什么按类而不是按方法检查：不少测试类在 {@code @BeforeAll} 起一个共享服务端、{@code @AfterAll} 关闭，
 * 方法之间线程本来就该活着。{@code @Nested} 类跳过检查，由最外层类结束时统一检查（外层可能仍持有共享资源）。
 *
 * <p>为什么等 5 秒而不是立即断言：Netty 的 {@code shutdownGracefully} 有静默期，Curator / ZooKeeper 的线程在 close 返回后
 * 还要几十到几百毫秒才真正退出；5 秒内归零即视为没有泄漏，超时就失败并列出残留线程与栈顶。
 */
public final class RpcLeakCheckExtension implements AfterAllCallback {

	public static final String THREAD_PREFIX = "rpc-";
	public static final Duration DEFAULT_GRACE = Duration.ofSeconds(5);

	@Override
	public void afterAll(ExtensionContext context) throws Exception {
		Optional<Class<?>> testClass = context.getTestClass();
		if (testClass.isPresent() && testClass.get().isAnnotationPresent(Nested.class)) {
			return;
		}
		List<String> problems = new ArrayList<>();
		testClass.ifPresent(type -> problems.addAll(inFlightProblems(type, context.getTestInstance().orElse(null))));
		Map<String, String> leaked = awaitNoRpcThreads(DEFAULT_GRACE);
		if (!leaked.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			sb.append(leaked.size()).append(" '").append(THREAD_PREFIX).append("' thread(s) still alive ")
					.append(DEFAULT_GRACE.toMillis()).append("ms after the test class finished (unclosed RpcClient / RpcServer / Registry?):");
			leaked.forEach((name, top) -> sb.append("\n  ").append(name).append(" @ ").append(top));
			problems.add(sb.toString());
		}
		if (!problems.isEmpty()) {
			throw new AssertionError("[RpcLeakCheck] " + testClass.map(Class::getName).orElse("?") + ": " + String.join("\n", problems));
		}
	}

	/**
	 * 等到没有 rpc- 线程存活或超时；返回超时时仍存活的线程名 → 栈顶帧（按名字排序）。没有残留时立即返回空表。
	 * 公开给自测与需要在方法级别断言的测试使用。
	 */
	public static Map<String, String> awaitNoRpcThreads(Duration grace) throws InterruptedException {
		long deadline = System.nanoTime() + grace.toNanos();
		while (true) {
			Map<String, String> alive = rpcThreads();
			if (alive.isEmpty() || System.nanoTime() - deadline >= 0) {
				return alive;
			}
			Thread.sleep(20);
		}
	}

	/** 当前存活的 rpc- 线程名 → 栈顶帧。 */
	public static Map<String, String> rpcThreads() {
		Map<String, String> alive = new TreeMap<>();
		Thread.getAllStackTraces().forEach((thread, stack) -> {
			if (thread.isAlive() && thread.getName().startsWith(THREAD_PREFIX)) {
				alive.put(thread.getName(), stack.length > 0 ? stack[0].toString() : "(no frames)");
			}
		});
		return alive;
	}

	/** 测试类字段里持有的 RpcClient / RpcServer 在途计数不为零的描述。字段为 null 或无法访问时跳过。 */
	static List<String> inFlightProblems(Class<?> testClass, Object instance) {
		List<String> problems = new ArrayList<>();
		for (Class<?> type = testClass; type != null && type != Object.class; type = type.getSuperclass()) {
			for (Field field : type.getDeclaredFields()) {
				boolean isStatic = Modifier.isStatic(field.getModifiers());
				if (!isStatic && instance == null) {
					continue;
				}
				Object value;
				try {
					field.setAccessible(true);
					value = field.get(isStatic ? null : instance);
				} catch (ReflectiveOperationException | RuntimeException e) {
					continue;
				}
				String where = type.getSimpleName() + "." + field.getName();
				if (value instanceof RpcClient client) {
					if (client.inFlightCalls() != 0 || client.pendingRequests() != 0) {
						problems.add(where + ": RpcClient has " + client.inFlightCalls() + " in-flight call(s) and "
								+ client.pendingRequests() + " pending request(s)");
					}
				} else if (value instanceof RpcServer server) {
					if (server.inFlight() != 0) {
						problems.add(where + ": RpcServer has " + server.inFlight() + " in-flight request(s)");
					}
				}
			}
		}
		return problems;
	}
}
