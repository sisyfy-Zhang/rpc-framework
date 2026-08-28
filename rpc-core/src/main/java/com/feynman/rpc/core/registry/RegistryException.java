package com.feynman.rpc.core.registry;

/** 包装各注册中心后端（ZK/Nacos 等）特有失败的非受检异常。 */
public class RegistryException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public RegistryException(String message, Throwable cause) {
		super(message, cause);
	}
}
