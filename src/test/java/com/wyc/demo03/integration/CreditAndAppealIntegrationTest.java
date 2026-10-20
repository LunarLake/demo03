package com.wyc.demo03.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wyc.demo03.domain.ReservationStatus;
import com.wyc.demo03.domain.RuleParams;
import com.wyc.demo03.entity.Appeal;
import com.wyc.demo03.entity.CreditLog;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.entity.User;
import com.wyc.demo03.mapper.CreditAccountMapper;
import com.wyc.demo03.mapper.CreditLogMapper;
import com.wyc.demo03.mapper.ReservationMapper;
import com.wyc.demo03.service.AppealService;
import com.wyc.demo03.service.CreditService;
import com.wyc.demo03.service.ReservationService;
import com.wyc.demo03.service.RuleConfigService;
import com.wyc.demo03.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 信用账本与申诉的真库集成测试。
 *
 * <p>单测能证明逻辑分支，证明不了"唯一键真的挡住了重复扣分""申诉对冲后分数回到原值"。
 * 这两件事直接决定用户会不会被冤枉扣两次分，必须在真库上验。
 */
@SpringBootTest
@Transactional
class CreditAndAppealIntegrationTest {

    @Autowired private ReservationService reservationService;
    @Autowired private CreditService creditService;
    @Autowired private AppealService appealService;
    @Autowired private RuleConfigService ruleConfigService;
    @Autowired private ReservationMapper reservationMapper;
    @Autowired private CreditLogMapper creditLogMapper;
    @Autowired private CreditAccountMapper creditAccountMapper;
    @Autowired private com.wyc.demo03.mapper.ReservationSlotMapper slotMapper;
    @Autowired private UserService userService;

    private User student;
    private Long adminId;

    @BeforeEach
    void setUp() {
        student = userService.lambdaQuery().eq(User::getUsername, "student01").one();
        adminId = userService.lambdaQuery().eq(User::getUsername, "admin01").one().getId();
        assertNotNull(student);

        // 清掉该用户的历史信用流水并复位分数，保证断言的是"本次操作的效果"
        creditLogMapper.delete(new LambdaQueryWrapper<CreditLog>()
                .eq(CreditLog::getUserId, student.getId()));
        var account = creditAccountMapper.selectById(student.getId());
        if (account != null) {
            account.setScore(100);
            account.setUpdatedAt(LocalDateTime.now());
            creditAccountMapper.updateById(account);
        }
    }

    /** 直接插一笔已通过、且开始时刻在过去（已过签到截止）的预约。 */
    private Reservation insertOverdue() {
        Reservation r = new Reservation();
        r.setUserId(student.getId());
        r.setRoomId(1L);
        LocalDateTime start = LocalDateTime.now().minusMinutes(30).withSecond(0).withNano(0);
        r.setStartTime(start);
        r.setEndTime(start.plusMinutes(45));
        r.setAttendeeCount(2);
        r.setReservationStatus(ReservationStatus.APPROVED.code());
        reservationMapper.insert(r);
        return r;
    }

    private int score() {
        return creditService.scoreOf(student.getId());
    }

    private long countLogs(CreditLog.Reason reason) {
        return creditLogMapper.selectCount(new LambdaQueryWrapper<CreditLog>()
                .eq(CreditLog::getUserId, student.getId())
                .eq(CreditLog::getReasonCode, reason.name()));
    }

    // ====================================================================

    @Test
    @DisplayName("爽约扣分只发生一次：调度重复扫描不会重复扣")
    void noShowDeductsOnlyOnce() {
        insertOverdue();

        reservationService.releaseNoShow(LocalDateTime.now());
        reservationService.releaseNoShow(LocalDateTime.now());

        assertEquals(1, countLogs(CreditLog.Reason.NO_SHOW), "同一次爽约只能有一条流水");
        assertEquals(90, score());
    }

    @Test
    @DisplayName("已签到并正常结束加 2 分（分数上限 100，所以看流水更可靠）")
    void onTimeFinishAwardsTwoPoints() {
        Reservation r = insertOverdue();
        r.setReservationStatus(ReservationStatus.CHECKED_IN.code());
        r.setEndTime(LocalDateTime.now().minusMinutes(1));
        reservationMapper.updateById(r);

        reservationService.finishExpired(LocalDateTime.now());

        assertEquals(1, countLogs(CreditLog.Reason.ON_TIME_CHECKIN));
        CreditLog award = creditLogMapper.selectList(new LambdaQueryWrapper<CreditLog>()
                .eq(CreditLog::getUserId, student.getId())
                .eq(CreditLog::getReasonCode, CreditLog.Reason.ON_TIME_CHECKIN.name())).get(0);
        assertEquals(2, award.getDelta());
    }

    @Test
    @DisplayName("30 日内再犯加倍：第二条爽约扣 20 分")
    void repeatNoShowDoubles() {
        insertOverdue();
        reservationService.releaseNoShow(LocalDateTime.now());
        assertEquals(90, score());

        insertOverdue();
        reservationService.releaseNoShow(LocalDateTime.now());

        assertEquals(2, countLogs(CreditLog.Reason.NO_SHOW));
        assertEquals(1, countLogs(CreditLog.Reason.REPEAT_DOUBLE), "必须留下加倍说明");
        assertEquals(70, score(), "第二次应扣 20（−10 加倍）");
    }

    @Test
    @DisplayName("申诉成立：写相反数流水对冲，分数回到原值，但单元不恢复")
    void appealRevokeRestoresScore() {
        Reservation reservation = insertOverdue();
        reservationService.releaseNoShow(LocalDateTime.now());
        assertEquals(90, score());

        CreditLog deduction = creditLogMapper.selectList(new LambdaQueryWrapper<CreditLog>()
                .eq(CreditLog::getUserId, student.getId())
                .eq(CreditLog::getReasonCode, CreditLog.Reason.NO_SHOW.name()))
                .get(0);

        Appeal appeal = appealService.submit(student.getId(), Appeal.TargetType.CREDIT,
                deduction.getId(), "当天系统故障未能签到", null);
        appealService.decide(appeal.getId(), adminId, Appeal.Status.REVOKED, "核实属实，撤销扣分");

        assertEquals(100, score(), "撤销后应回到 100");
        assertEquals(1, countLogs(CreditLog.Reason.APPEAL_REVOKE));

        // ★ 关键：申诉成立只撤销扣分，绝不恢复时段。
        //   时段早就可能被别人订走了，恢复等于制造重叠占用。
        assertEquals(0, slotMapper.findByReservationId(reservation.getId()).size(),
                "申诉成立不能恢复占用单元");
        assertEquals(ReservationStatus.TIMEOUT_RELEASED.code(),
                reservationMapper.selectById(reservation.getId()).getReservationStatus(),
                "预约状态也不该被申诉改回来");
    }

    @Test
    @DisplayName("同一项扣分只能申诉一次")
    void appealIsOneShot() {
        insertOverdue();
        reservationService.releaseNoShow(LocalDateTime.now());
        CreditLog deduction = creditLogMapper.selectList(new LambdaQueryWrapper<CreditLog>()
                .eq(CreditLog::getUserId, student.getId())
                .eq(CreditLog::getReasonCode, CreditLog.Reason.NO_SHOW.name())).get(0);

        appealService.submit(student.getId(), Appeal.TargetType.CREDIT, deduction.getId(), "第一次", null);

        assertThrows(IllegalArgumentException.class, () -> appealService.submit(
                student.getId(), Appeal.TargetType.CREDIT, deduction.getId(), "第二次", null));
    }

    @Test
    @DisplayName("复核人不能是原审核人")
    void cannotSelfReviewAsOriginalApprover() {
        Reservation r = insertOverdue();
        r.setReservationStatus(ReservationStatus.REJECTED.code());
        r.setApprovedBy(adminId);
        r.setClosedAt(LocalDateTime.now());
        reservationMapper.updateById(r);

        Appeal appeal = appealService.submit(student.getId(), Appeal.TargetType.RESERVATION,
                r.getId(), "时段其实是空的", null);

        assertThrows(IllegalStateException.class,
                () -> appealService.decide(appeal.getId(), adminId, Appeal.Status.REVOKED, "改判"));
    }

    @Test
    @DisplayName("发布新规则版本后，判定立刻按新参数执行")
    void publishedRuleTakesEffect() {
        RuleParams before = ruleConfigService.currentParams();
        assertEquals(90, before.autoApproveCredit());

        Map<String, String> submitted = Map.of("AUTO_APPROVE_CREDIT", "95");
        ruleConfigService.publish(submitted, adminId, "集成测试：抬高自动通过线");

        RuleParams after = ruleConfigService.currentParams();
        assertEquals(95, after.autoApproveCredit());
        assertEquals(before.midCreditMin(), after.midCreditMin(), "未提交的参数应保持原值");
        assertTrue(ruleConfigService.history().size() >= 2, "应能看到历史版本");
    }

    @Test
    @DisplayName("非法参数不会落库——发布失败时版本号不前进")
    void invalidPublishDoesNotAdvanceVersion() {
        Integer before = ruleConfigService.currentVersion() == null
                ? null : ruleConfigService.currentVersion().getVersionNo();

        assertThrows(IllegalArgumentException.class,
                () -> ruleConfigService.publish(Map.of("AUTO_APPROVE_CREDIT", "999"), adminId, "越界"));

        Integer after = ruleConfigService.currentVersion() == null
                ? null : ruleConfigService.currentVersion().getVersionNo();
        assertEquals(before, after);
    }
}
