package io.github.sisyfyzhang.steadyrpc.starter;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import io.github.sisyfyzhang.steadyrpc.core.RpcServer;

/**
 * 把 RpcServer 的启停挂到容器生命周期上，替代旧实现的 ContextRefreshedEvent 监听：
 * {@link #start()} 调用 {@code RpcServer.start()}，绑定端口后立即返回（注册在后台进行），容器的 refresh 与
 * {@code SpringApplication.run()} 都能正常结束；停止调用 {@code close()}，即完整的优雅下线：
 * 取消注册重试 → 摘注册 → 宽限期（{@code rpc.server.deregister-grace}，默认 0）→ 拒新请求（SERVER_OVERLOADED
 * "server shutting down"）→ 等在途（上限 {@code rpc.server.shutdown-timeout}，默认 10s）→ 关端口与连接 → 关线程组 → 关注册中心。
 * {@code ConfigurableApplicationContext.close()} 与 JVM 关闭钩子（SIGTERM）都走这条路径。
 *
 * <p><b>异步停止</b>：容器调用的是 {@link #stop(Runnable)}——在独立线程 {@code rpc-server-shutdown} 上执行 close，
 * 完成后回调。为什么不在容器的停机线程上同步 close：同一 phase 的其他 SmartLifecycle（例如应用自己的消息监听容器）
 * 会被串行地排在 RPC 的几秒排空之后；异步之后它们并行排空，容器在该 phase 上统一等待全部回调。
 * <b>最长阻塞时长</b> ≈ {@code deregister-grace + shutdown-timeout} + 约 5 秒（等注册线程退出、关 IO 线程组与注册中心会话），
 * 默认配置下约 15 秒。容器对每个 phase 的等待有上限：Spring Boot 的 {@code spring.lifecycle.timeout-per-shutdown-phase}
 * 默认 30s，纯 Spring 的 {@code DefaultLifecycleProcessor} 默认 10s。上限必须大于上面的最长阻塞时长，否则容器会在
 * 排空完成前继续销毁 bean、进程可能提前退出；调大 shutdown-timeout 或 deregister-grace 时要同步调大它
 * （Kubernetes 的 terminationGracePeriodSeconds 同理）。
 *
 * <p>启动刻意不等待注册完成：注册中心不可达时，应用照样启动、端口照样服务直连请求，后台无限退避重试。
 * "是否已可被发现"是另一个问题，由 {@link #registered()} / {@link #isRegistered()} 回答；classpath 上有 Actuator 时，
 * {@link RpcServerHealthIndicator} 会把它接进 health 端点。
 *
 * <p>phase 取 {@code DEFAULT_PHASE - 512}：高于 Boot Web 服务器的优雅停机阶段（DEFAULT_PHASE - 1024），
 * 所以启动时在它们之后对外暴露、停机时最先摘注册；又给用户自己"必须更早停"的组件留出更高的 phase。
 * 容器里没有任何 {@code @RpcService} 时不启动，纯消费端应用不会绑定端口。
 */
public class RpcServerLifecycle implements SmartLifecycle {

	public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 512;
	/** 异步 close 所在线程的名字（rpc- 前缀，线程泄漏断言会覆盖到它；close 结束即退出）。 */
	static final String SHUTDOWN_THREAD_NAME = "rpc-server-shutdown";
	private static final Logger log = LoggerFactory.getLogger(RpcServerLifecycle.class);

	private final RpcServer server;
	private volatile boolean running;

	public RpcServerLifecycle(RpcServer server) {
		this.server = server;
	}

	@Override
	public void start() {
		if (server.exportedServices().isEmpty()) {
			log.debug("No @RpcService beans, RPC server not started");
			return;
		}
		server.start();
		running = true;
	}

	/** 同步停止：直接在调用线程上 close。容器的正常停机路径走的是 {@link #stop(Runnable)}。 */
	@Override
	public void stop() {
		if (running) {
			try {
				server.close();
			} finally {
				running = false;
			}
		}
	}

	/**
	 * 异步停止：在 {@value #SHUTDOWN_THREAD_NAME} 线程上执行 close，结束（含异常）后调用 {@code callback}。
	 * 线程不是守护线程：容器在 main 线程里 close 时，不会因为 main 结束而让排空中途被截断。
	 */
	@Override
	public void stop(Runnable callback) {
		if (!running) {
			callback.run();
			return;
		}
		Thread shutdown = new Thread(() -> {
			try {
				server.close();
			} catch (RuntimeException e) {
				log.warn("RpcServer close failed", e);
			} finally {
				running = false;
				callback.run();
			}
		}, SHUTDOWN_THREAD_NAME);
		shutdown.start();
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	/**
	 * 全部 {@code @RpcService} 都已在注册中心注册成功时完成；语义见 {@link RpcServer#registered()}。
	 * 没有任何 {@code @RpcService}（服务端未启动）时返回一个已完成的 future：没有需要被发现的东西。
	 */
	public CompletableFuture<Void> registered() {
		if (server.exportedServices().isEmpty()) {
			return CompletableFuture.completedFuture(null);
		}
		return server.registered();
	}

	/** 当前是否全部已注册（适合 readiness 探针轮询）。没有任何 {@code @RpcService} 时为 true。 */
	public boolean isRegistered() {
		return server.exportedServices().isEmpty() || server.isRegistered();
	}

	@Override
	public int getPhase() {
		return PHASE;
	}
}
