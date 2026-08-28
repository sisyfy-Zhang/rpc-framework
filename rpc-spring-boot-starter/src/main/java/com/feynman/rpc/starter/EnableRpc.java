package com.feynman.rpc.starter;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Import;

import com.feynman.rpc.core.config.RpcProviderConfiguration;

/**
 * "本应用是 RPC 服务提供方"的显式开关，标在 {@code @SpringBootApplication} 类上即可。效果等价于
 * 在 application.yml 里设置 {@code rpc.provider.enabled=true}（两条路径最终装配的都是同一个
 * {@link RpcProviderConfiguration} bean，用其中一种就够，两种都用也不会重复注册）。消费端的代理
 * 装配不需要对应的注解——只要 classpath 上有这个 starter 就会自动生效，因为它本身没有实际副作用，
 * 除非真的有 bean 声明了 {@code @RemoteInvoke} 字段。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(RpcProviderConfiguration.class)
public @interface EnableRpc {
}
