package com.feynman.rpc.core.protocol;

/**
 * 解码后的请求体（即 {@code RpcMessage.payload} 字节解出来的对象）。
 *
 * <p>{@code arg} 在构造这个对象之前，调用方就已经把方法参数序列化成字节了，字段类型是
 * {@code byte[]} 而不是嵌套的 {@code Object}。这样每个 {@link com.feynman.rpc.core.serialize.Serializer}
 * 实现只需要处理 {@code String}/{@code byte[]} 这种简单字段，不用支持多态/未知类型字段——
 * 参数的真实类型要等 {@code MethodMediator} 查到目标方法声明的参数类型后，才用它直接反序列化
 * {@code arg}。
 */
public class RpcRequest {

	private String command;
	private byte[] arg;

	public RpcRequest() {
	}

	public RpcRequest(String command, byte[] arg) {
		this.command = command;
		this.arg = arg;
	}

	public String getCommand() {
		return command;
	}

	public void setCommand(String command) {
		this.command = command;
	}

	public byte[] getArg() {
		return arg;
	}

	public void setArg(byte[] arg) {
		this.arg = arg;
	}
}
