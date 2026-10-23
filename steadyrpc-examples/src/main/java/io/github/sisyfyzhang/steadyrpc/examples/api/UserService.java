package io.github.sisyfyzhang.steadyrpc.examples.api;

import java.util.List;

/** 领域服务：provider 内部被 UserFacade 本地调用，同时也对外暴露（演示一个进程暴露多个接口）。 */
public interface UserService {

	void save(User user);

	void saveList(List<User> users);
}
