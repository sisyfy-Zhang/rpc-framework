package com.feynman.rpc.examples.consumer;

import org.springframework.stereotype.Component;

import com.feynman.rpc.examples.api.User;
import com.feynman.rpc.examples.api.UserFacade;
import com.feynman.rpc.starter.RpcReference;

/** 业务代码里使用远程服务的样子：字段上标 {@code @RpcReference}，像本地接口一样调用。 */
@Component
public class UserGateway {

	@RpcReference(timeout = 5000)
	private UserFacade userFacade;

	public User register(int id, String name) {
		return userFacade.saveUser(new User(id, name));
	}
}
