package com.wyc.demo03.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wyc.demo03.common.RoleConstant;
import com.wyc.demo03.entity.User;
import com.wyc.demo03.mapper.UserMapper;
import com.wyc.demo03.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Override
    public User login(String username, String password) {
        User user = baseMapper.findByUsername(username);
        if (user != null && BCrypt.checkpw(password, user.getPassword())) {
            return user;
        }
        return null;
    }

    @Override
    public void register(User user) {
        // 强制学生角色：防止伪造表单提交 role=TEACHER/ADMIN 越权注册
        user.setRole(RoleConstant.STUDENT);
        user.setPassword(BCrypt.hashpw(user.getPassword()));
        super.save(user);
    }
}
