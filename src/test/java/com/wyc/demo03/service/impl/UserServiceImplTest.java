package com.wyc.demo03.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.wyc.demo03.entity.User;
import com.wyc.demo03.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UserServiceImpl.login() 的单元测试：
 * 覆盖用户不存在、BCrypt 校验通过/失败、明文密码登录即迁移、明文密码错误五个分支。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;

    private UserServiceImpl userService;

    private User plainUser;
    private User hashedUser;

    @BeforeEach
    void setUp() {
        // ServiceImpl 的父类字段 baseMapper 无法被 @InjectMocks 注入，显式反射注入
        userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "baseMapper", userMapper);

        // Arrange：明文密码存量账号
        plainUser = new User();
        plainUser.setId(1L);
        plainUser.setUsername("olduser");
        plainUser.setPassword("plainpass");
        plainUser.setRole("STUDENT");

        // Arrange：BCrypt 哈希账号
        hashedUser = new User();
        hashedUser.setId(2L);
        hashedUser.setUsername("newuser");
        hashedUser.setPassword(BCrypt.hashpw("secret123"));
        hashedUser.setRole("STUDENT");
    }

    @Test
    void returnsNullWhenUserNotFound() {
        // Arrange
        when(userMapper.findByUsername("ghost")).thenReturn(null);

        // Act
        User result = userService.login("ghost", "whatever");

        // Assert
        assertNull(result);
    }

    @Test
    void returnsUserWhenBcryptPasswordMatches() {
        // Arrange
        when(userMapper.findByUsername("newuser")).thenReturn(hashedUser);

        // Act
        User result = userService.login("newuser", "secret123");

        // Assert
        assertNotNull(result);
        assertEquals("newuser", result.getUsername());
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    void returnsNullWhenBcryptPasswordWrong() {
        // Arrange
        when(userMapper.findByUsername("newuser")).thenReturn(hashedUser);

        // Act
        User result = userService.login("newuser", "wrongpass");

        // Assert
        assertNull(result);
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    void migratesPlaintextPasswordToBcryptOnSuccessfulLogin() {
        // Arrange
        when(userMapper.findByUsername("olduser")).thenReturn(plainUser);
        when(userMapper.update(any(), any())).thenReturn(1);

        // Act
        User result = userService.login("olduser", "plainpass");

        // Assert
        assertNotNull(result);
        // 返回对象上的密码已替换为 BCrypt 哈希
        assertTrue(result.getPassword().startsWith("$2a$"));
        assertTrue(BCrypt.checkpw("plainpass", result.getPassword()));
        // 写库语句的参数值中密码同样为 BCrypt 哈希（登录即迁移）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<AbstractWrapper<User, ?, ?>> wrapperCaptor = ArgumentCaptor.forClass(AbstractWrapper.class);
        verify(userMapper).update(isNull(), wrapperCaptor.capture());
        assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().values().stream()
                .anyMatch(v -> v instanceof String s && s.startsWith("$2a$")));
    }

    @Test
    void returnsNullWhenPlaintextPasswordWrong() {
        // Arrange
        when(userMapper.findByUsername("olduser")).thenReturn(plainUser);

        // Act
        User result = userService.login("olduser", "wrongpass");

        // Assert
        assertNull(result);
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    void returnsNullWhenUsernameMissing() {
        // Arrange
        when(userMapper.findByUsername("ghost")).thenReturn(null);

        // Act
        User result = userService.login("ghost", "plainpass");

        // Assert
        assertNull(result);
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    void forcesStudentRoleOnRegister() {
        // Arrange
        User forged = new User();
        forged.setUsername("evil");
        forged.setPassword("123456");
        forged.setName("伪造者");
        forged.setRole("ADMIN");
        when(userMapper.insert(any(User.class))).thenReturn(1);

        // Act
        userService.register(forged);

        // Assert
        assertEquals("STUDENT", forged.getRole());
        assertTrue(forged.getPassword().startsWith("$2a$"));
        verify(userMapper).insert(eq(forged));
    }
}
