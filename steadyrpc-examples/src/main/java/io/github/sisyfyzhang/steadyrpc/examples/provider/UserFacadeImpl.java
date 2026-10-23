package io.github.sisyfyzhang.steadyrpc.examples.provider;

import java.util.List;

import io.github.sisyfyzhang.steadyrpc.examples.api.User;
import io.github.sisyfyzhang.steadyrpc.examples.api.UserFacade;
import io.github.sisyfyzhang.steadyrpc.examples.api.UserService;
import io.github.sisyfyzhang.steadyrpc.starter.RpcService;

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
