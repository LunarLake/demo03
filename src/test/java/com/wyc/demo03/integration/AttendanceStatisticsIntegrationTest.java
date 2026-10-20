package com.wyc.demo03.integration;

import com.wyc.demo03.domain.ReservationStatus;
import com.wyc.demo03.entity.AttendanceRecord;
import com.wyc.demo03.entity.CheckinCode;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.entity.User;
import com.wyc.demo03.mapper.AttendanceRecordMapper;
import com.wyc.demo03.mapper.CheckinCodeMapper;
import com.wyc.demo03.mapper.ReservationMapper;
import com.wyc.demo03.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 缺席统计口径的真库测试（BUG-007）。
 *
 * <p>这条统计的坑在于：v2 把「未签到」从 {@code attend_status = 0 的行}
 * 改成了「没有行」。写个单测 mock 掉 Mapper 什么都证明不了 —— 必须让 SQL
 * 真正跑一遍，才能证明「爽约」被算进缺席、而「主动取消 / 待审超时 / 未来预约」
 * 没有被算进去。
 *
 * <p>统计是全表聚合，库里已经有演示数据。因此断言的不是绝对值，而是
 * <b>插入固定夹具前后的增量</b>，这样测试之间、与演示数据之间都互不影响。
 */
@SpringBootTest
@Transactional
class AttendanceStatisticsIntegrationTest {

    @Autowired private ReservationMapper reservationMapper;
    @Autowired private AttendanceRecordMapper attendanceRecordMapper;
    @Autowired private CheckinCodeMapper checkinCodeMapper;
    @Autowired private UserService userService;

    private Long studentId;

    @BeforeEach
    void setUp() {
        User student = userService.lambdaQuery().eq(User::getUsername, "student01").one();
        assertNotNull(student);
        studentId = student.getId();
    }

    // ====================================================================
    // 夹具
    // ====================================================================

    /** 插一笔预约；{@code startOffset} 为相对当前时刻的分钟数（负数表示过去）。 */
    private Reservation insertReservation(int status, int startOffset, int durationMinutes) {
        Reservation r = new Reservation();
        r.setUserId(studentId);
        r.setRoomId(1L);
        LocalDateTime start = LocalDateTime.now().plusMinutes(startOffset).withSecond(0).withNano(0);
        r.setStartTime(start);
        r.setEndTime(start.plusMinutes(durationMinutes));
        r.setAttendeeCount(2);
        r.setReservationStatus(status);
        reservationMapper.insert(r);
        return r;
    }

    /** 写一条首次签到记录（v2 中"有这一行"就等于"已签到"）。 */
    private void insertAttendance(Reservation r) {
        AttendanceRecord ar = new AttendanceRecord();
        ar.setReservationId(r.getId());
        ar.setUserId(studentId);
        ar.setCheckInTime(r.getStartTime().plusMinutes(1));
        ar.setAttendStatus(1);
        ar.setMethod("SCREEN_CODE");
        ar.setIp("127.0.0.1");
        attendanceRecordMapper.insert(ar);
    }

    /**
     * 签发签到码 —— 统计里用它表达"这笔预约曾经通过审批、被期待出席"。
     * 撤销只置 revoked_at，行仍在，因此仍是"曾要求出席"的证据。
     */
    private void insertCode(Reservation r, boolean revoked) {
        CheckinCode cc = new CheckinCode();
        cc.setReservationId(r.getId());
        cc.setCode("1234");
        cc.setIssuedAt(r.getStartTime().minusMinutes(10));
        cc.setRevokedAt(revoked ? r.getStartTime() : null);
        checkinCodeMapper.insert(cc);
    }

    // ====================================================================
    // 断言辅助：全量统计 → 看增量
    // ====================================================================

    private Map<String, Long> counts() {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Map<String, Object> row : attendanceRecordMapper.countAttendanceRate()) {
            m.put(String.valueOf(row.get("label")), ((Number) row.get("count")).longValue());
        }
        return m;
    }

    private long delta(Map<String, Long> after, Map<String, Long> before, String label) {
        return after.getOrDefault(label, 0L) - before.getOrDefault(label, 0L);
    }

    // ====================================================================

    @Test
    @DisplayName("已签到 / 爽约 / 未来预约 / 主动取消 / 待审超时 各一笔：只有爽约计入缺席")
    void onlyNoShowCountsAsAbsent() {
        Map<String, Long> before = counts();

        // 1) 已签到：有签到记录，且已结束
        Reservation attended = insertReservation(ReservationStatus.FINISHED.code(), -60, 45);
        insertCode(attended, true);
        insertAttendance(attended);

        // 2) 爽约释放：曾发码、无签到记录、状态 5
        Reservation noShow = insertReservation(ReservationStatus.TIMEOUT_RELEASED.code(), -30, 45);
        insertCode(noShow, true);

        // 3) 未来预约：已通过但还没到开始时刻，尚未判定
        Reservation future = insertReservation(ReservationStatus.APPROVED.code(), 120, 45);
        insertCode(future, false);

        // 4) 主动取消
        insertReservation(ReservationStatus.CANCELED.code(), -60, 45);

        // 5) 待审超时：状态同为 5，但从未发过码 —— 关键区分点
        insertReservation(ReservationStatus.TIMEOUT_RELEASED.code(), -30, 45);

        Map<String, Long> after = counts();

        assertEquals(1, delta(after, before, "已签到"), "有签到记录的预约计入已签到");
        assertEquals(1, delta(after, before, "缺席（爽约）"), "只有真正爽约的那笔计入缺席");
        assertEquals(1, delta(after, before, "主动取消"), "主动取消要单独归类，不得算缺席");
        assertEquals(1, delta(after, before, "待审超时"), "待审超时与爽约终态同为 5，必须靠签到码区分");
        assertEquals(1, delta(after, before, "待定（未到期）"), "还没开始的预约不能提前判缺席");
        assertEquals(0, delta(after, before, "已拒绝"));
    }

    @Test
    @DisplayName("已结束但全程未签到：事实爽约，计入缺席")
    void finishedWithoutCheckInCountsAsAbsent() {
        Map<String, Long> before = counts();

        // 签到截止扫描漏掉、直接走到"到期结束"的极端情况：整场没来人
        Reservation r = insertReservation(ReservationStatus.FINISHED.code(), -60, 45);
        insertCode(r, true);

        Map<String, Long> after = counts();

        assertEquals(1, delta(after, before, "缺席（爽约）"));
        assertEquals(0, delta(after, before, "已签到"));
    }

    @Test
    @DisplayName("待审批（未处理）不计入任何缺席口径")
    void pendingNeverCountsAsAbsent() {
        Map<String, Long> before = counts();

        insertReservation(ReservationStatus.PENDING.code(), 120, 45);
        insertReservation(ReservationStatus.REJECTED.code(), -60, 45);

        Map<String, Long> after = counts();

        assertEquals(1, delta(after, before, "待定（未到期）"), "待审批还没到判定时点");
        assertEquals(1, delta(after, before, "已拒绝"));
        assertEquals(0, delta(after, before, "缺席（爽约）"));
        assertEquals(0, delta(after, before, "待审超时"));
    }
}
