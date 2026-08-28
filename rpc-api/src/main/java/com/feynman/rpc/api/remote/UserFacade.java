package com.feynman.rpc.api.remote;

import java.util.List;

import com.feynman.rpc.api.service.User;


public interface UserFacade {

	public User saveUser(User user);
	public void saveUsers(List<User> users);
	
}
