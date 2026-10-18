package com.feynman.rpc.core;

import com.feynman.rpc.core.protocol.Status;

/**
 * 服务端抛出的异常在本端没有还原成同类型时的降级表示：类名不在客户端的异常还原白名单内
 * （{@code RpcClientConfig.exceptionAllowlist}）、本地 classpath 上没有该类、它不是 {@link Exception}
 * 的子类，或没有 {@code (String)} / {@code (String, Throwable)} 构造器。保留原类名与消息，调用方仍可按
 * {@link #remoteClassName()} 区分；远端堆栈（若服务端开启）挂在 cause 上，类型为 {@link RemoteStackTrace}。
 */
public class RpcRemoteException extends RpcException {

	private static final long serialVersionUID = 1L;

	private final String remoteClassName;
	private final String remoteMessage;
	private final Status status;

	public RpcRemoteException(String remoteClassName, String remoteMessage, RemoteStackTrace remoteStack) {
		this(remoteClassName, remoteMessage, remoteStack, Status.BIZ_EXCEPTION);
	}

	/** @param status 服务端回包的状态（BIZ_EXCEPTION 或 SERVER_ERROR），由 {@link #status()} 原样交还 */
	public RpcRemoteException(String remoteClassName, String remoteMessage, RemoteStackTrace remoteStack, Status status) {
		super(remoteClassName + ": " + remoteMessage, remoteStack);
		this.remoteClassName = remoteClassName;
		this.remoteMessage = remoteMessage;
		this.status = status;
	}

	public String remoteClassName() {
		return remoteClassName;
	}

	public String remoteMessage() {
		return remoteMessage;
	}

	/** 远端堆栈文本；服务端关闭了堆栈回传时为 null。 */
	public String remoteStackTrace() {
		return getCause() instanceof RemoteStackTrace remote ? remote.remoteStack() : null;
	}

	/** 服务端回包的状态：业务方法抛出的是 BIZ_EXCEPTION，框架侧失败 / Error 是 SERVER_ERROR。 */
	@Override
	public Status status() {
		return status;
	}
}
