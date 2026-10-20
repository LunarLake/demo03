package com.wyc.demo03.task;

import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.service.CreditService;
import com.wyc.demo03.service.DeviceCommandService;
import com.wyc.demo03.service.ReservationService;
import com.wyc.demo03.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 定时任务的编排测试。
 *
 * <p>v1 这个类断言的是 Mapper 里的 SQL 字符串（用反射取注解值），一旦 SQL 改写就红一片，
 * 而且并不能证明"顺序对、异常不炸调度"。v2 改为验证编排行为：
 * 四步按固定顺序调用、单轮异常被吞掉、时间来自注入的时钟。
 */
@ExtendWith(MockitoExtension.class)
class ReservationExpiryTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 20, 14, 0);

    @Mock
    private ReservationService reservationService;
    @Mock
    private CreditService creditService;
    @Mock
    private DeviceCommandService deviceCommandService;

    private final MutableClock clock = MutableClock.at(T0);
    private ReservationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ReservationScheduler(reservationService, creditService, deviceCommandService, clock);
    }

    @Test
    @DisplayName("一轮收敛按「释放待审批 → 释放爽约 → 到期结束 → 结束提醒」顺序执行")
    void convergeRunsStepsInOrder() {
        scheduler.converge();

        InOrder order = inOrder(reservationService);
        order.verify(reservationService).releasePendingStarted(T0);
        order.verify(reservationService).releaseNoShow(T0);
        order.verify(reservationService).finishExpired(T0);
        order.verify(reservationService).remindEnding(T0);
    }

    @Test
    @DisplayName("单轮抛异常不能终止调度：异常被吞掉，调度线程存活")
    void exceptionDoesNotKillScheduler() {
        when(reservationService.releasePendingStarted(any()))
                .thenThrow(new IllegalStateException("数据库抖了一下"));

        assertDoesNotThrow(() -> scheduler.converge());
        verify(reservationService).releasePendingStarted(T0);
    }

    @Test
    @DisplayName("提醒结果用于日志统计，不抛异常")
    void reminderResultIsTolerated() {
        Reservation r = new Reservation();
        r.setId(1L);
        when(reservationService.remindEnding(T0)).thenReturn(List.of(r));

        assertDoesNotThrow(() -> scheduler.converge());
        verify(reservationService).remindEnding(T0);
    }

    @Test
    @DisplayName("「30 日无爽约」加分是独立的每日任务，不混在每分钟的收敛里")
    void cleanRecordIsSeparateDailyJob() {
        when(creditService.awardCleanRecord(T0)).thenReturn(2);

        scheduler.awardCleanRecords();

        verify(creditService).awardCleanRecord(T0);
        // 每分钟的收敛不应触发加分
        scheduler.converge();
        verify(creditService, org.mockito.Mockito.times(1)).awardCleanRecord(T0);
    }

    @Test
    @DisplayName("加分任务异常也不影响调度线程")
    void cleanRecordFailureIsSwallowed() {
        when(creditService.awardCleanRecord(T0)).thenThrow(new IllegalStateException("boom"));
        assertDoesNotThrow(() -> scheduler.awardCleanRecords());
    }
}
