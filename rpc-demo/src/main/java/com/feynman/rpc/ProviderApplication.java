package com.feynman.rpc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.feynman.rpc.starter.EnableRpc;

/**
 * rpc-spring-boot-starter 的实际使用示例：{@code @EnableRpc} 开启服务端功能（在 application.yml
 * 里设置 {@code rpc.provider.enabled=true} 效果一样），其余配置——注册中心/序列化/负载均衡/容错
 * 策略选择、端口、权重——都通过 {@code RpcProperties} 从 application.yml 读取，而不是靠 JVM
 * {@code -D} 参数或手动组装 {@code AnnotationConfigApplicationContext}。
 */
@SpringBootApplication
@EnableRpc
public class ProviderApplication {

	public static void main(String[] args) {
		SpringApplication.run(ProviderApplication.class, args);
	}

}
