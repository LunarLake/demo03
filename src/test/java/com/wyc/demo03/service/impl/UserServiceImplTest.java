package com.wyc.demo03.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.wyc.demo03.entity.User;
import com.wyc.demo03.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * 覆盖用户不存在、BCrypt 校验通过/失败、明文密码被拒绝 等分支。
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
    @DisplayName("存量明文密码一律拒绝登录：不再保留明文直通后门")
    void rejectsPlaintextStoredPassword() {
        // Arrange：库里若还有明文行，不能凭它登录
        when(userMapper.findByUsername("olduser")).thenReturn(plainUser);

        // Act
        User result = userService.login("olduser", "plainpass");

        // Assert
        assertNull(result);
        verify(userMapper, never()).update(any(), any());
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

    // ============ changePassword ============

    @Test
    void changePasswordReturnsUserNotFound() {
        // Arrange
        when(userMapper.selectById(99L)).thenReturn(null);

        // Act
        String result = userService.changePassword(99L, "old", "newpass123");

        // Assert
        assertEquals("user_not_found", result);
    }

    @Test
    void changePasswordRejectsWrongOldPassword() {
        // Arrange
        User user = new User();
        user.setId(1L);
        user.setPassword(BCrypt.hashpw("correct-old"));
        when(userMapper.selectById(1L)).thenReturn(user);

        // Act
        String result = userService.changePassword(1L, "wrong-old", "newpass123");

        // Assert
        assertEquals("wrong_password", result);
    }

    @Test
    void changePasswordHashesNewPasswordOnSuccess() {
        // Arrange
        User user = new User();
        user.setId(1L);
        user.setPassword(BCrypt.hashpw("correct-old"));
        when(userMapper.selectById(1L)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        // Act
        String result = userService.changePassword(1L, "correct-old", "newpass123");

        // Assert
        assertEquals("success", result);
        assertTrue(user.getPassword().startsWith("$2a$"));
        assertTrue(BCrypt.checkpw("newpass123", user.getPassword()));
    }

    @Test
    @DisplayName("存量明文密码账号无法自助改密：原密码校验直接失败，需管理员重置")
    void changePasswordRejectsPlaintextStoredPassword() {
        // Arrange：存量明文密码账号
        User user = new User();
        user.setId(1L);
        user.setPassword("plain-old");
        when(userMapper.selectById(1L)).thenReturn(user);

        // Act
        String result = userService.changePassword(1L, "plain-old", "newpass123");

        // Assert
        assertEquals("wrong_password", result);
        verify(userMapper, never()).updateById(any(User.class));
    }

    // ============ updateProfile ============

    @Test
    void updateProfileUpdatesNameAndEmail() {
        // Arrange
        User user = new User();
        user.setId(1L);
        user.setName("旧名字");
        when(userMapper.selectById(1L)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        // Act
        String result = userService.updateProfile(1L, "新名字", "new@example.com");

        // Assert
        assertEquals("success", result);
        assertEquals("新名字", user.getName());
        assertEquals("new@example.com", user.getEmail());
    }

    @Test
    void updateProfileReturnsUserNotFound() {
        // Arrange
        when(userMapper.selectById(99L)).thenReturn(null);

        // Act
        String result = userService.updateProfile(99L, "名字", "x@x.com");

        // Assert
        assertEquals("user_not_found", result);
    }
}
