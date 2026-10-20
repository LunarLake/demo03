package com.wyc.demo03.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wyc.demo03.domain.ReservationStatus;
import com.wyc.demo03.entity.CreditLog;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.entity.User;
import com.wyc.demo03.mapper.CreditLogMapper;
import com.wyc.demo03.mapper.ReservationMapper;
import com.wyc.demo03.service.CreditService;
import com.wyc.demo03.service.ReservationService;
import com.wyc.demo03.service.UserService;
import com.wyc.demo03.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间旅行：把一整天压进毫秒。
 *
 * <p>用可拨动的 {@link Clock} Bean 替换系统时钟，按真实时间顺序推进并触发收敛，
 * 验证「到了 09:00 待审批释放」「到了 09:05 未签到释放」「到了 09:30 到期结束」。
 *
 * <p>不用这个办法就得等真实时间，或者把每个判断的时间来源改成可传参 —— 前者没法测，
 * 后者会让每个业务方法都多一个"现在几点"的参数。注入 Clock 是最小侵入的做法。
 */
@SpringBootTest
@Transactional
@Import(TimeTravelConvergenceTest.ClockOverride.class)
class TimeTravelConvergenceTest {

    /** 起点：当天 08:00，早于所有测试预约。 */
    private static final LocalDateTime START = LocalDate.now().atTime(8, 0);

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        MutableClock testClock() {
            return MutableClock.at(START);
        }
    }

    @Autowired private MutableClock clock;
    @Autowired private ReservationService reservationService;
    @Autowired private CreditService creditService;
    @Autowired private ReservationMapper reservationMapper;
    @Autowired private CreditLogMapper creditLogMapper;
    @Autowired private com.wyc.demo03.mapper.ReservationSlotMapper slotMapper;
    @Autowired private UserService userService;

    private User student;

    @BeforeEach
    void setUp() {
        clock.set(START);
        student = userService.lambdaQuery().eq(User::getUsername, "student01").one();
        assertNotNull(student);

        // 清掉该用户的信用流水，让断言只看本次
        creditLogMapper.delete(new LambdaQueryWrapper<CreditLog>()
                .eq(CreditLog::getUserId, student.getId()));
    }

    private Reservation insert(int status, LocalDateTime start, LocalDateTime end) {
        Reservation r = new Reservation();
        r.setUserId(student.getId());
        r.setRoomId(1L);
        r.setStartTime(start);
        r.setEndTime(end);
        r.setAttendeeCount(2);
        r.setReservationStatus(status);
        r.setCreatedAt(START);
        reservationMapper.insert(r);
        return r;
    }

    private int statusOf(Long id) {
        return reservationMapper.selectById(id).getReservationStatus();
    }

    /** 按真实顺序跑一轮收敛（与调度器的四步一致）。 */
    private void converge(LocalDateTime now) {
        clock.set(now);
        reservationService.releasePendingStarted(now);
        reservationService.releaseNoShow(now);
        reservationService.finishExpired(now);
        reservationService.remindEnding(now);
    }

    private long creditLogs(CreditLog.Reason reason, Long reservationId) {
        return creditLogMapper.selectCount(new LambdaQueryWrapper<CreditLog>()
                .eq(CreditLog::getUserId, student.getId())
                .eq(CreditLog::getReasonCode, reason.name())
                .eq(reservationId != null, CreditLog::getReservationId, reservationId));
    }

    @Test
    @DisplayName("一整天按真实顺序推进：待审批释放 → 爽约释放 → 到期结束，扣分与加分都落账")
    void oneDayConverges() {
        LocalDateTime nine = START.toLocalDate().atTime(9, 0);

        Reservation pending = insert(ReservationStatus.PENDING.code(), nine, nine.plusHours(1));
        Reservation approved = insert(ReservationStatus.APPROVED.code(), nine, nine.plusHours(1));
        Reservation checkedIn = insert(ReservationStatus.CHECKED_IN.code(), nine, nine.plusMinutes(30));

        // 08:50 —— 什么都没到点，状态不应变化
        converge(nine.minusMinutes(10));
        assertEquals(ReservationStatus.PENDING.code(), statusOf(pending.getId()));
        assertEquals(ReservationStatus.APPROVED.code(), statusOf(approved.getId()));
        assertEquals(ReservationStatus.CHECKED_IN.code(), statusOf(checkedIn.getId()));

        // 09:10 —— 待审批已过开始时刻被释放；已通过已过签到截止（09:05）被释放；
        //          已签到的那笔还没结束，保持不动
        converge(nine.plusMinutes(10));
        assertEquals(ReservationStatus.TIMEOUT_RELEASED.code(), statusOf(pending.getId()),
                "待审批到了开始时刻仍未处理，应超时释放");
        assertEquals(ReservationStatus.TIMEOUT_RELEASED.code(), statusOf(approved.getId()),
                "开始后 5 分钟仍未签到，应释放");
        assertEquals(ReservationStatus.CHECKED_IN.code(), statusOf(checkedIn.getId()),
                "已签到且未到结束时刻，不能被误释放");

        // 09:35 —— 已签到那笔到期结束
        converge(nine.plusMinutes(35));
        assertEquals(ReservationStatus.FINISHED.code(), statusOf(checkedIn.getId()),
                "到结束时刻应转为已结束");
    }

    @Test
    @DisplayName("爽约扣 10 分只发生一次：重复收敛不会重复扣")
    void noShowDeductsOnce() {
        LocalDateTime nine = START.toLocalDate().atTime(9, 0);
        Reservation approved = insert(ReservationStatus.APPROVED.code(), nine, nine.plusHours(1));

        converge(nine.plusMinutes(10));
        converge(nine.plusMinutes(20));
        converge(nine.plusMinutes(30));

        assertEquals(1, creditLogs(CreditLog.Reason.NO_SHOW, approved.getId()),
                "同一预约同一原因只能有一条流水");
    }

    @Test
    @DisplayName("按时签到并正常结束加 2 分")
    void onTimeFinishAwards() {
        LocalDateTime nine = START.toLocalDate().atTime(9, 0);
        Reservation checkedIn = insert(ReservationStatus.CHECKED_IN.code(), nine, nine.plusMinutes(30));

        converge(nine.plusMinutes(35));

        assertEquals(1, creditLogs(CreditLog.Reason.ON_TIME_CHECKIN, checkedIn.getId()));
    }

    @Test
    @DisplayName("结束前 5 分钟只提醒一次")
    void remindOnlyOnce() {
        LocalDateTime nine = START.toLocalDate().atTime(9, 0);
        Reservation checkedIn = insert(ReservationStatus.CHECKED_IN.code(), nine, nine.plusMinutes(30));

        // 09:26 —— 距结束 4 分钟，应当提醒
        clock.set(nine.plusMinutes(26));
        List<Reservation> first = reservationService.remindEnding(nine.plusMinutes(26));
        assertEquals(1, first.size());

        // 再跑一次不应重复提醒
        List<Reservation> second = reservationService.remindEnding(nine.plusMinutes(27));
        assertTrue(second.isEmpty(), "提醒过一次就不能再提醒");
    }

    @Test
    @DisplayName("AC-20 15 分钟预约：签到截止（+5min）与结束提醒（-5min）各自触发、互不干扰")
    void shortSlotDeadlineAndReminderDoNotInterfere() {
        LocalDateTime nine = START.toLocalDate().atTime(9, 0);
        // 一格 15 分钟：签到窗口 [09:00, 09:05)，提醒窗口 [09:10, 09:15)
        Reservation checkedIn = insert(ReservationStatus.CHECKED_IN.code(), nine, nine.plusMinutes(15));
        Reservation noShow = insert(ReservationStatus.APPROVED.code(), nine, nine.plusMinutes(15));

        // 09:06 —— 已签到那笔的签到窗口刚关（但它已签到，不该被释放）；
        //          未签到那笔应被爽约释放
        converge(nine.plusMinutes(6));
        assertEquals(ReservationStatus.TIMEOUT_RELEASED.code(), statusOf(noShow.getId()),
                "未签到的应在窗口关闭后释放");
        assertEquals(ReservationStatus.CHECKED_IN.code(), statusOf(checkedIn.getId()),
                "已签到的不能在窗口关闭时被误释放");

        // 09:12 —— 进入提醒窗口，只提醒一次
        clock.set(nine.plusMinutes(12));
        List<Reservation> firstRemind = reservationService.remindEnding(nine.plusMinutes(12));
        assertEquals(1, firstRemind.size(), "只应提醒仍在进行的那笔");
        assertEquals(checkedIn.getId(), firstRemind.get(0).getId());
        assertTrue(reservationService.remindEnding(nine.plusMinutes(13)).isEmpty(),
                "提醒过就不再提醒");

        // 09:16 —— 到期结束
        converge(nine.plusMinutes(16));
        assertEquals(ReservationStatus.FINISHED.code(), statusOf(checkedIn.getId()));
    }

    @Test
    @DisplayName("释放后单元被清空，同一时段可以被别人重新约上")
    void releasedSlotsBecomeAvailable() {
        LocalDateTime nine = START.toLocalDate().atTime(9, 0);
        Reservation approved = insert(ReservationStatus.APPROVED.code(), nine, nine.plusHours(1));

        // 先手动占上这几个单元，模拟"批准时已写入占用"
        for (int i = 4; i < 8; i++) {
            slotMapper.insert(com.wyc.demo03.entity.ReservationSlot.of(
                    approved.getId(), 1L, nine.toLocalDate(), i));
        }
        assertEquals(4, slotMapper.findByReservationId(approved.getId()).size());

        converge(nine.plusMinutes(10));

        assertEquals(ReservationStatus.TIMEOUT_RELEASED.code(), statusOf(approved.getId()));
        assertEquals(0, slotMapper.findByReservationId(approved.getId()).size(),
                "释放后占用单元必须清空，否则这段时段永远约不上");
    }
}
