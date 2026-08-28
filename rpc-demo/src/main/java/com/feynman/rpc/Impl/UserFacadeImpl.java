package com.feynman.rpc.Impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.feynman.rpc.api.annotation.Remote;
import com.feynman.rpc.api.remote.UserFacade;
import com.feynman.rpc.api.service.User;
import com.feynman.rpc.api.service.UserService;



@Remote
@Component
public class UserFacadeImpl implements UserFacade{

	@Autowired
	private UserService userService;

	public User saveUser(User user) {
		userService.save(user);
		return user;
	}

	public void saveUsers(List<User> users) {
		userService.saveList(users);
	}
}
