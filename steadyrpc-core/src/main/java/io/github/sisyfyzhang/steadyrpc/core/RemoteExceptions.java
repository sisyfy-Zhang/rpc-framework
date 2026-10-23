package io.github.sisyfyzhang.steadyrpc.core;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.function.Predicate;

import io.github.sisyfyzhang.steadyrpc.core.codec.EnvelopeCodec.ErrorEnvelope;
import io.github.sisyfyzhang.steadyrpc.core.protocol.Status;

/**
 * 客户端把 BIZ_EXCEPTION / SERVER_ERROR 的错误信封还原成异常对象。
 *
 * <p>规则：类名在异常还原白名单内（见 {@code RpcClientConfig.Builder#exceptionAllowlist}；白名单外的类名<b>不加载</b>）、
 * 能用接口的类加载器（其次线程上下文类加载器）加载该类、它是 {@link Exception} 的非抽象子类、且有
 * {@code (String)} 或 {@code (String, Throwable)} 构造器 → 还原同类型，远端堆栈作为 cause 挂上（cause 已被构造器
 * 占用时改为 suppressed）；否则降级为 {@link RpcRemoteException}，保留原类名与消息。
 *
 * <p>为什么只还原 Exception 而不还原 Error：把服务端的 OutOfMemoryError / StackOverflowError 在客户端原样抛出会误导排障
 * （客户端并没有内存问题），这类情况降级为 RpcRemoteException。
 *
 * <p><b>还原对象的状态与线上状态可能不同</b>：还原出的框架异常（RpcException 子类）的 {@code status()} 是该异常类型
 * 自己的状态，不是回包的状态——业务方法透传下游的 RpcOverloadedException 时线上为 SERVER_ERROR，还原对象的
 * {@code status()} 却是 SERVER_OVERLOADED。客户端的重试判定以线上状态为准（只有线上 SERVER_OVERLOADED 走传输失败通道），
 * 不看还原对象的 {@code status()}；降级的 RpcRemoteException 则带着线上状态。
 */
final class RemoteExceptions {

	private RemoteExceptions() {
	}

	/**
	 * @param status  回包状态（BIZ_EXCEPTION / SERVER_ERROR），降级时由 RpcRemoteException 带回给调用方
	 * @param allowed 异常类名白名单；不通过的类名直接降级，不触发类加载
	 */
	static Throwable restore(ErrorEnvelope error, Status status, ClassLoader preferredLoader, Predicate<String> allowed) {
		RemoteStackTrace remote = error.remoteStack().isEmpty() ? null
				: new RemoteStackTrace(error.className(), error.remoteStack());
		Throwable restored = allowed.test(error.className())
				? instantiate(error.className(), error.message(), remote, preferredLoader)
				: null;
		return restored != null ? restored : new RpcRemoteException(error.className(), error.message(), remote, status);
	}

	private static Throwable instantiate(String className, String message, RemoteStackTrace remote, ClassLoader preferredLoader) {
		if (className.isEmpty()) {
			return null;
		}
		Class<?> type = load(className, preferredLoader);
		if (type == null) {
			type = load(className, Thread.currentThread().getContextClassLoader());
		}
		if (type == null || !Exception.class.isAssignableFrom(type) || Modifier.isAbstract(type.getModifiers())) {
			return null;
		}
		try {
			Constructor<?> withMessage = constructor(type, String.class);
			if (withMessage != null) {
				Throwable t = (Throwable) withMessage.newInstance(message);
				attach(t, remote);
				return t;
			}
			Constructor<?> withCause = constructor(type, String.class, Throwable.class);
			if (withCause != null) {
				return (Throwable) withCause.newInstance(message, remote);
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			return null;
		}
		return null;
	}

	private static void attach(Throwable t, RemoteStackTrace remote) {
		if (remote == null) {
			return;
		}
		try {
			t.initCause(remote);
		} catch (IllegalStateException | IllegalArgumentException alreadySet) {
			t.addSuppressed(remote);
		}
	}

	private static Constructor<?> constructor(Class<?> type, Class<?>... params) {
		try {
			Constructor<?> c = type.getConstructor(params);
			return c;
		} catch (NoSuchMethodException | SecurityException e) {
			return null;
		}
	}

	private static Class<?> load(String className, ClassLoader loader) {
		if (loader == null) {
			return null;
		}
		try {
			return Class.forName(className, false, loader);
		} catch (ClassNotFoundException | LinkageError e) {
			return null;
		}
	}
}
