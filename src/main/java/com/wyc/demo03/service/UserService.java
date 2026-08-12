package com.wyc.demo03.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wyc.demo03.entity.User;

public interface UserService extends IService<User> {
    User login(String username, String password);
    void register(User user);
    // 修改密码（校验原密码），返回结果码：success / user_not_found / wrong_password
    String changePassword(Long userId, String oldPassword, String newPassword);
    // 更新个人资料（姓名/邮箱），返回结果码：success / user_not_found
    String updateProfile(Long userId, String name, String email);
}
