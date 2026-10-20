package com.wyc.demo03.service.impl;

import com.wyc.demo03.common.SlotConflictException;
import com.wyc.demo03.config.SlotProperties;
import com.wyc.demo03.domain.DecisionPath;
import com.wyc.demo03.domain.ReservationStatus;
import com.wyc.demo03.domain.RuleParams;
import com.wyc.demo03.domain.SlotSchema;
import com.wyc.demo03.domain.SlotUnit;
import com.wyc.demo03.entity.MeetingRoom;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.entity.ReservationSlot;
import com.wyc.demo03.entity.User;
import com.wyc.demo03.mapper.AuditLogMapper;
import com.wyc.demo03.mapper.ReservationMapper;
import com.wyc.demo03.rule.RuleEngine;
import com.wyc.demo03.rule.RuleParamsProvider;
import com.wyc.demo03.rule.basic.AccountActiveRule;
import com.wyc.demo03.rule.basic.CapacityRule;
import com.wyc.demo03.rule.basic.CreditFloorRule;
import com.wyc.demo03.rule.basic.NoActiveViolationRule;
import com.wyc.demo03.rule.basic.SlotAlignmentRule;
import com.wyc.demo03.rule.basic.SlotOccupiedRule;
import com.wyc.demo03.service.AccessService;
import com.wyc.demo03.service.AttendanceRecordService;
import com.wyc.demo03.service.CheckinCodeService;
import com.wyc.demo03.service.CreditService;
import com.wyc.demo03.service.MeetingRoomService;
import com.wyc.demo03.service.SlotService;
import com.wyc.demo03.service.UserService;
import com.wyc.demo03.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预约业务 v2 单测。
 *
 * <p>用**真实的** {@link RuleEngine} 与 6 条基础规则，只 mock 掉数据访问层。
 * 这样"提交申请 → 三层判定 → 状态落库"这条主链是被真正测到的，
 * 而不是把 RuleEngine 也 mock 掉、最后只验证了一个 switch。
 *
 * <p>时间用 {@link MutableClock} 控制，签到截止、到期结束这类边界可以精确到分钟。
 */
@ExtendWith(MockitoExtension.class)
class ReservationServiceImplTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 20, 9, 0);

    @Mock private ReservationMapper reservationMapper;
    @Mock private AttendanceRecordService attendanceRecordService;
    @Mock private MeetingRoomService meetingRoomService;
    @Mock private UserService userService;
    @Mock private SlotService slotService;
    @Mock private CheckinCodeService checkinCodeService;
    @Mock private CreditService creditService;
    @Mock private AccessService accessService;
    @Mock private AuditLogMapper auditLogMapper;

    private final MutableClock clock = MutableClock.at(T0);
    private final SlotProperties properties = new SlotProperties();
    private SlotSchema schema;
    private ReservationServiceImpl service;
    private MeetingRoom room;

    private final AtomicLong idSeq = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        schema = properties.toSchema();
        RuleParamsProvider params = RuleParamsProvider.of(RuleParams.defaults());
        RuleEngine engine = new RuleEngine(List.of(
                new AccountActiveRule(),
                new SlotAlignmentRule(),
                new CapacityRule(),
                new SlotOccupiedRule(),
                new CreditFloorRule(params),
                new NoActiveViolationRule()), params, schema);

        service = new ReservationServiceImpl(attendanceRecordService, meetingRoomService, userService,
                slotService, checkinCodeService, creditService, engine, schema, properties,
                auditLogMapper, clock, accessService);
        ReflectionTestUtils.setField(service, "baseMapper", reservationMapper);

        room = new MeetingRoom();
        room.setId(1L);
        room.setRoomName("研讨室A");
        room.setCapacity(6);
        room.setRoomStatus(0);

        // 公共 stub
        lenient().when(reservationMapper.insert(any(Reservation.class))).thenAnswer(inv -> {
            Reservation r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(idSeq.getAndIncrement());
            }
            return 1;
        });
        lenient().when(slotService.unitsOf(any(), any())).thenAnswer(inv ->
                schema.split(inv.getArgument(0), inv.getArgument(1)));
        lenient().when(slotService.findOccupied(anyLong(), anyList())).thenReturn(List.of());
        lenient().when(creditService.scoreOf(anyLong())).thenReturn(100);
        lenient().when(checkinCodeService.issue(anyLong())).thenReturn("1234");
        // 默认门禁正常回执；个别用例再覆盖成失败
        lenient().when(accessService.openAfterCheckIn(any(), anyLong(), any()))
                .thenReturn(AccessService.UnlockResult.granted("msg-1", "ACKED", "门禁已开锁"));
        lenient().when(meetingRoomService.getById(1L)).thenReturn(room);
    }

    // ==================== 辅助 ====================

    private Reservation form(LocalDateTime start, LocalDateTime end) {
        Reservation r = new Reservation();
        r.setRoomId(1L);
        r.setStartTime(start);
        r.setEndTime(end);
        r.setAttendeeCount(3);
        return r;
    }

    /** 明天 10:00–11:00，稳定落在可预约窗口内。 */
    private Reservation tomorrow() {
        LocalDateTime day = T0.toLocalDate().plusDays(1).atTime(10, 0);
        return form(day, day.plusHours(1));
    }

    private void stubExisting(Reservation r) {
        when(reservationMapper.selectById(r.getId())).thenReturn(r);
    }

    private static Reservation reservationWithStatus(long id, int status, LocalDateTime start) {
        Reservation r = new Reservation();
        r.setId(id);
        r.setUserId(7L);
        r.setRoomId(1L);
        r.setStartTime(start);
        r.setEndTime(start.plusHours(1));
        r.setReservationStatus(status);
        return r;
    }

    // ====================================================================
    // 提交申请：校验与三层判定
    // ====================================================================
    @Nested
    @DisplayName("提交申请")
    class Submit {

        @Test
        void roomNotFound() {
            when(meetingRoomService.getById(1L)).thenReturn(null);
            var result = service.submit(tomorrow(), 7L, "STUDENT");
            assertFalse(result.accepted());
            assertEquals("room_not_found", result.errorCode());
            verify(reservationMapper, never()).insert(any(Reservation.class));
        }

        @Test
        void roomUnderMaintenance() {
            room.setRoomStatus(1);
            var result = service.submit(tomorrow(), 7L, "STUDENT");
            assertEquals("room_maintenance", result.errorCode());
            verify(reservationMapper, never()).insert(any(Reservation.class));
        }

        @Test
        @DisplayName("AC-01 未对齐 15 分钟整点被拒绝")
        void notAligned() {
            LocalDateTime day = T0.toLocalDate().plusDays(1).atTime(10, 7);
            var result = service.submit(form(day, day.plusHours(1)), 7L, "STUDENT");
            assertFalse(result.accepted());
            assertEquals("not_aligned", result.errorCode());
        }

        @Test
        @DisplayName("AC-02 超过 16 个单元被拒绝")
        void tooLong() {
            LocalDateTime day = T0.toLocalDate().plusDays(1).atTime(8, 15);
            var result = service.submit(form(day, day.plusMinutes(15 * 17)), 7L, "STUDENT");
            assertEquals("duration_too_long", result.errorCode());
        }

        @Test
        @DisplayName("AC-03 超过 7 天被拒绝")
        void tooFarAhead() {
            LocalDateTime day = T0.toLocalDate().plusDays(8).atTime(10, 0);
            var result = service.submit(form(day, day.plusHours(1)), 7L, "STUDENT");
            assertEquals("too_far_ahead", result.errorCode());
        }

        @Test
        void overCapacity() {
            Reservation f = tomorrow();
            f.setAttendeeCount(7);
            var result = service.submit(f, 7L, "STUDENT");
            assertFalse(result.accepted());
            assertEquals("B03_CAPACITY", result.errorCode());
        }

        @Test
        @DisplayName("信用 100、人数 3 → 自动通过并占用单元、签发签到码")
        void autoApproved() {
            var result = service.submit(tomorrow(), 7L, "STUDENT");

            assertTrue(result.accepted());
            assertEquals(ReservationStatus.APPROVED, result.status());
            assertEquals("1234", result.checkInCode());

            ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
            verify(reservationMapper).insert(captor.capture());
            assertEquals(ReservationStatus.APPROVED.code(), captor.getValue().getReservationStatus());
            assertEquals(DecisionPath.AUTO_PASS.name(), captor.getValue().getDecisionPath());
            verify(slotService).occupy(eq(captor.getValue().getId()), eq(1L), anyList());
        }

        @Test
        @DisplayName("人数达到 5（容量 6）→ 转人工，且不占单元")
        void headcountForcesManual() {
            Reservation f = tomorrow();
            f.setAttendeeCount(5);
            var result = service.submit(f, 7L, "STUDENT");

            assertTrue(result.accepted());
            assertEquals(ReservationStatus.PENDING, result.status());
            verify(slotService, never()).occupy(anyLong(), anyLong(), anyList());
            verify(checkinCodeService, never()).issue(anyLong());
        }

        @Test
        @DisplayName("当日自动通过已达 3 笔 → 转人工")
        void dailyLimitForcesManual() {
            when(reservationMapper.countAutoPassOnDate(7L, T0.toLocalDate().plusDays(1))).thenReturn(3);
            var result = service.submit(tomorrow(), 7L, "STUDENT");
            assertEquals(ReservationStatus.PENDING, result.status());
            assertTrue(result.message().contains("自动通过笔数"));
        }

        @Test
        @DisplayName("信用 75 且时长 3 小时 → 转人工")
        void midCreditLongDurationForcesManual() {
            when(creditService.scoreOf(7L)).thenReturn(75);
            LocalDateTime day = T0.toLocalDate().plusDays(1).atTime(10, 0);
            var result = service.submit(form(day, day.plusHours(3)), 7L, "STUDENT");
            assertEquals(ReservationStatus.PENDING, result.status());
        }

        @Test
        @DisplayName("信用 75 且时长 1 小时 → 自动通过")
        void midCreditShortDurationAutoApproved() {
            when(creditService.scoreOf(7L)).thenReturn(75);
            var result = service.submit(tomorrow(), 7L, "STUDENT");
            assertEquals(ReservationStatus.APPROVED, result.status());
        }

        @Test
        @DisplayName("信用 55 → 基础规则直接拒绝（Q3 取从严）")
        void lowCreditRejected() {
            when(creditService.scoreOf(7L)).thenReturn(55);
            var result = service.submit(tomorrow(), 7L, "STUDENT");
            assertFalse(result.accepted());
            assertEquals("B05_CREDIT", result.errorCode());
        }

        @Test
        @DisplayName("信用 65 且未填用途 → 拒绝并要求补用途")
        void lowCreditWithoutPurposeRejected() {
            when(creditService.scoreOf(7L)).thenReturn(65);
            var result = service.submit(tomorrow(), 7L, "STUDENT");
            assertEquals("E06_PURPOSE_REQUIRED", result.errorCode());
        }

        @Test
        @DisplayName("信用 65 且填了用途 → 转人工")
        void lowCreditWithPurposeGoesManual() {
            when(creditService.scoreOf(7L)).thenReturn(65);
            Reservation f = tomorrow();
            f.setPurpose("课程小组讨论");
            var result = service.submit(f, 7L, "STUDENT");
            assertEquals(ReservationStatus.PENDING, result.status());
        }

        @Test
        @DisplayName("单元已被占用 → 基础规则拒绝")
        void slotOccupiedRejected() {
            when(slotService.findOccupied(anyLong(), anyList()))
                    .thenReturn(List.of(ReservationSlot.of(99L, 1L, LocalDate.of(2026, 10, 21), 8)));
            var result = service.submit(tomorrow(), 7L, "STUDENT");
            assertEquals("B04_OCCUPIED", result.errorCode());
        }

        @Test
        @DisplayName("存在未撤销的违规 → 基础规则拒绝")
        void activeViolationRejected() {
            when(reservationMapper.countActiveViolation(7L)).thenReturn(1);
            var result = service.submit(tomorrow(), 7L, "STUDENT");
            assertEquals("B06_VIOLATION", result.errorCode());
        }

        @Test
        @DisplayName("被拒绝的申请也落库，便于申诉追溯")
        void rejectedIsPersisted() {
            when(creditService.scoreOf(7L)).thenReturn(55);
            service.submit(tomorrow(), 7L, "STUDENT");

            ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
            verify(reservationMapper).insert(captor.capture());
            assertEquals(ReservationStatus.REJECTED.code(), captor.getValue().getReservationStatus());
            assertEquals(DecisionPath.REJECTED_BASIC.name(), captor.getValue().getDecisionPath());
            assertNotNull(captor.getValue().getDecisionReason());
            assertNotNull(captor.getValue().getClosedAt());
        }
    }

    // ====================================================================
    // 教师路径
    // ====================================================================
    @Nested
    @DisplayName("教师预约与覆盖")
    class Teacher {

        @Test
        @DisplayName("与其他教师冲突 → 拒绝")
        void teacherConflict() {
            Reservation other = reservationWithStatus(50L, 1, T0.plusDays(1).withHour(10));
            other.setUserId(8L);
            when(slotService.findOccupied(anyLong(), anyList()))
                    .thenReturn(List.of(ReservationSlot.of(50L, 1L, LocalDate.of(2026, 10, 21), 8)));
            when(reservationMapper.findByIds(anyList())).thenReturn(List.of(other));
            User teacher = new User();
            teacher.setId(8L);
            teacher.setRole("TEACHER");
            when(userService.listByIds(anyList())).thenReturn(List.of(teacher));

            var result = service.submit(tomorrow(), 9L, "TEACHER");
            assertFalse(result.accepted());
            assertEquals("teacher_conflict", result.errorCode());
            verify(slotService, never()).occupy(anyLong(), anyLong(), anyList());
        }

        @Test
        @DisplayName("学生预约已开始 → 不可覆盖")
        void cannotOverrideStarted() {
            // 已经开始：开始时刻早于当前时间
            Reservation other = reservationWithStatus(50L, 1, T0.minusMinutes(30));
            when(slotService.findOccupied(anyLong(), anyList()))
                    .thenReturn(List.of(ReservationSlot.of(50L, 1L, T0.toLocalDate(), 2)));
            when(reservationMapper.findByIds(anyList())).thenReturn(List.of(other));
            User student = new User();
            student.setId(8L);
            student.setRole("STUDENT");
            when(userService.listByIds(anyList())).thenReturn(List.of(student));

            Reservation f = form(T0.plusMinutes(15), T0.plusMinutes(75));
            var result = service.submit(f, 9L, "TEACHER");
            assertFalse(result.accepted());
            assertEquals("override_started", result.errorCode());
        }

        @Test
        @DisplayName("覆盖未开始的学生预约：学生转已取消、释放单元、撤销码，教师预约生效")
        void overridesFutureStudentReservation() {
            Reservation other = reservationWithStatus(50L, 1, T0.plusDays(1).withHour(10));
            other.setUserId(8L);
            when(slotService.findOccupied(anyLong(), anyList()))
                    .thenReturn(List.of(ReservationSlot.of(50L, 1L, LocalDate.of(2026, 10, 21), 8)));
            when(reservationMapper.findByIds(anyList())).thenReturn(List.of(other));
            when(reservationMapper.transit(eq(50L), anyInt(), anyInt(), any(), anyString(), any()))
                    .thenReturn(1);
            User student = new User();
            student.setId(8L);
            student.setRole("STUDENT");
            when(userService.listByIds(anyList())).thenReturn(List.of(student));

            var result = service.submit(tomorrow(), 9L, "TEACHER");

            assertTrue(result.accepted());
            assertEquals(ReservationStatus.APPROVED, result.status());
            verify(reservationMapper).setCancelSource(50L, "TEACHER_OVERRIDE");
            verify(slotService).release(50L);
            verify(checkinCodeService).revoke(50L);
            verify(slotService).occupy(anyLong(), eq(1L), anyList());
        }

        @Test
        @DisplayName("覆盖时目标状态已变 → 抛异常回滚，不能出现「取消了却没释放单元」")
        void overrideFailsWhenStatusChanged() {
            Reservation other = reservationWithStatus(50L, 1, T0.plusDays(1).withHour(10));
            other.setUserId(8L);
            when(slotService.findOccupied(anyLong(), anyList()))
                    .thenReturn(List.of(ReservationSlot.of(50L, 1L, LocalDate.of(2026, 10, 21), 8)));
            when(reservationMapper.findByIds(anyList())).thenReturn(List.of(other));
            when(reservationMapper.transit(anyLong(), anyInt(), anyInt(), any(), anyString(), any()))
                    .thenReturn(0);
            User student = new User();
            student.setId(8L);
            student.setRole("STUDENT");
            when(userService.listByIds(anyList())).thenReturn(List.of(student));

            assertThrows(IllegalStateException.class, () -> service.submit(tomorrow(), 9L, "TEACHER"));
            verify(slotService, never()).release(50L);
        }
    }

    // ====================================================================
    // 人工批准 / 拒绝
    // ====================================================================
    @Nested
    @DisplayName("人工审批")
    class Approve {

        @Test
        void notFound() {
            when(reservationMapper.selectById(1L)).thenReturn(null);
            assertFalse(service.approve(1L, 9L).ok());
        }

        @Test
        @DisplayName("已处理过的申请不能再批")
        void stateChangedPrecheck() {
            stubExisting(reservationWithStatus(1L, ReservationStatus.APPROVED.code(), T0.plusHours(2)));
            var result = service.approve(1L, 9L);
            assertFalse(result.ok());
            assertEquals("state_changed", result.errorCode());
        }

        @Test
        @DisplayName("开始时刻已过 → 不能批准")
        void expired() {
            stubExisting(reservationWithStatus(1L, ReservationStatus.PENDING.code(), T0.minusMinutes(15)));
            var result = service.approve(1L, 9L);
            assertEquals("expired", result.errorCode());
        }

        @Test
        @DisplayName("批准成功：迁移状态、记录审核人、占用单元、签发码")
        void approvesAndOccupies() {
            Reservation pending = reservationWithStatus(1L, ReservationStatus.PENDING.code(), T0.plusHours(2));
            stubExisting(pending);
            when(reservationMapper.transit(anyLong(), anyInt(), anyInt(), any(), any(), any())).thenReturn(1);

            var result = service.approve(1L, 9L);

            assertTrue(result.ok());
            assertEquals("1234", result.checkInCode());
            verify(reservationMapper).transit(1L, ReservationStatus.PENDING.code(),
                    ReservationStatus.APPROVED.code(), DecisionPath.MANUAL_APPROVED.name(), null, null);
            verify(reservationMapper).markApprover(eq(1L), eq(9L), any());
            verify(slotService).occupy(eq(1L), eq(1L), anyList());
        }

        @Test
        @DisplayName("并发下抢占失败 → 返回状态变化，不占用单元")
        void losesRace() {
            stubExisting(reservationWithStatus(1L, ReservationStatus.PENDING.code(), T0.plusHours(2)));
            when(reservationMapper.transit(anyLong(), anyInt(), anyInt(), any(), any(), any())).thenReturn(0);

            var result = service.approve(1L, 9L);

            assertFalse(result.ok());
            assertEquals("state_changed", result.errorCode());
            verify(slotService, never()).occupy(anyLong(), anyLong(), anyList());
        }

        @Test
        @DisplayName("单元被抢占 → 冲突异常向上抛，由事务回滚")
        void propagatesSlotConflict() {
            stubExisting(reservationWithStatus(1L, ReservationStatus.PENDING.code(), T0.plusHours(2)));
            when(reservationMapper.transit(anyLong(), anyInt(), anyInt(), any(), any(), any())).thenReturn(1);
            org.mockito.Mockito.doThrow(new SlotConflictException(List.of()))
                    .when(slotService).occupy(anyLong(), anyLong(), anyList());

            assertThrows(SlotConflictException.class, () -> service.approve(1L, 9L));
        }

        @Test
        @DisplayName("拒绝：写入原因、记录审核人、清理占用与码")
        void rejects() {
            stubExisting(reservationWithStatus(1L, ReservationStatus.PENDING.code(), T0.plusHours(2)));
            when(reservationMapper.transit(anyLong(), anyInt(), anyInt(), any(), any(), any())).thenReturn(1);

            service.reject(1L, "该时段有教学安排", 9L);

            verify(reservationMapper).transit(1L, ReservationStatus.PENDING.code(),
                    ReservationStatus.REJECTED.code(), DecisionPath.MANUAL_REJECTED.name(),
                    "该时段有教学安排", T0);
            verify(slotService).release(1L);
            verify(checkinCodeService).revoke(1L);
        }

        @Test
        void rejectsBlankReasonWithDefault() {
            stubExisting(reservationWithStatus(1L, ReservationStatus.PENDING.code(), T0.plusHours(2)));
            when(reservationMapper.transit(anyLong(), anyInt(), anyInt(), any(), any(), any())).thenReturn(1);

            service.reject(1L, "  ", 9L);

            verify(reservationMapper).transit(anyLong(), anyInt(), anyInt(), any(),
                    org.mockito.ArgumentMatchers.contains("无法安排"), any());
        }

        @Test
        @DisplayName("拒绝非待审批的申请 → 抛异常")
        void cannotRejectNonPending() {
            stubExisting(reservationWithStatus(1L, ReservationStatus.REJECTED.code(), T0.plusHours(2)));
            assertThrows(IllegalStateException.class, () -> service.reject(1L, "x", 9L));
        }
    }

    // ====================================================================
    // 取消
    // ====================================================================
    @Nested
    @DisplayName("取消")
    class Cancel {

        @Test
        void notOwner() {
            Reservation r = reservationWithStatus(1L, ReservationStatus.PENDING.code(), T0.plusHours(2));
            r.setUserId(7L);
            stubExisting(r);
            assertEquals("not_owner", service.cancel(1L, 99L));
        }

        @Test
        @DisplayName("★ 已开始的预约不能取消（否则可用取消规避爽约扣分）")
        void cannotCancelAfterStart() {
            Reservation r = reservationWithStatus(1L, ReservationStatus.APPROVED.code(), T0.minusMinutes(1));
            stubExisting(r);
            assertEquals("already_started", service.cancel(1L, 7L));
            verify(slotService, never()).release(anyLong());
        }

        @Test
        void cancelsAndReleases() {
            stubExisting(reservationWithStatus(1L, ReservationStatus.APPROVED.code(), T0.plusHours(2)));
            when(reservationMapper.transit(anyLong(), anyInt(), anyInt(), any(), any(), any())).thenReturn(1);

            assertEquals("success", service.cancel(1L, 7L));

            verify(reservationMapper).setCancelSource(1L, "USER");
            verify(slotService).release(1L);
            verify(checkinCodeService).revoke(1L);
        }

        @Test
        void terminalStatusCannotCancel() {
            stubExisting(reservationWithStatus(1L, ReservationStatus.TIMEOUT_RELEASED.code(), T0.plusHours(2)));
            assertEquals("invalid_status", service.cancel(1L, 7L));
        }
    }

    // ====================================================================
    // 签到
    // ====================================================================
    @Nested
    @DisplayName("签到")
    class CheckIn {

        private Reservation approvedAt(LocalDateTime start) {
            return reservationWithStatus(1L, ReservationStatus.APPROVED.code(), start);
        }

        @Test
        void tooEarly() {
            stubExisting(approvedAt(T0.plusMinutes(10)));
            assertEquals("too_early", service.checkIn(1L, 7L, "1234", "127.0.0.1"));
        }

        @Test
        @DisplayName("AC-13 开始后 4:59 可以签到")
        void successJustBeforeDeadline() {
            clock.set(T0);
            stubExisting(approvedAt(T0.minusMinutes(5).plusSeconds(1)));
            when(checkinCodeService.verify(1L, "1234")).thenReturn(true);
            when(reservationMapper.transit(anyLong(), anyInt(), anyInt(), any(), any(), any())).thenReturn(1);

            assertEquals("success", service.checkIn(1L, 7L, "1234", "127.0.0.1"));
            verify(attendanceRecordService).save(any());
        }

        @Test
        @DisplayName("AC-14 开始后 5:00 已截止")
        void expiredExactlyAtDeadline() {
            clock.set(T0);
            stubExisting(approvedAt(T0.minusMinutes(5)));
            assertEquals("expired", service.checkIn(1L, 7L, "1234", "127.0.0.1"));
        }

        @Test
        @DisplayName("短预约的窗口被结束时刻截断：15 分钟预约窗口仍是 [开始, 开始+5)")
        void windowClampedByEnd() {
            clock.set(T0);
            Reservation r = approvedAt(T0.minusMinutes(5));
            r.setEndTime(T0.minusMinutes(2)); // 已经结束
            stubExisting(r);
            assertEquals("expired", service.checkIn(1L, 7L, "1234", "127.0.0.1"));
        }

        @Test
        void wrongCode() {
            clock.set(T0);
            stubExisting(approvedAt(T0.minusMinutes(1)));
            when(checkinCodeService.verify(anyLong(), anyString())).thenReturn(false);
            assertEquals("wrong_code", service.checkIn(1L, 7L, "0000", "127.0.0.1"));
            verify(reservationMapper, never()).transit(anyLong(), anyInt(), anyInt(), any(), any(), any());
        }

        @Test
        @DisplayName("签到码连错 5 次后锁定")
        void locksAfterFiveFailures() {
            clock.set(T0);
            stubExisting(approvedAt(T0.minusMinutes(1)));
            when(checkinCodeService.verify(anyLong(), anyString())).thenReturn(false);
            for (int i = 0; i < 5; i++) {
                assertEquals("wrong_code", service.checkIn(1L, 7L, "0000", "127.0.0.1"));
            }
            assertEquals("too_many_attempts", service.checkIn(1L, 7L, "0000", "127.0.0.1"));
        }

        @Test
        @DisplayName("已签到状态再次签到 → already_checked_in")
        void alreadyCheckedIn() {
            stubExisting(reservationWithStatus(1L, ReservationStatus.CHECKED_IN.code(), T0));
            assertEquals("already_checked_in", service.checkIn(1L, 7L, "1234", "127.0.0.1"));
        }

        @Test
        @DisplayName("并发抢占失败 → already_checked_in，不写签到记录")
        void losesCheckInRace() {
            clock.set(T0);
            stubExisting(approvedAt(T0.minusMinutes(1)));
            when(checkinCodeService.verify(1L, "1234")).thenReturn(true);
            when(reservationMapper.transit(anyLong(), anyInt(), anyInt(), any(), any(), any())).thenReturn(0);

            assertEquals("already_checked_in", service.checkIn(1L, 7L, "1234", "127.0.0.1"));
            verify(attendanceRecordService, never()).save(any());
        }

        @Test
        void notOwner() {
            Reservation r = approvedAt(T0.minusMinutes(1));
            r.setUserId(7L);
            stubExisting(r);
            assertEquals("error", service.checkIn(1L, 8L, "1234", "127.0.0.1"));
        }
    }

    // ====================================================================
    // 定时任务
    // ====================================================================
    @Nested
    @DisplayName("状态收敛")
    class Converge {

        @Test
        void releaseNoShowReleasesSlotsAndRevokesCode() {
            Reservation r = reservationWithStatus(1L, ReservationStatus.APPROVED.code(), T0.minusMinutes(10));
            when(reservationMapper.findCheckinOverdue(T0.minusMinutes(5))).thenReturn(List.of(r));
            when(reservationMapper.transit(anyLong(), anyInt(), anyInt(), any(), any(), any())).thenReturn(1);

            assertEquals(1, service.releaseNoShow(T0));

            verify(reservationMapper).transit(1L, ReservationStatus.APPROVED.code(),
                    ReservationStatus.TIMEOUT_RELEASED.code(), null,
                    "签到截止仍未签到，已自动释放", T0);
            verify(slotService).release(1L);
            verify(checkinCodeService).revoke(1L);
        }

        @Test
        void releaseNoShowSkipsWhenTransitionLoses() {
            Reservation r = reservationWithStatus(1L, ReservationStatus.APPROVED.code(), T0.minusMinutes(10));
            when(reservationMapper.findCheckinOverdue(any())).thenReturn(List.of(r));
            when(reservationMapper.transit(anyLong(), anyInt(), anyInt(), any(), any(), any())).thenReturn(0);

            assertEquals(0, service.releaseNoShow(T0));
            verify(slotService, never()).release(anyLong());
        }

        @Test
        void finishExpiredEndsBothApprovedAndCheckedIn() {
            Reservation a = reservationWithStatus(1L, ReservationStatus.APPROVED.code(), T0.minusHours(2));
            Reservation b = reservationWithStatus(2L, ReservationStatus.CHECKED_IN.code(), T0.minusHours(2));
            when(reservationMapper.findToFinish(T0)).thenReturn(List.of(a, b));
            when(reservationMapper.transit(anyLong(), anyInt(), anyInt(), any(), any(), any())).thenReturn(1);

            assertEquals(2, service.finishExpired(T0));
            verify(slotService).release(1L);
            verify(slotService).release(2L);
        }

        @Test
        @DisplayName("提醒只登记一次：条件更新返回 0 的不计入")
        void remindEndingOnlyOnce() {
            Reservation r = reservationWithStatus(1L, ReservationStatus.APPROVED.code(), T0.minusHours(1));
            when(reservationMapper.findEndingSoon(T0, T0.plusMinutes(5))).thenReturn(List.of(r));
            when(reservationMapper.markReminded(1L, T0)).thenReturn(1);

            assertEquals(1, service.remindEnding(T0).size());

            when(reservationMapper.markReminded(1L, T0)).thenReturn(0);
            assertTrue(service.remindEnding(T0).isEmpty());
        }

        @Test
        void releasePendingStartedDelegatesToBulkUpdate() {
            when(reservationMapper.releaseExpiredPending(T0)).thenReturn(3);
            assertEquals(3, service.releasePendingStarted(T0));
        }
    }

    // ====================================================================
    // 查询透传（防止有人把房间名等字段在改造中漏掉）
    // ====================================================================
    @Test
    void scheduleUsesDayBounds() {
        LocalDate day = LocalDate.of(2026, 10, 21);
        when(reservationMapper.findByRoomAndDate(1L, day.atStartOfDay(), day.plusDays(1).atStartOfDay()))
                .thenReturn(List.of());
        assertNotNull(service.findScheduleByRoomAndDate(1L, day));
    }

    @Test
    @DisplayName("slot 单元展开与占用集合一致")
    void unitsMatchSchema() {
        List<SlotUnit> units = schema.split(T0.toLocalDate().plusDays(1).atTime(9, 15),
                T0.toLocalDate().plusDays(1).atTime(10, 15));
        assertEquals(4, units.size());
        assertEquals(5, units.get(0).index());
    }
}
