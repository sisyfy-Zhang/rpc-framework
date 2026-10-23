package io.github.sisyfyzhang.steadyrpc.core;

import java.lang.reflect.Method;
import java.lang.reflect.Type;

import io.github.sisyfyzhang.steadyrpc.core.protocol.MethodSignature;
import io.github.sisyfyzhang.steadyrpc.core.server.ServiceDispatcher;

/**
 * 客户端对一个远程方法预先算好的元数据：paramSig、各参数的泛型声明类型、返回值的声明类型
 * （{@code CompletableFuture<T>} 取 T）。按 Method 缓存在 RpcClient 实例里，每次调用不再反射计算。
 */
record RemoteMethod(Method method, String paramSig, Type[] genericParamTypes, Type returnValueType, boolean voidReturn) {

	static RemoteMethod of(Method method) {
		Type returnValueType = ServiceDispatcher.returnValueType(method);
		return new RemoteMethod(method, MethodSignature.paramSig(method), method.getGenericParameterTypes(), returnValueType,
				returnValueType == void.class || returnValueType == Void.class);
	}
}
