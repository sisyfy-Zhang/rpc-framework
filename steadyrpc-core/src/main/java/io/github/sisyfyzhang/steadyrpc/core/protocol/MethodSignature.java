package io.github.sisyfyzhang.steadyrpc.core.protocol;

import java.lang.reflect.Method;

/**
 * 请求信封里的 {@code paramSig}：区分重载方法的参数签名。客户端与服务端各自从同一个接口的 {@link Method}
 * 计算，所以规则必须确定且两端一致。
 *
 * <p>规则：按声明顺序取<b>擦除后</b>的参数类型（{@link Method#getParameterTypes()}），每个类型用
 * {@link Class#getTypeName()} 表示，用 {@code ,} 连接、无空格；无参方法为空串。具体为：
 * <ul>
 *   <li>基本类型：关键字本身，如 {@code int}、{@code long}、{@code boolean}；</li>
 *   <li>普通类与包装类型：全限定二进制名，如 {@code java.lang.Integer}、{@code com.x.Outer$Inner}；</li>
 *   <li>数组：组件类型名 + 每一维一个 {@code []}，如 {@code int[]}、{@code java.lang.String[][]}、{@code com.x.User[]}
 *       （不用 JVM 描述符 {@code [I} / {@code [Ljava.lang.String;}——可读、且与源码写法一致）；可变参数按数组处理；</li>
 *   <li>泛型参数取擦除类型：{@code List<User>} 记为 {@code java.util.List}。Java 不允许仅泛型实参不同的重载，
 *       擦除后签名足以区分所有重载。</li>
 * </ul>
 * 例：{@code reserve(long skuId, Integer qty, String orderNo)} → {@code long,java.lang.Integer,java.lang.String}。
 */
public final class MethodSignature {

	private MethodSignature() {
	}

	public static String paramSig(Method method) {
		Class<?>[] types = method.getParameterTypes();
		if (types.length == 0) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < types.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(types[i].getTypeName());
		}
		return sb.toString();
	}

	/** 服务端方法表的键：{@code method(paramSig)}。 */
	public static String methodKey(String methodName, String paramSig) {
		return methodName + "(" + paramSig + ")";
	}
}
