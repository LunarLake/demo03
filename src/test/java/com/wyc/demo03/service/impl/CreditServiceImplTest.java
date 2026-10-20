package com.wyc.demo03.service.impl;

import com.wyc.demo03.entity.CreditAccount;
import com.wyc.demo03.entity.CreditLog;
import com.wyc.demo03.mapper.CreditAccountMapper;
import com.wyc.demo03.mapper.CreditLogMapper;
import com.wyc.demo03.mapper.ReservationMapper;
import com.wyc.demo03.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 信用账本。
 *
 * <p>核心命题：**分数永远是账本的函数**。因此测试重点不是"分数改对了"，
 * 而是"流水不会被重复写"与"分数总能从账本重算出来"。
 */
@ExtendWith(MockitoExtension.class)
class CreditServiceImplTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 20, 12, 0);
    private static final Long USER = 7L;

    @Mock private CreditAccountMapper accountMapper;
    @Mock private CreditLogMapper logMapper;
    @Mock private ReservationMapper reservationMapper;

    private final MutableClock clock = MutableClock.at(T0);
    private CreditServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CreditServiceImpl(accountMapper, logMapper, reservationMapper, clock);
    }

    @Test
    @DisplayName("30 日内同类再犯：扣分加倍，并留一条 REPEAT_DOUBLE 注记")
    void doublesWithinThirtyDays() {
        when(logMapper.countSince(USER, CreditLog.Reason.NO_SHOW.name(), T0.minusDays(30))).thenReturn(1);
        when(logMapper.insert(any(CreditLog.class))).thenReturn(1);
        when(logMapper.sumDelta(USER)).thenReturn(-20);
        CreditAccount account = new CreditAccount();
        account.setUserId(USER);
        account.setScore(100);
        when(accountMapper.selectById(USER)).thenReturn(account);

        assertTrue(service.adjust(USER, CreditLog.Reason.NO_SHOW, null, 42L, null, "SYSTEM", null));

        ArgumentCaptor<CreditLog> captor = ArgumentCaptor.forClass(CreditLog.class);
        verify(logMapper, times(2)).insert(captor.capture());
        List<CreditLog> inserts = captor.getAllValues();

        assertEquals(-20, inserts.get(0).getDelta(), "爽约原 −10，再犯应加倍到 −20");
        assertEquals(CreditLog.Reason.REPEAT_DOUBLE.name(), inserts.get(1).getReasonCode());
        assertEquals(0, inserts.get(1).getDelta(), "注记行不改变分数");
    }

    @Test
    @DisplayName("首次发生不加倍")
    void doesNotDoubleFirstTime() {
        when(logMapper.countSince(USER, CreditLog.Reason.NO_SHOW.name(), T0.minusDays(30))).thenReturn(0);
        when(logMapper.insert(any(CreditLog.class))).thenReturn(1);
        when(logMapper.sumDelta(USER)).thenReturn(-10);
        CreditAccount account = new CreditAccount();
        account.setUserId(USER);
        account.setScore(100);
        when(accountMapper.selectById(USER)).thenReturn(account);

        service.adjust(USER, CreditLog.Reason.NO_SHOW, null, 42L, null, "SYSTEM", null);

        ArgumentCaptor<CreditLog> captor = ArgumentCaptor.forClass(CreditLog.class);
        verify(logMapper, times(1)).insert(captor.capture());
        assertEquals(-10, captor.getValue().getDelta());
    }

    @Test
    @DisplayName("同一预约同一原因被唯一键挡住 → 返回 false，且不重算分数")
    void duplicateIsSwallowed() {
        when(logMapper.countSince(USER, CreditLog.Reason.NO_SHOW.name(), T0.minusDays(30))).thenReturn(0);
        when(logMapper.insert(any(CreditLog.class)))
                .thenThrow(new DuplicateKeyException("uk_res_reason"));

        assertFalse(service.adjust(USER, CreditLog.Reason.NO_SHOW, null, 42L, null, "SYSTEM", null));
        verify(logMapper, never()).sumDelta(anyLong());
    }

    @Test
    @DisplayName("分数下限夹到 0，不出现负分")
    void clampsAtZero() {
        when(logMapper.countSince(anyLong(), anyString(), any())).thenReturn(0);
        when(logMapper.insert(any(CreditLog.class))).thenReturn(1);
        when(logMapper.sumDelta(USER)).thenReturn(-150);
        CreditAccount account = new CreditAccount();
        account.setUserId(USER);
        account.setScore(100);
        when(accountMapper.selectById(USER)).thenReturn(account);

        service.adjust(USER, CreditLog.Reason.VIOLATION, null, 42L, null, "ADMIN", null);

        ArgumentCaptor<CreditAccount> captor = ArgumentCaptor.forClass(CreditAccount.class);
        verify(accountMapper).updateById(captor.capture());
        assertEquals(0, captor.getValue().getScore());
    }

    @Test
    @DisplayName("正向加分不参与加倍判定")
    void positiveNeverDoubles() {
        when(logMapper.insert(any(CreditLog.class))).thenReturn(1);
        when(logMapper.sumDelta(USER)).thenReturn(2);
        CreditAccount account = new CreditAccount();
        account.setUserId(USER);
        account.setScore(100);
        when(accountMapper.selectById(USER)).thenReturn(account);

        service.adjust(USER, CreditLog.Reason.ON_TIME_CHECKIN, null, 42L, null, "SYSTEM", null);

        verify(logMapper, never()).countSince(anyLong(), eq("ON_TIME_CHECKIN"), any());
        ArgumentCaptor<CreditLog> captor = ArgumentCaptor.forClass(CreditLog.class);
        verify(logMapper, times(1)).insert(captor.capture());
        assertEquals(2, captor.getValue().getDelta());
    }

    @Test
    @DisplayName("30 日内已加过分 → 不再重复加")
    void cleanRecordSkipsWhenAlreadyAwarded() {
        when(reservationMapper.findUsersFinishedSince(T0.minusDays(30))).thenReturn(List.of(USER));
        when(logMapper.countSince(USER, CreditLog.Reason.CLEAN_30D.name(), T0.minusDays(30))).thenReturn(1);

        assertEquals(0, service.awardCleanRecord(T0));
        verify(logMapper, never()).insert(any(CreditLog.class));
    }

    @Test
    @DisplayName("30 日内有扣分 → 不算「无爽约」")
    void cleanRecordSkipsWhenNegativeExists() {
        when(reservationMapper.findUsersFinishedSince(T0.minusDays(30))).thenReturn(List.of(USER));
        when(logMapper.countSince(USER, CreditLog.Reason.CLEAN_30D.name(), T0.minusDays(30))).thenReturn(0);
        when(logMapper.countNegativeSince(USER, T0.minusDays(30))).thenReturn(2);

        assertEquals(0, service.awardCleanRecord(T0));
        verify(logMapper, never()).insert(any(CreditLog.class));
    }

    @Test
    @DisplayName("满足条件时加 5 分")
    void cleanRecordAwards() {
        when(reservationMapper.findUsersFinishedSince(T0.minusDays(30))).thenReturn(List.of(USER));
        when(logMapper.countSince(USER, CreditLog.Reason.CLEAN_30D.name(), T0.minusDays(30))).thenReturn(0);
        when(logMapper.countNegativeSince(USER, T0.minusDays(30))).thenReturn(0);
        when(logMapper.insert(any(CreditLog.class))).thenReturn(1);
        when(logMapper.sumDelta(USER)).thenReturn(5);
        CreditAccount account = new CreditAccount();
        account.setUserId(USER);
        account.setScore(100);
        when(accountMapper.selectById(USER)).thenReturn(account);

        assertEquals(1, service.awardCleanRecord(T0));

        ArgumentCaptor<CreditLog> captor = ArgumentCaptor.forClass(CreditLog.class);
        verify(logMapper).insert(captor.capture());
        assertEquals(CreditLog.Reason.CLEAN_30D.name(), captor.getValue().getReasonCode());
        assertEquals(5, captor.getValue().getDelta());
    }
}
