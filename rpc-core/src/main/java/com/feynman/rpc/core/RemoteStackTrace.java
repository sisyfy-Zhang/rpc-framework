package com.feynman.rpc.core;

/**
 * 远端堆栈的载体：服务端异常的堆栈文本，作为还原出的异常的 cause（或 suppressed）挂上，日志里以
 * {@code Caused by: RemoteStackTrace: ...} 的形式出现在本地堆栈之后。
 *
 * <p>为什么是文本而不是还原成 StackTraceElement[]：远端类在本地可能不存在、行号对本地无意义，
 * 排障时需要的是"服务端在哪一行抛的"，原样的文本最直接。不填充本地堆栈（构造它的位置没有信息量）。
 */
public final class RemoteStackTrace extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String remoteClassName;
	private final String remoteStack;

	public RemoteStackTrace(String remoteClassName, String remoteStack) {
		super("remote stack trace of " + remoteClassName + ":\n" + remoteStack, null, false, false);
		this.remoteClassName = remoteClassName;
		this.remoteStack = remoteStack;
	}

	public String remoteClassName() {
		return remoteClassName;
	}

	public String remoteStack() {
		return remoteStack;
	}
}
