package com.feynman.rpc.examples.provider;

import java.util.List;

import com.feynman.rpc.examples.api.User;
import com.feynman.rpc.examples.api.UserFacade;
import com.feynman.rpc.examples.api.UserService;
import com.feynman.rpc.starter.RpcService;

/** 暴露给消费端的门面；内部依赖的 UserService 是本地 bean 注入，不经过网络。 */
@RpcService
public class UserFacadeImpl implements UserFacade {

	private final UserService userService;

	public UserFacadeImpl(UserService userService) {
		this.userService = userService;
	}

	@Override
	public User saveUser(User user) {
		userService.save(user);
		return user;
	}

	@Override
	public void saveUsers(List<User> users) {
		userService.saveList(users);
	}
}
