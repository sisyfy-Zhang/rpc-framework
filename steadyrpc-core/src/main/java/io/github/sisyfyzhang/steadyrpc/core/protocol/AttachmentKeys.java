package io.github.sisyfyzhang.steadyrpc.core.protocol;

/**
 * 预留的 attachments 键名（DESIGN §3.5）。键名与线 B 的 HTTP header 保持一致，跨 HTTP / RPC 透传时不需要改名。
 *
 * <p>W3C Trace Context 三个键直接用标准名：OTel 的 TextMapPropagator 可以零改造地读写 attachments。
 * 框架自用的键统一加 {@code rpc.} 前缀，避免与业务键冲突。
 */
public final class AttachmentKeys {

	private AttachmentKeys() {
	}

	/** W3C traceparent。 */
	public static final String TRACEPARENT = "traceparent";
	/** W3C tracestate。 */
	public static final String TRACESTATE = "tracestate";
	/** W3C baggage。 */
	public static final String BAGGAGE = "baggage";

	/** 调用方的绝对截止时间（epoch 毫秒），仅供观测与日志；服务端判定用 {@link #TIMEOUT}。 */
	public static final String DEADLINE = "rpc.deadline";
	/**
	 * 发送时刻的剩余时间（毫秒）。服务端以"请求到达时刻 + 该值"判定过期——用相对值而不是 {@link #DEADLINE}，
	 * 是为了不受两台机器时钟偏差影响（gRPC 的 grpc-timeout 同理）。
	 */
	public static final String TIMEOUT = "rpc.timeout";
	/** 压测流量标记（影子表路由）。 */
	public static final String SHADOW = "rpc.shadow";
	/** 幂等键。 */
	public static final String IDEMPOTENCY_KEY = "rpc.idempotency-key";
}
