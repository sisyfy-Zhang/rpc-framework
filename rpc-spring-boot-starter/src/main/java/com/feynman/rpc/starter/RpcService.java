package com.feynman.rpc.starter;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.stereotype.Component;

/**
 * 标在服务实现类上：该 bean 会被 {@code RpcServer.export} 暴露。只是 export 的声明式包装，
 * 语义与纯 Java API 完全相同。
 *
 * <p>元注解 {@link Component}：实现类本来就是 Spring bean（要注入 DAO、事务等），标了它就会被组件扫描捡到，
 * 不需要再写 {@code @Service}；暴露的是容器里的最终实例（可能是 AOP 代理），事务等切面对远程调用同样生效。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface RpcService {

	/** 要暴露的接口；缺省时取实现类唯一直接实现的业务接口，有多个时必须显式指定。 */
	Class<?> interfaceClass() default void.class;

	String version() default "";

	String group() default "";

	/** 注册权重；0 表示沿用 {@code rpc.server.weight}。 */
	int weight() default 0;
}
