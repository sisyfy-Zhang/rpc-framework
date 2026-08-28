package com.feynman.rpc.core.mediator;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feynman.rpc.core.protocol.MessageType;
import com.feynman.rpc.core.protocol.RpcMessage;
import com.feynman.rpc.core.protocol.RpcRequest;
import com.feynman.rpc.core.protocol.RpcResponse;
import com.feynman.rpc.core.protocol.Status;
import com.feynman.rpc.core.serialize.Serializer;
import com.feynman.rpc.core.serialize.Serializers;


public class MethodMediator {

	private static final Logger log = LoggerFactory.getLogger(MethodMediator.class);

	public static final Map<String, BeanMethod> beanMap = new ConcurrentHashMap<>();
	private static MethodMediator m = null;

	private MethodMediator() {

	}

	public static void registerMethod(String key, BeanMethod beanMethod) {
		beanMap.put(key, beanMethod);
    }

	// 单例模式
	public static MethodMediator newInstance() {
		if(m == null) {
			m = new MethodMediator();
		}
		return m;
	}

	//通过反射处理业务代码
	public RpcMessage process(RpcMessage requestMsg) {
		long requestId = requestMsg.getRequestId();
		byte serializeType = requestMsg.getSerializeType();
		Serializer serializer = Serializers.byCode(serializeType);

	    try {
	        RpcRequest req = serializer.deserialize(requestMsg.getPayload(), RpcRequest.class);
	        String cmd = req.getCommand();
	        BeanMethod beanMethod = beanMap.get(cmd);

	        if (beanMethod == null) {
	            return errorReply(requestId, serializeType, serializer, Status.NOT_FOUND, "No such method: " + cmd);
	        }

            Object bean = beanMethod.getBean();
			Method method = beanMethod.getMethod();

			Object[] invokeArgs = new Object[0];
	        Class<?>[] paramTypes = method.getParameterTypes();

	        if (paramTypes.length == 1) {
	            Object arg = serializer.deserialize(req.getArg(), paramTypes[0]);
	            invokeArgs = new Object[] { arg };
	        } else if (paramTypes.length > 1) {
	            return errorReply(requestId, serializeType, serializer, Status.BAD_REQUEST,
	                    "Only support 0 or 1 parameter method, but got " + paramTypes.length);
	        }

	        Object ret = method.invoke(bean, invokeArgs);
	        // 服务端方法自己也可以声明返回 CompletableFuture<T>（比如内部做了异步 IO）；
	        // 框架在这里等它完成再回包，客户端拿到的仍然是"结果"字节，而不是 future 对象本身。
	        if (ret instanceof CompletableFuture) {
	            ret = ((CompletableFuture<?>) ret).join();
	        }
	        byte[] resultBytes = ret == null ? new byte[0] : serializer.serialize(ret);
	        byte[] payload = serializer.serialize(new RpcResponse(null, resultBytes));
	        return new RpcMessage(requestId, MessageType.RESPONSE, serializeType, Status.OK, payload);

        } catch (Exception e) {
            // method.invoke() 会把业务方法抛出的异常包一层 InvocationTargetException；
            // 这里解包一层，让回传给调用方的错误信息是真实的业务异常，而不是反射相关的包装类型
            Throwable reported = (e instanceof InvocationTargetException && e.getCause() != null) ? e.getCause() : e;
            log.error("Error invoking remote method for request {}", requestId, reported);
            return errorReply(requestId, serializeType, serializer, Status.SERVER_ERROR,
                    "Server error: " + reported.getClass().getSimpleName() + ": " + reported.getMessage());
        }
	}

	private RpcMessage errorReply(long requestId, byte serializeType, Serializer serializer, Status status, String reason) {
		byte[] payload = serializer.serialize(new RpcResponse(reason, new byte[0]));
		return new RpcMessage(requestId, MessageType.RESPONSE, serializeType, status, payload);
	}
}
