package com.feynman.rpc.starter;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

import com.feynman.rpc.core.RpcClient;
import com.feynman.rpc.core.RpcServer;
import com.feynman.rpc.core.config.RpcClientConfig;
import com.feynman.rpc.core.config.RpcServerConfig;
import com.feynman.rpc.core.filter.ClientFilter;
import com.feynman.rpc.core.filter.Filter;
import com.feynman.rpc.core.filter.ServerFilter;

/**
 * 只要 classpath 上有这个 starter 就自动生效（{@code META-INF/spring/...AutoConfiguration.imports}）。
 * starter 只做三件事：properties → 不可变配置、注解 → export / reference、SmartLifecycle → start / close。
 * 框架行为全部在 rpc-core，Spring 侧没有第二套实现。
 *
 * <p>配置的组装顺序（客户端与服务端相同），后者覆盖前者：
 * <ol>
 *   <li>{@code rpc.*} properties（{@link RpcProperties#applyTo}）；</li>
 *   <li>容器里全部 {@link ClientFilter} / {@link ServerFilter} bean 追加进对应的 Filter 链。按 {@code @Order} 收集，
 *       执行顺序最终由 core 按 {@link Filter#order()} 稳定排序决定（同 order 时保持收集顺序）；一个类同时实现两个标记接口
 *       就同时进两条链。只实现 {@link Filter} 而没有标记接口的 bean 不会被接入（不知道该放哪一端），启动时打 warn；</li>
 *   <li>全部 {@link RpcClientCustomizer} / {@link RpcServerCustomizer} bean 按 {@code @Order} 依次修改 builder。</li>
 * </ol>
 *
 * <ul>
 *   <li>{@link RpcClient}：懒加载单例，首个 {@code @RpcReference} 注入时才创建（连注册中心、起 IO 线程）；
 *       容器关闭时由 Spring 推断出的 {@code close()} 释放。</li>
 *   <li>{@link RpcServer}：构造无副作用（不起线程、不绑端口），所以总是创建；是否启动由
 *       {@link RpcServerLifecycle} 根据是否有 {@code @RpcService} 决定。</li>
 *   <li>两个 BeanPostProcessor 用 static 工厂方法声明，避免配置类被提前实例化。</li>
 * </ul>
 * 用户自己声明 RpcClient / RpcServer bean 时以用户的为准（{@code @ConditionalOnMissingBean}），此时上面的组装不生效。
 */
@AutoConfiguration
@EnableConfigurationProperties(RpcProperties.class)
public class RpcAutoConfiguration {

	private static final Logger log = LoggerFactory.getLogger(RpcAutoConfiguration.class);

	@Bean
	@Lazy
	@ConditionalOnMissingBean
	public RpcClient rpcClient(RpcProperties properties, ObjectProvider<ClientFilter> clientFilters,
			ObjectProvider<RpcClientCustomizer> customizers) {
		RpcClientConfig.Builder<RpcClientConfig> builder = RpcClientConfig.builder();
		properties.applyTo(builder);
		builder.clientFilter(clientFilters.orderedStream().toArray(Filter[]::new));
		customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
		return RpcClient.create(builder.build());
	}

	@Bean
	@ConditionalOnMissingBean
	public RpcServer rpcServer(RpcProperties properties, ObjectProvider<ServerFilter> serverFilters,
			ObjectProvider<RpcServerCustomizer> customizers, ObjectProvider<Filter> allFilters) {
		RpcServerConfig.Builder<RpcServerConfig> builder = RpcServerConfig.builder();
		properties.applyTo(builder);
		builder.serverFilter(serverFilters.orderedStream().toArray(Filter[]::new));
		customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
		// RpcServer 总会创建（RpcClient 是懒加载的），所以"没有标记接口的 Filter"只在这里检查一次
		warnAboutUnmarkedFilters(allFilters);
		return RpcServer.create(builder.build());
	}

	@Bean
	@ConditionalOnMissingBean
	public RpcServerLifecycle rpcServerLifecycle(RpcServer rpcServer) {
		return new RpcServerLifecycle(rpcServer);
	}

	@Bean
	public static RpcServiceExporter rpcServiceExporter(ObjectProvider<RpcServer> rpcServer) {
		return new RpcServiceExporter(rpcServer);
	}

	@Bean
	public static RpcReferenceInjector rpcReferenceInjector(ObjectProvider<RpcClient> rpcClient) {
		return new RpcReferenceInjector(rpcClient);
	}

	private static void warnAboutUnmarkedFilters(ObjectProvider<Filter> allFilters) {
		List<String> unmarked = allFilters.stream()
				.filter(f -> !(f instanceof ClientFilter) && !(f instanceof ServerFilter))
				.map(f -> f.getClass().getName())
				.toList();
		if (!unmarked.isEmpty()) {
			log.warn("Filter beans {} implement neither ClientFilter nor ServerFilter and were not added to any chain; "
					+ "implement one (or both) of the marker interfaces", unmarked);
		}
	}
}
