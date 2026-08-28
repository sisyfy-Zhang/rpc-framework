package com.feynman.rpc.api.service;

import java.util.List;

public interface UserService {

	public void save(User user);

	public void saveList(List<User> users);

}
