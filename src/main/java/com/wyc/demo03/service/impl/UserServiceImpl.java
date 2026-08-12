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

    // Hutool BCrypt.hashpw() 生成的哈希前缀；用于区分"已哈希密码"与"存量明文密码"
    private static final String BCRYPT_PREFIX = "$2a$";

    @Override
    public User login(String username, String password) {
        User user = baseMapper.findByUsername(username);
        if (user == null) {
            return null;
        }
        String stored = user.getPassword();
        // 已哈希密码：直接 BCrypt 校验
        if (stored.startsWith(BCRYPT_PREFIX)) {
            return BCrypt.checkpw(password, stored) ? user : null;
        }
        // 存量明文密码（历史数据）：明文比对，成功后立即重哈希写回 —— 登录即迁移。
        // 全部存量账号迁移完成后可移除此分支。
        if (stored.equals(password)) {
            String hashed = BCrypt.hashpw(password);
            lambdaUpdate().eq(User::getId, user.getId())
                    .set(User::getPassword, hashed)
                    .update();
            user.setPassword(hashed);
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
