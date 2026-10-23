package io.github.sisyfyzhang.steadyrpc.examples.provider;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.sisyfyzhang.steadyrpc.core.filter.Invocation;
import io.github.sisyfyzhang.steadyrpc.core.filter.Invoker;
import io.github.sisyfyzhang.steadyrpc.core.filter.Result;
import io.github.sisyfyzhang.steadyrpc.core.filter.ServerFilter;

/**
 * 服务端 Filter 示例：读取调用方用 {@code RpcContext.setAttachment("tenant", ...)} 带来的 attachment。
 * ServerFilter bean 由 starter 自动接入。Filter 里从 {@link Invocation} 读；业务方法里用
 * {@code RpcContext.getServerAttachment("tenant")}（入站上下文在链尾、调用业务方法前才绑定到线程上）。
 */
@Component
public class TenantFilter implements ServerFilter {

	private static final Logger log = LoggerFactory.getLogger(TenantFilter.class);

	private volatile String lastTenant;

	@Override
	public CompletableFuture<Result> invoke(Invoker next, Invocation invocation) {
		String tenant = invocation.getAttachment("tenant");
		lastTenant = tenant;
		if (tenant != null) {
			log.info("{} called for tenant {}", invocation.methodName(), tenant);
		}
		return next.invoke(invocation);
	}

	/** 最近一次调用带来的 tenant，没带为 null（集成测试用它确认 attachment 只随下一次调用发出）。 */
	public String lastTenant() {
		return lastTenant;
	}
}
