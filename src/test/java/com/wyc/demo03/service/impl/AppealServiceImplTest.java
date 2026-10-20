package com.wyc.demo03.service.impl;

import com.wyc.demo03.entity.Appeal;
import com.wyc.demo03.entity.CreditLog;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.mapper.AppealMapper;
import com.wyc.demo03.mapper.AuditLogMapper;
import com.wyc.demo03.mapper.CreditLogMapper;
import com.wyc.demo03.mapper.ReservationMapper;
import com.wyc.demo03.service.CreditService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 申诉工单。
 *
 * <p>三条底线：7 日时效、每项只能申诉一次、复核不得自审。
 * 这三条一旦漏掉，申诉就会变成"不满意就再来一次"的无限复议。
 */
@ExtendWith(MockitoExtension.class)
class AppealServiceImplTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 23, 15, 0);
    private static final Long APPLICANT = 7L;
    private static final Long REVIEWER = 9L;

    @Mock private AppealMapper appealMapper;
    @Mock private ReservationMapper reservationMapper;
    @Mock private CreditLogMapper creditLogMapper;
    @Mock private CreditService creditService;
    @Mock private AuditLogMapper auditLogMapper;

    private final MutableClock clock = MutableClock.at(T0);
    private AppealServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AppealServiceImpl(appealMapper, reservationMapper, creditLogMapper,
                creditService, auditLogMapper, clock);
    }

    private Reservation reservation(Long ownerId, int statusCode) {
        Reservation r = new Reservation();
        r.setId(42L);
        r.setUserId(ownerId);
        r.setReservationStatus(statusCode);
        r.setClosedAt(T0.minusDays(1));
        r.setDecisionPath("MANUAL_REJECTED");
        r.setDecisionReason("该时段无法安排");
        return r;
    }

    // ==================== 提交 ====================

    @Test
    void rejectsBlankReason() {
        assertThrows(IllegalArgumentException.class,
                () -> service.submit(APPLICANT, Appeal.TargetType.RESERVATION, 42L, "  ", null));
        verify(appealMapper, never()).insert(any(Appeal.class));
    }

    @Test
    @DisplayName("不能替别人申诉")
    void rejectsForeignReservation() {
        when(reservationMapper.selectById(42L)).thenReturn(reservation(99L, 2));
        assertEquals("只能对自己的预约提交申诉", assertThrows(IllegalArgumentException.class,
                () -> service.submit(APPLICANT, Appeal.TargetType.RESERVATION, 42L, "我有异议", null))
                .getMessage());
    }

    @Test
    @DisplayName("超过 7 日不再受理")
    void rejectsExpiredWindow() {
        Reservation r = reservation(APPLICANT, 2);
        r.setClosedAt(T0.minusDays(8));
        when(reservationMapper.selectById(42L)).thenReturn(r);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.submit(APPLICANT, Appeal.TargetType.RESERVATION, 42L, "我有异议", null));
        assertTrue(ex.getMessage().contains("7 日"));
    }

    @Test
    @DisplayName("提交成功：应办结时间跳过周末（周五 + 3 工作日 = 下周三）")
    void setsDueAtSkippingWeekend() {
        when(reservationMapper.selectById(42L)).thenReturn(reservation(APPLICANT, 2));
        when(appealMapper.insert(any(Appeal.class))).thenReturn(1);

        Appeal appeal = service.submit(APPLICANT, Appeal.TargetType.RESERVATION, 42L, "现场有人占位", "照片已发辅导员");

        ArgumentCaptor<Appeal> captor = ArgumentCaptor.forClass(Appeal.class);
        verify(appealMapper).insert(captor.capture());
        Appeal saved = captor.getValue();

        assertEquals(Appeal.Status.SUBMITTED.name(), saved.getStatus());
        assertEquals(LocalDateTime.of(2026, 10, 28, 15, 0), saved.getDueAt(),
                "2026-10-23 是周五，3 个工作日后是 10-28 周三");
        assertTrue(saved.getOriginalDecision().contains("MANUAL_REJECTED"));
        assertEquals(APPLICANT, saved.getUserId());
    }

    @Test
    @DisplayName("同一目标重复申诉被唯一键挡住，转成可读提示")
    void rejectsDuplicateAppeal() {
        when(reservationMapper.selectById(42L)).thenReturn(reservation(APPLICANT, 2));
        when(appealMapper.insert(any(Appeal.class))).thenThrow(new DuplicateKeyException("uk_user_target"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.submit(APPLICANT, Appeal.TargetType.RESERVATION, 42L, "再申诉一次", null));
        assertTrue(ex.getMessage().contains("只能申诉一次"));
    }

    @Test
    @DisplayName("扣分申诉也要校验归属与时效")
    void creditAppealChecksOwnerAndWindow() {
        CreditLog log = new CreditLog();
        log.setId(5L);
        log.setUserId(APPLICANT);
        log.setDelta(-10);
        log.setReasonCode("NO_SHOW");
        log.setCreatedAt(T0.minusDays(10));
        when(creditLogMapper.selectById(5L)).thenReturn(log);

        assertThrows(IllegalArgumentException.class,
                () -> service.submit(APPLICANT, Appeal.TargetType.CREDIT, 5L, "我没迟到", null));
    }

    // ==================== 复核 ====================

    private Appeal submitted(Long applicant, String targetType, Long targetId) {
        Appeal a = new Appeal();
        a.setId(100L);
        a.setUserId(applicant);
        a.setTargetType(targetType);
        a.setTargetId(targetId);
        a.setStatus(Appeal.Status.SUBMITTED.name());
        a.setReason("有异议");
        return a;
    }

    @Test
    @DisplayName("不能复核自己的申诉")
    void rejectsSelfReview() {
        when(appealMapper.selectById(100L)).thenReturn(submitted(APPLICANT, "RESERVATION", 42L));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.decide(100L, APPLICANT, Appeal.Status.UPHELD, "维持"));
        assertTrue(ex.getMessage().contains("自己"));
    }

    @Test
    @DisplayName("复核人不得是原审核人")
    void rejectsOriginalApprover() {
        when(appealMapper.selectById(100L)).thenReturn(submitted(APPLICANT, "RESERVATION", 42L));
        Reservation r = reservation(APPLICANT, 2);
        r.setApprovedBy(REVIEWER);
        when(reservationMapper.selectById(42L)).thenReturn(r);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.decide(100L, REVIEWER, Appeal.Status.UPHELD, "维持"));
        assertTrue(ex.getMessage().contains("原审核人"));
        verify(appealMapper, never()).updateById(any(Appeal.class));
    }

    @Test
    @DisplayName("已办结的工单不能再改")
    void rejectsAlreadyDecided() {
        Appeal a = submitted(APPLICANT, "RESERVATION", 42L);
        a.setStatus(Appeal.Status.UPHELD.name());
        a.setDecidedAt(T0.minusDays(1));
        when(appealMapper.selectById(100L)).thenReturn(a);

        assertThrows(IllegalStateException.class,
                () -> service.decide(100L, REVIEWER, Appeal.Status.REVOKED, "改判"));
    }

    @Test
    @DisplayName("结论必须是终态，不能把工单「复核成待复核」")
    void rejectsNonTerminalStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> service.decide(100L, REVIEWER, Appeal.Status.SUBMITTED, null));
    }

    @Test
    @DisplayName("扣分申诉成立 → 用 APPEAL_REVOKE 写相反数流水对冲，而不是直接改分数")
    void revokedCreditWritesReversal() {
        when(appealMapper.selectById(100L)).thenReturn(submitted(APPLICANT, "CREDIT", 5L));
        CreditLog original = new CreditLog();
        original.setId(5L);
        original.setUserId(APPLICANT);
        original.setDelta(-10);
        original.setReasonCode("NO_SHOW");
        original.setReservationId(42L);
        when(creditLogMapper.selectById(5L)).thenReturn(original);
        when(appealMapper.updateById(any(Appeal.class))).thenReturn(1);

        Appeal result = service.decide(100L, REVIEWER, Appeal.Status.REVOKED, "签到记录有误");

        assertEquals(Appeal.Status.REVOKED.name(), result.getStatus());
        assertEquals(REVIEWER, result.getReviewerId());
        // ★ 必须是 APPEAL_REVOKE 且 reservationId 传 null：
        //   沿用原原因码会与 (reservation_id, reason_code) 唯一键撞车，撤销会静默失败
        verify(creditService).adjust(
                org.mockito.ArgumentMatchers.eq(APPLICANT),
                org.mockito.ArgumentMatchers.eq(CreditLog.Reason.APPEAL_REVOKE),
                org.mockito.ArgumentMatchers.eq(10),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(100L),
                any(),
                any());
    }

    @Test
    @DisplayName("维持原判不动分数")
    void upheldDoesNotTouchCredit() {
        when(appealMapper.selectById(100L)).thenReturn(submitted(APPLICANT, "CREDIT", 5L));
        CreditLog original = new CreditLog();
        original.setId(5L);
        original.setUserId(APPLICANT);
        original.setDelta(-10);
        original.setReasonCode("NO_SHOW");
        when(creditLogMapper.selectById(5L)).thenReturn(original);
        when(appealMapper.updateById(any(Appeal.class))).thenReturn(1);

        service.decide(100L, REVIEWER, Appeal.Status.UPHELD, "事实清楚");

        verify(creditService, never()).adjust(anyLong(), any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("3 个工作日跳过周末")
    void workingDaysSkipWeekend() {
        // 2026-10-23 周五
        assertEquals(LocalDateTime.of(2026, 10, 28, 15, 0),
                AppealServiceImpl.addWorkingDays(LocalDateTime.of(2026, 10, 23, 15, 0), 3));
        // 2026-10-26 周一
        assertEquals(LocalDateTime.of(2026, 10, 29, 9, 0),
                AppealServiceImpl.addWorkingDays(LocalDateTime.of(2026, 10, 26, 9, 0), 3));
    }
}
