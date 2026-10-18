package com.feynman.rpc.core.protocol;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** paramSig 规则：擦除类型的 getTypeName() 逗号拼接；基本类型用关键字，数组用 [] 后缀，嵌套类用 $。 */
class MethodSignatureTest {

	interface Sample {
		void none();

		void reserve(long skuId, Integer qty, String orderNo);

		void arrays(int[] a, String[][] b, Nested[] c, byte[] d);

		void generic(List<String> names, Map<String, List<Integer>> index);

		void varargs(String... values);

		void overloaded(int x);

		void overloaded(Integer x);

		final class Nested {
		}
	}

	private static String sig(String name, Class<?>... types) throws NoSuchMethodException {
		return MethodSignature.paramSig(Sample.class.getMethod(name, types));
	}

	@Test
	void followsTheDocumentedRules() throws Exception {
		assertEquals("", sig("none"));
		assertEquals("long,java.lang.Integer,java.lang.String", sig("reserve", long.class, Integer.class, String.class));
		assertEquals("int[],java.lang.String[][],com.feynman.rpc.core.protocol.MethodSignatureTest$Sample$Nested[],byte[]",
				sig("arrays", int[].class, String[][].class, Sample.Nested[].class, byte[].class));
		assertEquals("java.util.List,java.util.Map", sig("generic", List.class, Map.class));
		assertEquals("java.lang.String[]", sig("varargs", String[].class));
	}

	@Test
	void primitiveAndWrapperOverloadsAreDistinct() throws Exception {
		assertNotEquals(sig("overloaded", int.class), sig("overloaded", Integer.class));
		assertEquals("overloaded(int)", MethodSignature.methodKey("overloaded", sig("overloaded", int.class)));
	}
}
