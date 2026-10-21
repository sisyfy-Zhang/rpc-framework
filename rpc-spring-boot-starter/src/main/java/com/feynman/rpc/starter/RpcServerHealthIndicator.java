package com.feynman.rpc.starter;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

import com.feynman.rpc.core.RpcServer;

/**
 * 把 RpcServer 的状态接进 Actuator 的 health 端点（组件名 {@code rpc}），只在 classpath 上有 Actuator 时装配，
 * 可用 {@code management.health.rpc.enabled=false} 关闭。
 *
 * <ul>
 *   <li><b>UP</b>：已启动（STARTED）且全部服务已在注册中心注册成功——"能服务、也能被发现"；</li>
 *   <li><b>OUT_OF_SERVICE</b>：已启动但尚未注册成功（注册中心不可达，后台在重试；端口能服务直连请求但消费端找不到它），
 *       或注册过之后节点丢失、尚未重建（ZooKeeper 会话过期；依赖注册中心实现报告丢失，Nacos 不报告），
 *       或正在停机（CLOSING / DRAINING：已摘注册，不该再接新流量）；</li>
 *   <li><b>DOWN</b>：其余情况（NEW：有服务要暴露却没有启动；CLOSED）。</li>
 *   <li>容器里没有任何 {@code @RpcService}（纯消费端应用，服务端按设计不启动）时报告 UP 并注明，
 *       否则一个只做消费端的应用会因为"服务端没启动"而永远不健康。</li>
 * </ul>
 * 为什么"未注册"不是 DOWN：进程本身是好的，重启解决不了注册中心不可达；DOWN 常被 liveness 探针用来决定重启，
 * OUT_OF_SERVICE 更适合接到 readiness 上（把 {@code rpc} 加进 readiness 组即可）。
 */
public class RpcServerHealthIndicator implements HealthIndicator {

	private final RpcServer server;

	public RpcServerHealthIndicator(RpcServer server) {
		this.server = server;
	}

	@Override
	public Health health() {
		if (server.exportedServices().isEmpty()) {
			return Health.up().withDetail("server", "not started (no @RpcService beans)").build();
		}
		RpcServer.State state = server.state();
		boolean registered = server.isRegistered();
		Status status = switch (state) {
			case STARTED -> registered ? Status.UP : Status.OUT_OF_SERVICE;
			case CLOSING, DRAINING -> Status.OUT_OF_SERVICE;
			case NEW, CLOSED -> Status.DOWN;
		};
		return Health.status(status)
				.withDetail("state", state.name())
				.withDetail("registered", registered)
				.withDetail("port", server.port())
				.withDetail("inFlight", server.inFlight())
				.withDetail("services", server.exportedServices().stream().map(Object::toString).toList())
				.build();
	}
}
