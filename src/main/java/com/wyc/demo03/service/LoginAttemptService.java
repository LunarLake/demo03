package com.wyc.demo03.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 登录失败限流（内存版）：
 * 按 "用户名|IP" 计数，连续失败达到阈值后锁定一段时间。
 * 锁定到期后记录被惰性清除，重新计数。
 *
 * 注意：基于内存实现，仅适用于单实例部署；重启后计数清零。
 */
@Service
public class LoginAttemptService {

    // 连续失败次数阈值
    static final int MAX_FAILURES = 5;
    // 锁定时长
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final ConcurrentMap<String, AttemptRecord> attempts = new ConcurrentHashMap<>();

    private record AttemptRecord(int failures, LocalDateTime lockedUntil) {
    }

    /**
     * 是否处于锁定状态（锁定未到期返回 true，已到期则清除记录并返回 false）。
     */
    public boolean isBlocked(String username, String ip) {
        AttemptRecord record = attempts.get(key(username, ip));
        if (record == null || record.lockedUntil() == null) {
            return false;
        }
        if (LocalDateTime.now().isBefore(record.lockedUntil())) {
            return true;
        }
        // 锁定已到期 → 惰性清除，重新计数
        attempts.remove(key(username, ip));
        return false;
    }

    /**
     * 记录一次登录失败；达到阈值后进入锁定状态并重置计数。
     */
    public void recordFailure(String username, String ip) {
        attempts.compute(key(username, ip), (k, record) -> {
            int failures = (record == null ? 0 : record.failures()) + 1;
            LocalDateTime lockedUntil = failures >= MAX_FAILURES
                    ? LocalDateTime.now().plus(LOCK_DURATION)
                    : null;
            // 触发锁定时把计数归零，避免锁定到期后立即再次触发
            return new AttemptRecord(lockedUntil != null ? 0 : failures, lockedUntil);
        });
    }

    /**
     * 登录成功后清除该 key 的失败记录。
     */
    public void reset(String username, String ip) {
        attempts.remove(key(username, ip));
    }

    private String key(String username, String ip) {
        return username + "|" + ip;
    }
}
