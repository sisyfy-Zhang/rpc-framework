package com.feynman.rpc.core.mediator;

import java.lang.reflect.Method;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import com.feynman.rpc.api.annotation.Remote;

/**
 * 服务端 bean。通过 {@link com.feynman.rpc.core.config.RpcProviderConfiguration} 显式注册，
 * 而不是用 {@code @Component} + 包扫描——避免消费端的组件扫描把服务端 bean 也一并扫进去。
 */
public class InitialMediator implements BeanPostProcessor{

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		if(bean.getClass().isAnnotationPresent(Remote.class)) {
			Method[] methods = bean.getClass().getDeclaredMethods();
			for(Method m: methods) {
				//构建唯一键
				String key = bean.getClass().getInterfaces()[0].getName() + "." + m.getName();
				//构建 beanmethod 对象
				BeanMethod value = new BeanMethod();
				value.setBean(bean);
				value.setMethod(m);
				//注册到映射表
				MethodMediator.registerMethod(key, value);
			}
		}
		return bean;
	}
}
