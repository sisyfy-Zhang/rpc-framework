package com.feynman.rpc.api.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 用于字段注入 RPC 客户端代理

@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RemoteInvoke {

	String value() default "";

	/** 单次调用超时（毫秒）。 */
	long timeout() default 20000;

	/**
	 * 失败后额外重试次数。默认 0（不重试）——即便当前配置的是 failover 这类支持重试的 Cluster
	 * 策略，也不会对没有显式声明过"可以重试"的调用生效，避免非幂等调用被意外重试。
	 */
	int retries() default 0;
}
