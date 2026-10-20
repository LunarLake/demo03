package com.wyc.demo03.task;

import com.wyc.demo03.mapper.AuditLogMapper;
import com.wyc.demo03.mapper.LogMapper;
import com.wyc.demo03.support.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 日志滚动清理。
 *
 * <p>两个要点：保留窗口必须是 7 天（不是 30 天，也不是"顺手"把信用流水一起删了）；
 * 清理失败不能把定时线程搞挂。
 */
@ExtendWith(MockitoExtension.class)
class LogRotationJobTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 3, 20);

    @Mock private LogMapper logMapper;
    @Mock private AuditLogMapper auditLogMapper;

    private final MutableClock clock = MutableClock.at(NOW);

    @Test
    @DisplayName("滚动窗口是 7 天，两张日志表都清")
    void rotatesSevenDaysBack() {
        when(logMapper.deleteBefore(any())).thenReturn(12);
        when(auditLogMapper.deleteBefore(any())).thenReturn(3);

        new LogRotationJob(logMapper, auditLogMapper, clock).rotate();

        verify(logMapper).deleteBefore(NOW.minusDays(7));
        verify(auditLogMapper).deleteBefore(NOW.minusDays(7));
    }

    @Test
    @DisplayName("清理失败不抛异常，下一轮继续")
    void failureIsSwallowed() {
        when(logMapper.deleteBefore(any())).thenThrow(new IllegalStateException("db busy"));

        assertDoesNotThrow(() -> new LogRotationJob(logMapper, auditLogMapper, clock).rotate());
    }
}
