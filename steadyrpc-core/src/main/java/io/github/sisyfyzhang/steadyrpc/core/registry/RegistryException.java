package io.github.sisyfyzhang.steadyrpc.core.registry;

/**
 * 包装各注册中心后端（ZK/Nacos 等）特有失败的非受检异常。
 *
 * <p>契约（见 steadyrpc-registry-tck 的 RegistryContractTest）：后端不可达时 {@link Registry#register} 必须在实现声明的
 * 时限内以本异常失败，而不是无限阻塞调用线程——RpcServer 的后台注册据此退避重试。
 */
public class RegistryException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public RegistryException(String message) {
		super(message);
	}

	public RegistryException(String message, Throwable cause) {
		super(message, cause);
	}
}
