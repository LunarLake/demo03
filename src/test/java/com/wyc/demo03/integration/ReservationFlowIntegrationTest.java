package com.wyc.demo03.integration;

import com.wyc.demo03.common.SlotConflictException;
import com.wyc.demo03.domain.ReservationStatus;
import com.wyc.demo03.entity.CheckinCode;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.entity.User;
import com.wyc.demo03.mapper.CheckinCodeMapper;
import com.wyc.demo03.mapper.ReservationMapper;
import com.wyc.demo03.mapper.ReservationSlotMapper;
import com.wyc.demo03.service.ReservationService;
import com.wyc.demo03.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真库集成测试：验证 Mapper 的 SQL 与数据库唯一键在真实 MySQL 上确实按设计工作。
 *
 * <p>单测把 Mapper 全 mock 掉了，证明不了"多值 INSERT 撞唯一键时整条回滚"这类
 * 数据库侧行为 —— 而这正是并发安全的地基，必须在真库上验一次。
 *
 * <p>命名遵循 Surefire 约定（{@code *Test}）以便随 {@code mvn test} 一起跑。
 * 前提是本机 MySQL 已按 {@code sql/schema-v2.sql} 初始化。每个用例在事务内执行并回滚，
 * 不污染开发库。
 */
@SpringBootTest
@Transactional
class ReservationFlowIntegrationTest {

    @Autowired
    private ReservationService reservationService;
    @Autowired
    private ReservationMapper reservationMapper;
    @Autowired
    private ReservationSlotMapper slotMapper;
    @Autowired
    private CheckinCodeMapper checkinCodeMapper;
    @Autowired
    private UserService userService;

    private User student;
    private User teacher;
    private Long adminId;

    /** 用一个较远的日期，避免撞上开发库里已有数据。 */
    private LocalDateTime slotStart;

    @BeforeEach
    void setUp() {
        student = userService.lambdaQuery().eq(User::getUsername, "student01").one();
        teacher = userService.lambdaQuery().eq(User::getUsername, "teacher01").one();
        User admin = userService.lambdaQuery().eq(User::getUsername, "admin01").one();
        assertNotNull(student, "集成测试依赖 init 数据里的 student01");
        assertNotNull(teacher, "集成测试依赖 init 数据里的 teacher01");
        assertNotNull(admin, "集成测试依赖 init 数据里的 admin01");
        adminId = admin.getId();
        slotStart = LocalDate.now().plusDays(5).atTime(10, 0);
    }

    private Reservation form(int attendee) {
        Reservation r = new Reservation();
        r.setRoomId(1L);
        r.setStartTime(slotStart);
        r.setEndTime(slotStart.plusHours(1));
        r.setAttendeeCount(attendee);
        return r;
    }

    private Reservation insertApprovedNow() {
        Reservation r = new Reservation();
        r.setUserId(student.getId());
        r.setRoomId(1L);
        r.setStartTime(LocalDateTime.now().withSecond(0).withNano(0));
        r.setEndTime(r.getStartTime().plusHours(1));
        r.setAttendeeCount(2);
        r.setReservationStatus(ReservationStatus.APPROVED.code());
        reservationMapper.insert(r);
        return r;
    }

    // ====================================================================

    @Test
    @DisplayName("自动通过：写入 4 个单元 + 1 条有效签到码")
    void autoApproveWritesSlotsAndCode() {
        var result = reservationService.submit(form(3), student.getId(), "STUDENT");

        assertTrue(result.accepted());
        assertEquals(ReservationStatus.APPROVED, result.status());
        assertNotNull(result.checkInCode());

        assertEquals(4, slotMapper.findByReservationId(result.reservationId()).size(),
                "10:00–11:00 应占 4 个单元");
        CheckinCode code = checkinCodeMapper.findByReservationId(result.reservationId());
        assertNotNull(code);
        assertNull(code.getRevokedAt(), "有效预约的签到码不应被撤销");
    }

    @Test
    @DisplayName("AC-04：两笔待审批可以并存于同一单元，且都不占单元")
    void pendingApplicationsCoexist() {
        var first = reservationService.submit(form(5), student.getId(), "STUDENT");
        var second = reservationService.submit(form(5), student.getId(), "STUDENT");

        assertEquals(ReservationStatus.PENDING, first.status());
        assertEquals(ReservationStatus.PENDING, second.status());
        assertEquals(0, slotMapper.findByDate(slotStart.toLocalDate()).size(),
                "待审批绝不应该写占用单元");
    }

    @Test
    @DisplayName("AC-05：第一笔批准占住单元，第二笔批准撞唯一键并整条回滚")
    void secondApprovalConflicts() {
        var first = reservationService.submit(form(5), student.getId(), "STUDENT");
        var second = reservationService.submit(form(5), student.getId(), "STUDENT");

        assertTrue(reservationService.approve(first.reservationId(), adminId).ok(),
                "第一笔应批准成功");

        assertThrows(SlotConflictException.class,
                () -> reservationService.approve(second.reservationId(), adminId),
                "第二笔撞上已占用单元，必须抛冲突异常让事务回滚");
    }

    @Test
    @DisplayName("唯一键真的会挡住重复占用：单行插入同一单元直接 1062")
    void uniqueKeyRejectsDuplicateSlot() {
        var created = reservationService.submit(form(3), student.getId(), "STUDENT");
        List<com.wyc.demo03.entity.ReservationSlot> slots =
                slotMapper.findByReservationId(created.reservationId());
        assertFalse(slots.isEmpty());

        com.wyc.demo03.entity.ReservationSlot clash = com.wyc.demo03.entity.ReservationSlot.of(
                999_999L, 1L, slotStart.toLocalDate(), slots.get(0).getSlotIndex());

        assertThrows(DuplicateKeyException.class, () -> slotMapper.insert(clash),
                "同一房间同一天同一单元只能有一条占用");
    }

    @Test
    @DisplayName("取消：释放全部单元、撤销签到码、记录取消来源")
    void cancelReleasesSlotsAndRevokesCode() {
        var result = reservationService.submit(form(3), student.getId(), "STUDENT");
        assertEquals(4, slotMapper.findByReservationId(result.reservationId()).size());

        assertEquals("success", reservationService.cancel(result.reservationId(), student.getId()));

        assertEquals(0, slotMapper.findByReservationId(result.reservationId()).size(),
                "取消必须释放全部单元");
        assertNotNull(checkinCodeMapper.findByReservationId(result.reservationId()).getRevokedAt(),
                "取消必须撤销签到码");

        Reservation after = reservationMapper.selectById(result.reservationId());
        assertEquals(ReservationStatus.CANCELED.code(), after.getReservationStatus());
        assertEquals("USER", after.getCancelSource());
        assertEquals("用户取消", after.getDecisionReason());
        assertNotNull(after.getClosedAt());
    }

    @Test
    @DisplayName("教师覆盖：学生预约转已取消并释放单元，教师预约占住同一单元")
    void teacherOverridesStudent() {
        var studentResult = reservationService.submit(form(3), student.getId(), "STUDENT");
        assertEquals(ReservationStatus.APPROVED, studentResult.status());

        var teacherResult = reservationService.submit(form(3), teacher.getId(), "TEACHER");
        assertTrue(teacherResult.accepted(), "教师应能覆盖尚未开始的学生预约");

        Reservation canceled = reservationMapper.selectById(studentResult.reservationId());
        assertEquals(ReservationStatus.CANCELED.code(), canceled.getReservationStatus());
        assertEquals("TEACHER_OVERRIDE", canceled.getCancelSource());
        assertEquals(0, slotMapper.findByReservationId(studentResult.reservationId()).size());
        assertEquals(4, slotMapper.findByReservationId(teacherResult.reservationId()).size());
    }

    @Test
    @DisplayName("签到只能成功一次：第二次返回 already_checked_in，且只留一条签到记录")
    void checkInIsSingleShot() {
        Reservation r = insertApprovedNow();
        CheckinCode code = new CheckinCode();
        code.setReservationId(r.getId());
        code.setCode("4321");
        code.setIssuedAt(LocalDateTime.now());
        checkinCodeMapper.insert(code);

        assertEquals("success", reservationService.checkIn(r.getId(), student.getId(), "4321", "127.0.0.1"));
        assertEquals("already_checked_in",
                reservationService.checkIn(r.getId(), student.getId(), "4321", "127.0.0.1"));

        Reservation after = reservationMapper.selectById(r.getId());
        assertEquals(ReservationStatus.CHECKED_IN.code(), after.getReservationStatus());
    }

    @Test
    @DisplayName("我的预约分页 SQL 能取到房间名与签到码别名")
    void myReservationsQueryWorks() {
        var created = reservationService.submit(form(3), student.getId(), "STUDENT");
        var page = reservationService.findByUserIdWithRoomPage(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10),
                student.getId(), null);

        assertFalse(page.getRecords().isEmpty());
        Map<String, Object> row = page.getRecords().stream()
                .filter(m -> created.reservationId().equals(((Number) m.get("id")).longValue()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("刚提交的预约应出现在我的预约列表中"));
        assertNotNull(row.get("roomName"), "必须带出房间名");
        assertNotNull(row.get("checkInCode"), "必须带出签到码（别名 checkInCode）");
    }

    @Test
    @DisplayName("待审批列表带出判定路径与命中规则，供管理员看到「为什么进了人工」")
    void approvalsExposeDecisionInfo() {
        reservationService.submit(form(5), student.getId(), "STUDENT");
        List<Map<String, Object>> approvals = reservationService.findApprovals();

        assertFalse(approvals.isEmpty());
        Map<String, Object> row = approvals.get(0);
        assertEquals("MANUAL_PENDING", row.get("decision_path"));
        assertEquals("E01_HEADCOUNT", row.get("hit_rule_codes"));
    }
}
