package com.wyc.demo03.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wyc.demo03.entity.User;

public interface UserService extends IService<User> {
    User login(String username, String password);
    void register(User user);
}
