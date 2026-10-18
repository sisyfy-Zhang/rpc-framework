package com.feynman.rpc.starter;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在接口类型的字段上：注入 {@code RpcClient.reference(...)} 创建的远程代理。只是 reference 的声明式包装。
 * 注入发生在 {@code @PostConstruct} 之前，初始化方法里就可以使用。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RpcReference {

	String version() default "";

	String group() default "";

	/** 单次调用超时（毫秒）；0 表示沿用 {@code rpc.client.default-timeout}。 */
	long timeout() default 0;

	/**
	 * 失败后额外重试次数。默认 0（不重试）——即便配置的是 failover 这类支持重试的 Cluster，
	 * 也不会对没有显式声明过"可以重试"的引用生效，避免非幂等调用被意外重试。
	 */
	int retries() default 0;

	/**
	 * 是否继承上游剩余的 deadline（默认 true）：在服务端业务方法里（或 {@code RpcContext.withDeadline} 作用域内）
	 * 经这个引用发起的调用，超时取 {@code timeout} 与上游剩余预算的较小者——上游已经放弃的请求，下游再按自己的
	 * 长超时等下去只是浪费资源。只有确实要脱离上游生命周期的调用（例如上游超时后仍须完成的补偿写）才设为 false。
	 */
	boolean inheritDeadline() default true;
}
