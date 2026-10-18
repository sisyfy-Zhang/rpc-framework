package com.feynman.rpc.examples.provider;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * 服务提供方示例。{@code @RpcService} 标注的实现被 starter 暴露；RPC 服务端在容器启动完成后由
 * SmartLifecycle 非阻塞地启动，{@code run()} 正常返回，进程由 Netty 的非守护线程保活，
 * 收到 SIGTERM（或 {@code context.close()}）时 Spring 关闭容器，RpcServer 优雅下线：摘注册 → 拒新请求 → 等在途
 * （上限 {@code rpc.server.shutdown-timeout}）→ 关端口与连接 → 关注册中心。
 *
 * <p>配置在 {@code provider.yml}（同一模块里还有 consumer，用不同的 spring.config.name 区分）。
 */
@SpringBootApplication
public class ProviderApplication {

	public static void main(String[] args) {
		builder().run(args);
	}

	/** main 与集成测试共用的启动方式：测试在此基础上覆盖注册中心地址与端口。 */
	public static SpringApplicationBuilder builder() {
		return new SpringApplicationBuilder(ProviderApplication.class)
				.web(WebApplicationType.NONE)
				.properties("spring.config.name=provider");
	}
}
