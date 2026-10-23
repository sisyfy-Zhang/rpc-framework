package io.github.sisyfyzhang.steadyrpc.core.protocol;

/**
 * 响应状态，对应帧头 status 字节（请求帧固定为 {@link #OK}）。
 *
 * <p>为什么要细分而不是只有成功 / 失败：客户端要据此决定"抛什么"——BIZ_EXCEPTION 还原成业务异常类型，
 * DEADLINE_EXCEEDED 映射为 {@code RpcDeadlineExceededException}，其余是框架错误；服务端也据此决定日志级别
 * （业务异常不打 error 堆栈）。编码随 v2 重新分配，与 v1 不兼容。
 */
public enum Status {

	OK((byte) 0),
	/** 帧或信封格式错误、attachments 超限、参数个数不符。 */
	BAD_REQUEST((byte) 1),
	/** 服务（interface:version:group）未暴露，或方法 + paramSig 不存在。 */
	SERVICE_NOT_FOUND((byte) 2),
	/** 框架侧失败（Filter 抛异常、Error、业务方法抛出的 RpcException、回程 attachments 超限或非法）。 */
	SERVER_ERROR((byte) 3),
	/** 业务方法抛出的异常。 */
	BIZ_EXCEPTION((byte) 4),
	/** 请求在服务端开始执行前已过期，业务方法未被调用。 */
	DEADLINE_EXCEEDED((byte) 5),
	/**
	 * 服务端拒绝执行：业务执行器满载（队列满 / 并发达到上限）、正在优雅停机（DRAINING）、或 Filter 以
	 * RpcOverloadedException 拒绝。<b>业务方法一定没有执行</b>，所以客户端把它当作传输失败、可以换节点重试
	 * （failover 排除该节点）。业务方法自己抛出的下游 SERVER_OVERLOADED 不会以此状态回包（见 ServiceDispatcher）。
	 */
	SERVER_OVERLOADED((byte) 6),
	/** 参数 / 返回值序列化失败、返回值使回包超过整帧上限，或请求使用了服务端不支持的序列化编码。 */
	SERIALIZATION_ERROR((byte) 7);

	private final byte code;

	Status(byte code) {
		this.code = code;
	}

	public byte code() {
		return code;
	}

	/** 未知编码返回 null（解码器据此判定帧非法），不抛异常。 */
	public static Status find(byte code) {
		for (Status status : values()) {
			if (status.code == code) {
				return status;
			}
		}
		return null;
	}

	public static Status fromCode(byte code) {
		Status status = find(code);
		if (status == null) {
			throw new IllegalArgumentException("Unknown status code: " + code);
		}
		return status;
	}
}
