package com.feynman.rpc.core.protocol;

/**
 * 解码后的响应体。{@code result} 是被调用方已经按运行时类型序列化好的返回值——设计原因见
 * {@link RpcRequest}，同样是为了让每个 {@code Serializer} 实现不用支持多态字段。成功/失败状态
 * 本身放在 {@code RpcMessage} 协议头里（见 {@link Status}），这里不重复保存。
 */
public class RpcResponse {

	private String reason;
	private byte[] result;

	public RpcResponse() {
	}

	public RpcResponse(String reason, byte[] result) {
		this.reason = reason;
		this.result = result;
	}

	public String getReason() {
		return reason;
	}

	public void setReason(String reason) {
		this.reason = reason;
	}

	public byte[] getResult() {
		return result;
	}

	public void setResult(byte[] result) {
		this.result = result;
	}
}
