package com.feynman.rpc.core.extension;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个接口为 SPI 扩展点，并指定默认扩展实现的名字。仿照 Dubbo 的 {@code @SPI} 约定：
 * 实现类声明在 {@code META-INF/rpc/<接口全限定名>} 文件里，格式为 {@code name=impl.ClassName}。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SPI {

	String value() default "";

}
