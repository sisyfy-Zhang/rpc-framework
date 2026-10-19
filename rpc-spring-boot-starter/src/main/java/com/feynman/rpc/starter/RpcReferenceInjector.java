package com.feynman.rpc.starter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.ReflectionUtils;

import com.feynman.rpc.core.RpcClient;
import com.feynman.rpc.core.config.ReferenceOptions;

/**
 * 给标了 {@link RpcReference} 的字段注入远程代理。在 {@code postProcessBeforeInitialization} 做，
 * 因此 {@code @PostConstruct} 里就能用。
 *
 * <p>为什么用 BeanPostProcessor：引用是字段级注入，不是独立的 bean；用 Registrar 就得为每个引用注册一个
 * FactoryBean 再走 {@code @Autowired}，多一层间接而对用户没有收益。RpcClient 通过 {@link ObjectProvider}
 * 获取且是懒加载 bean：没有任何 {@code @RpcReference} 的纯服务端应用不会连注册中心做订阅、也不起客户端 IO 线程。
 * 同一接口 + 同一选项的多个注入点共享同一个代理。
 */
public class RpcReferenceInjector implements BeanPostProcessor {

	private final ObjectProvider<RpcClient> client;
	private final Map<ReferenceKey, Object> proxies = new ConcurrentHashMap<>();

	public RpcReferenceInjector(ObjectProvider<RpcClient> client) {
		this.client = client;
	}

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		ReflectionUtils.doWithFields(bean.getClass(),
				field -> inject(bean, beanName, field),
				field -> field.isAnnotationPresent(RpcReference.class));
		return bean;
	}

	private void inject(Object bean, String beanName, Field field) {
		int modifiers = field.getModifiers();
		if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
			throw new BeanCreationException(beanName, "@RpcReference is not supported on static or final fields: " + field);
		}
		Class<?> itf = field.getType();
		if (!itf.isInterface()) {
			throw new BeanCreationException(beanName, "@RpcReference must be placed on an interface-typed field: " + field);
		}
		ReferenceOptions options = optionsOf(field.getAnnotation(RpcReference.class));
		Object proxy = proxies.computeIfAbsent(new ReferenceKey(itf, options), key -> client.getObject().reference(itf, options));
		ReflectionUtils.makeAccessible(field);
		ReflectionUtils.setField(field, bean, proxy);
	}

	/** 注解 → core 的引用选项；注解的每个属性都在这里映射一次。 */
	static ReferenceOptions optionsOf(RpcReference annotation) {
		return ReferenceOptions.defaults()
				.version(annotation.version())
				.group(annotation.group())
				.timeout(annotation.timeout() > 0 ? Duration.ofMillis(annotation.timeout()) : null)
				.retries(annotation.retries())
				.inheritDeadline(annotation.inheritDeadline())
				.awaitProviders(annotation.awaitProvidersMs() > 0 ? Duration.ofMillis(annotation.awaitProvidersMs()) : null);
	}

	private record ReferenceKey(Class<?> itf, ReferenceOptions options) {
	}
}
