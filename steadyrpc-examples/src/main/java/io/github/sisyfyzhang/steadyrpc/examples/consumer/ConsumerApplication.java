package io.github.sisyfyzhang.steadyrpc.examples.consumer;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 服务消费方示例：{@link UserGateway} 里的 {@code @RpcReference} 字段由 starter 注入远程代理，
 * {@link DemoRunner} 在启动后发起一次调用。纯消费端应用不会绑定任何端口。
 *
 * <p>配置在 {@code consumer.yml}。main 里调用完即关闭容器：示例是一次性客户端，不需要常驻。
 */
@SpringBootApplication
public class ConsumerApplication {

	public static void main(String[] args) {
		try (ConfigurableApplicationContext context = builder().run(args)) {
			// DemoRunner 已在 run() 返回前执行完毕
		}
	}

	public static SpringApplicationBuilder builder() {
		return new SpringApplicationBuilder(ConsumerApplication.class)
				.web(WebApplicationType.NONE)
				.properties("spring.config.name=consumer");
	}
}
