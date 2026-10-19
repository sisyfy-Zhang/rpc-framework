package com.feynman.rpc.starter;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import com.feynman.rpc.core.RpcClient;
import com.feynman.rpc.core.RpcServer;
import com.feynman.rpc.metrics.micrometer.MicrometerClientFilter;
import com.feynman.rpc.metrics.micrometer.MicrometerServerFilter;
import com.feynman.rpc.metrics.micrometer.RpcClientMetrics;
import com.feynman.rpc.metrics.micrometer.RpcServerMetrics;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 可选的指标装配：classpath 上有 Micrometer 与 rpc-metrics-micrometer、容器里有 {@link MeterRegistry} bean（典型来源是
 * Actuator 的指标自动配置），且 {@code rpc.metrics.enabled} 不为 false（缺省 true）时生效。starter 对两者都是 optional 依赖。
 *
 * <ul>
 *   <li>注册 {@link MicrometerClientFilter} / {@link MicrometerServerFilter} 两个 bean：它们实现 ClientFilter / ServerFilter，
 *       走 {@link RpcAutoConfiguration} 现有的"Filter bean 自动接入"机制进入两条链，不需要另一条接入路径；</li>
 *   <li>一个 BeanPostProcessor 在 RpcClient / RpcServer bean（含用户自己声明的）初始化完成后绑定在途与连接数 Gauge，
 *       容器关闭时解绑。RpcClient 是懒加载的：没有任何 {@code @RpcReference} 时它不会被创建，客户端 Gauge 也就不存在。</li>
 * </ul>
 * 独立成一个自动配置类（而不是 {@link RpcAutoConfiguration} 的方法），理由同 {@link RpcHealthAutoConfiguration}：条件按字节码元数据
 * 判断，没有 Micrometer 的应用永远不会加载引用了它的类。排在 Boot 的指标自动配置之后，{@code @ConditionalOnBean} 才看得到
 * 它们注册的 MeterRegistry。
 */
@AutoConfiguration(after = RpcAutoConfiguration.class, afterName = {
		"org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
		"org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
		"org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration" })
@ConditionalOnClass(name = { "io.micrometer.core.instrument.MeterRegistry",
		"com.feynman.rpc.metrics.micrometer.MicrometerClientFilter" })
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(prefix = "rpc.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RpcMetricsAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	public MicrometerClientFilter rpcMetricsClientFilter(MeterRegistry registry) {
		return new MicrometerClientFilter(registry);
	}

	@Bean
	@ConditionalOnMissingBean
	public MicrometerServerFilter rpcMetricsServerFilter(MeterRegistry registry) {
		return new MicrometerServerFilter(registry);
	}

	@Bean
	public static RpcMetricsBinder rpcMetricsBinder(ObjectProvider<MeterRegistry> registry) {
		return new RpcMetricsBinder(registry);
	}

	/** RpcClient / RpcServer 初始化完成后绑定 Gauge；容器关闭时从 registry 移除。 */
	static final class RpcMetricsBinder implements BeanPostProcessor, DisposableBean {

		private final ObjectProvider<MeterRegistry> registry;
		private final List<AutoCloseable> bindings = new ArrayList<>();

		RpcMetricsBinder(ObjectProvider<MeterRegistry> registry) {
			this.registry = registry;
		}

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
			if (bean instanceof RpcClient client) {
				add(RpcClientMetrics.bind(client, registry.getObject()));
			} else if (bean instanceof RpcServer server) {
				add(RpcServerMetrics.bind(server, registry.getObject()));
			}
			return bean;
		}

		private synchronized void add(AutoCloseable binding) {
			bindings.add(binding);
		}

		@Override
		public synchronized void destroy() throws Exception {
			for (AutoCloseable binding : bindings) {
				binding.close();
			}
			bindings.clear();
		}
	}
}
