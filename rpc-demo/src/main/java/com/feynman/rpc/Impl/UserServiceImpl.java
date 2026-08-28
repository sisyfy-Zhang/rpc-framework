package com.feynman.rpc.Impl;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.feynman.rpc.api.annotation.Remote;
import com.feynman.rpc.api.service.User;
import com.feynman.rpc.api.service.UserService;

@Remote
@Service
public class UserServiceImpl implements UserService{

	private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

	@Override
	public void save(User user) {
		log.info("UserServiceImpl.save called with {}", user);
	}

	@Override
	public void saveList(List<User> users) {
		log.info("UserServiceImpl.saveList called with {} users", users.size());
	}
}
