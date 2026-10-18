package com.feynman.rpc.starter;

import java.util.ArrayList;
import java.util.List;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotatedElementUtils;

import com.feynman.rpc.core.RpcServer;
import com.feynman.rpc.core.config.ServiceOptions;

/**
 * 把标了 {@link RpcService} 的 bean 交给 {@link RpcServer#export}。只登记方法表，不绑端口——
 * 绑端口和注册在 {@link RpcServerLifecycle#start()}，即所有单例都初始化完之后。
 *
 * <p>为什么用 BeanPostProcessor 而不是 ImportBeanDefinitionRegistrar：服务实现本身就是普通 bean
 * （{@code @RpcService} 元注解了 {@code @Component}），BPP 在 {@code postProcessAfterInitialization}
 * 拿到的是容器最终交付的实例（含 AOP 代理），暴露它才能让事务等切面对远程调用生效；Registrar 只能看到
 * bean 定义，还得自己再扫一遍包、处理代理。RpcServer 通过 {@link ObjectProvider} 延迟获取，
 * 避免 BPP 过早实例化依赖导致其他 bean 错过后处理。
 */
public class RpcServiceExporter implements BeanPostProcessor {

	private final ObjectProvider<RpcServer> server;

	public RpcServiceExporter(ObjectProvider<RpcServer> server) {
		this.server = server;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		Class<?> targetClass = AopUtils.getTargetClass(bean);
		RpcService annotation = AnnotatedElementUtils.findMergedAnnotation(targetClass, RpcService.class);
		if (annotation == null) {
			return bean;
		}
		Class<?> itf = resolveInterface(targetClass, annotation, beanName);
		ServiceOptions options = ServiceOptions.defaults()
				.version(annotation.version())
				.group(annotation.group())
				.weight(annotation.weight());
		export(server.getObject(), itf, bean, options);
		return bean;
	}

	private static <T> void export(RpcServer rpcServer, Class<T> itf, Object bean, ServiceOptions options) {
		rpcServer.export(itf, itf.cast(bean), options);
	}

	private static Class<?> resolveInterface(Class<?> targetClass, RpcService annotation, String beanName) {
		if (annotation.interfaceClass() != void.class) {
			Class<?> declared = annotation.interfaceClass();
			if (!declared.isInterface() || !declared.isAssignableFrom(targetClass)) {
				throw new BeanCreationException(beanName, "@RpcService(interfaceClass=" + declared.getName()
						+ ") must be an interface implemented by " + targetClass.getName());
			}
			return declared;
		}
		List<Class<?>> candidates = new ArrayList<>();
		for (Class<?> itf : targetClass.getInterfaces()) {
			String name = itf.getName();
			if (!name.startsWith("java.") && !name.startsWith("org.springframework.")) {
				candidates.add(itf);
			}
		}
		if (candidates.size() != 1) {
			throw new BeanCreationException(beanName, "@RpcService on " + targetClass.getName()
					+ " must implement exactly one business interface or set interfaceClass; found " + candidates);
		}
		return candidates.get(0);
	}
}
