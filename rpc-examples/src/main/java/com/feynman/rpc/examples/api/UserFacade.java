package com.feynman.rpc.examples.api;

import java.util.List;

/** 面向消费端的门面服务：示例 consumer 只引用它。 */
public interface UserFacade {

	User saveUser(User user);

	void saveUsers(List<User> users);
}
