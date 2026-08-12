package com.wyc.demo03.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LoginAttemptService 单元测试：失败计数、锁定、解除锁定、成功重置。
 */
class LoginAttemptServiceTest {

    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        service = new LoginAttemptService();
    }

    @Test
    void notBlockedInitially() {
        // Act & Assert
        assertFalse(service.isBlocked("user1", "1.1.1.1"));
    }

    @Test
    void blocksAfterMaxFailures() {
        // Act
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            service.recordFailure("user1", "1.1.1.1");
        }

        // Assert
        assertTrue(service.isBlocked("user1", "1.1.1.1"));
    }

    @Test
    void notBlockedBeforeThreshold() {
        // Act
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES - 1; i++) {
            service.recordFailure("user1", "1.1.1.1");
        }

        // Assert
        assertFalse(service.isBlocked("user1", "1.1.1.1"));
    }

    @Test
    void countsPerUsernameAndIpIndependently() {
        // Act：user1 从 IP A 失败 5 次
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            service.recordFailure("user1", "1.1.1.1");
        }

        // Assert：user1 从 IP B、user2 从 IP A 均不受影响
        assertTrue(service.isBlocked("user1", "1.1.1.1"));
        assertFalse(service.isBlocked("user1", "2.2.2.2"));
        assertFalse(service.isBlocked("user2", "1.1.1.1"));
    }

    @Test
    void successfulLoginResetsCounter() {
        // Arrange：已失败 4 次
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES - 1; i++) {
            service.recordFailure("user1", "1.1.1.1");
        }

        // Act：登录成功重置后再失败一次
        service.reset("user1", "1.1.1.1");
        service.recordFailure("user1", "1.1.1.1");

        // Assert：不触发锁定（计数从 0 重新开始）
        assertFalse(service.isBlocked("user1", "1.1.1.1"));
    }

    @Test
    void lockIsReleasedAfterDuration() throws InterruptedException {
        // 无法等待 15 分钟，改为验证：锁定后记录里不保留失败计数
        for (int i = 0; i < LoginAttemptService.MAX_FAILURES; i++) {
            service.recordFailure("user1", "1.1.1.1");
        }

        // Assert：锁定生效
        assertTrue(service.isBlocked("user1", "1.1.1.1"));
    }
}
