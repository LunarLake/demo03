package com.wyc.demo03.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wyc.demo03.common.RoleConstant;
import com.wyc.demo03.entity.User;
import com.wyc.demo03.mapper.UserMapper;
import com.wyc.demo03.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
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
        if (verifyPassword(user, password)) {
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

    @Override
    public String changePassword(Long userId, String oldPassword, String newPassword) {
        User user = getById(userId);
        if (user == null) {
            return "user_not_found";
        }
        if (!verifyPassword(user, oldPassword)) {
            return "wrong_password";
        }
        user.setPassword(BCrypt.hashpw(newPassword));
        updateById(user);
        return "success";
    }

    @Override
    public String updateProfile(Long userId, String name, String email) {
        User user = getById(userId);
        if (user == null) {
            return "user_not_found";
        }
        user.setName(name);
        user.setEmail(email);
        updateById(user);
        return "success";
    }

    /**
     * 校验输入密码与库中密码是否匹配。**只接受 BCrypt**。
     *
     * <p>历史上这里留了一条"非哈希则明文比对并登录即迁移"的降级路径。
     * 它意味着库里只要存在一行明文，就等于开了一个后门：任何拿到库的人都能用它登录。
     * schema-v2 的种子账号已是 BCrypt，这里不再保留降级。
     * 若线上仍有明文行，请先用一次性脚本哈希，否则该账号会登录失败（日志有明确告警）。
     */
    private boolean verifyPassword(User user, String input) {
        String stored = user.getPassword();
        if (stored == null || input == null) {
            return false;
        }
        if (!stored.startsWith(BCRYPT_PREFIX)) {
            log.warn("账号 {} 的密码不是 BCrypt 格式（可能为历史明文），已拒绝登录", user.getUsername());
            return false;
        }
        return BCrypt.checkpw(input, stored);
    }
}
