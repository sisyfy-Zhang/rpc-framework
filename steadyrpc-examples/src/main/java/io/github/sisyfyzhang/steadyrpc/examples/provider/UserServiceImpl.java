package io.github.sisyfyzhang.steadyrpc.examples.provider;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.sisyfyzhang.steadyrpc.examples.api.User;
import io.github.sisyfyzhang.steadyrpc.examples.api.UserService;
import io.github.sisyfyzhang.steadyrpc.starter.RpcService;

/**
 * 参数按方法签名上的泛型类型反序列化：远程传来的 {@code List<User>} 元素就是 User，而不是 Map / JSONObject。
 */
@RpcService
public class UserServiceImpl implements UserService {

	private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

	@Override
	public void save(User user) {
		log.info("UserServiceImpl.save called with {}", user);
	}

	@Override
	public void saveList(List<User> users) {
		log.info("UserServiceImpl.saveList called with {} users, first={}", users.size(), users.isEmpty() ? null : users.get(0).getName());
	}
}
