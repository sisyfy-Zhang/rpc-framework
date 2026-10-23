package io.github.sisyfyzhang.steadyrpc.examples.consumer;

import org.springframework.stereotype.Component;

import io.github.sisyfyzhang.steadyrpc.examples.api.User;
import io.github.sisyfyzhang.steadyrpc.examples.api.UserFacade;
import io.github.sisyfyzhang.steadyrpc.starter.RpcReference;

/** 业务代码里使用远程服务的样子：字段上标 {@code @RpcReference}，像本地接口一样调用。 */
@Component
public class UserGateway {

	@RpcReference(timeout = 5000)
	private UserFacade userFacade;

	public User register(int id, String name) {
		return userFacade.saveUser(new User(id, name));
	}
}
