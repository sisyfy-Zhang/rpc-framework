package com.feynman.rpc.core.proxy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import com.feynman.rpc.api.annotation.RemoteInvoke;
import com.feynman.rpc.core.client.TCPClient;
import com.feynman.rpc.core.protocol.MessageType;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.protocol.RpcResponse;
import com.feynman.rpc.core.protocol.Status;
import com.feynman.rpc.core.serialize.Serializer;
import com.feynman.rpc.core.serialize.Serializers;

/**
 * 消费端 bean。通过 {@link com.feynman.rpc.core.config.RpcConsumerConfiguration} 显式注册，
 * 而不是用 {@code @Component} + 包扫描——避免消费端/服务端的组件扫描互相误捡到对方的 bean。
 *
 * <p>用普通 JDK {@link Proxy} 而不是 CGLIB：{@code @RemoteInvoke} 字段本来就要求是接口类型
 * （下面有校验），所以不需要 CGLIB 那套生成子类的机制——也就不用依赖
 * {@code --add-opens java.base/java.lang} 去满足 CGLIB 反射调用 {@code ClassLoader.defineClass}
 * 的需求（JDK 17+ 更严格的模块封装默认会拒绝这个调用）。
 *
 * <p>异步优先：{@code @RemoteInvoke} 字段对应的接口方法如果声明返回 {@code CompletableFuture<T>}，
 * 就走真正的非阻塞派发（直接返回 {@link TCPClient#sendAsync} 底层的 future）；普通同步方法签名
 * 只是在同一个 future 上阻塞等待——同步是异步之上的语法糖，不是另一条代码路径。
 */
public class InvokeProxy implements BeanPostProcessor{

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {

		Field[] fields = bean.getClass().getDeclaredFields();

		for(Field field: fields) {
			 // 0) 只处理标了 @RemoteInvoke 的字段
	        if (!field.isAnnotationPresent(RemoteInvoke.class)) {
	            continue;
	        }
	        // 1) 跳过 static/final
	        int mod = field.getModifiers();
	        if (Modifier.isStatic(mod) || Modifier.isFinal(mod)) {
	            continue;
	        }

	        Class<?> itf = field.getType();
	        // 2) 必须是接口
	        if (!itf.isInterface()) {
	            throw new IllegalStateException(
	                "@RemoteInvoke 只能标在接口类型字段上: " + bean.getClass().getName() + "." + field.getName()
	            );
	        }

	        RemoteInvoke config = field.getAnnotation(RemoteInvoke.class);

			// 3)设置可访问性（突破private限制）
			field.setAccessible(true);

			// 4)创建动态代理
			Object proxyObj = Proxy.newProxyInstance(itf.getClassLoader(), new Class<?>[]{ itf },
					(proxyInstance, method, args) -> invokeRemote(itf, method, args, config.timeout(), config.retries()));

			try {
				// 5. 注入代理
	            field.set(bean, proxyObj);
			}catch (Exception e) {
				throw new BeanCreationException(beanName, "注入 RPC 代理失败: " + field.getName(), e);
			}
		}
		return bean;
	}

	private Object invokeRemote(Class<?> itf, Method method, Object[] args, long timeoutMillis, int retries) {
		// equals/hashCode/toString 走本地实现，不要把它们当成远程调用发出去
		if (method.getDeclaringClass() == Object.class) {
			switch (method.getName()) {
				case "toString": return "RemoteInvokeProxy(" + itf.getName() + ")";
				case "hashCode": return System.identityHashCode(itf);
				case "equals": return args != null && args.length > 0 && args[0] == this;
				default: return null;
			}
		}

		//通过 netty 远程调用逻辑
		String command = itf.getName() + "." + method.getName();
		Object arg = (args != null && args.length > 0) ? args[0] : null;

		Class<?> rt = method.getReturnType();

		// 异步优先：方法声明返回 CompletableFuture<T> 时，直接透传底层的 CompletableFuture，
		// 调用方线程完全不会被网络 IO 阻塞——同步调用（下面的 else 分支）只是在它上面 .join() 的语法糖。
		if (rt == CompletableFuture.class) {
			Class<?> resultType = resolveFutureResultType(method);
			return TCPClient.sendAsync(command, arg, timeoutMillis, retries).thenApply(resp -> decodeResult(resp, resultType));
		}

		RpcMessage resp = TCPClient.send(command, arg, timeoutMillis, retries);
		return decodeResult(resp, rt);
	}

	private static Class<?> resolveFutureResultType(Method method) {
		Type generic = method.getGenericReturnType();
		if (generic instanceof ParameterizedType parameterized) {
			Type arg0 = parameterized.getActualTypeArguments()[0];
			if (arg0 instanceof Class<?> clazz) {
				return clazz;
			}
		}
		return Object.class;
	}

	private static Object decodeResult(RpcMessage resp, Class<?> rt) {
		// 1) 先判断是否成功
		if (resp == null) {
		    throw new RuntimeException("RPC response is null");
		}
		if (resp.getMessageType() != MessageType.RESPONSE) {
		    throw new RuntimeException("Unexpected message type: " + resp.getMessageType());
		}
		Serializer serializer = Serializers.byCode(resp.getSerializeType());
		RpcResponse body = serializer.deserialize(resp.getPayload(), RpcResponse.class);
		if (resp.getStatus() != Status.OK) {
		    throw new RuntimeException("RPC failed: " + resp.getStatus()
		            + (body != null && body.getReason() != null ? " - " + body.getReason() : ""));
		}
		// 2) void 方法：直接返回 null
		if (rt == void.class || rt == Void.class) {
		    return null;
		}
		// 3) 非 void：把结果字节按声明的返回类型反序列化
		byte[] resultBytes = body.getResult();
		if (resultBytes == null || resultBytes.length == 0) return null;

		return serializer.deserialize(resultBytes, rt);
	}


	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}
}
