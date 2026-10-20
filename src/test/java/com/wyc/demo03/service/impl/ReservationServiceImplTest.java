package com.wyc.demo03.service.impl;

import com.wyc.demo03.entity.AttendanceRecord;
import com.wyc.demo03.entity.MeetingRoom;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.entity.User;
import com.wyc.demo03.mapper.ReservationMapper;
import com.wyc.demo03.service.AttendanceRecordService;
import com.wyc.demo03.service.MeetingRoomService;
import com.wyc.demo03.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReservationServiceImpl 核心业务单测：
 * apply（容量/时间/冲突/教师覆盖/自动通过）、approve、reject、cancel、checkIn（含签到码防枚举）。
 */
@ExtendWith(MockitoExtension.class)
class ReservationServiceImplTest {

    @Mock
    private ReservationMapper reservationMapper;
    @Mock
    private AttendanceRecordService attendanceRecordService;
    @Mock
    private MeetingRoomService meetingRoomService;
    @Mock
    private UserService userService;
    @Mock
    private HttpSession session;

    private ReservationServiceImpl service;

    private MeetingRoom room;

    @BeforeEach
    void setUp() {
        service = new ReservationServiceImpl(attendanceRecordService, meetingRoomService, userService);
        ReflectionTestUtils.setField(service, "baseMapper", reservationMapper);

        room = new MeetingRoom();
        room.setId(1L);
        room.setRoomName("测试会议室");
        room.setCapacity(20);

        // 公共 stub：仅 apply 系列测试使用，lenient 避免严格模式报"不必要的 stub"
        lenient().when(session.getAttribute("Id")).thenReturn(100L);
        lenient().when(session.getAttribute("role")).thenReturn("STUDENT");
    }

    // ============ apply ============

    private Reservation newReservation() {
        Reservation r = new Reservation();
        r.setRoomId(1L);
        r.setStartTime(LocalDateTime.now().plusHours(2));
        r.setEndTime(LocalDateTime.now().plusHours(3));
        r.setAttendeeCount(5);
        return r;
    }

    @Test
    void applyReturnsRoomNotFoundWhenRoomMissing() {
        // Arrange
        when(meetingRoomService.getById(1L)).thenReturn(null);

        // Act
        String result = service.apply(newReservation(), session);

        // Assert
        assertEquals("room_not_found", result);
    }

    @Test
    void applyRejectsRoomThatBecameUnavailableAfterOpeningPage() {
        room.setRoomStatus(1);
        when(meetingRoomService.getById(1L)).thenReturn(room);
        assertEquals("room_maintenance", service.apply(newReservation(), session));
        verify(reservationMapper, never()).insert(any(Reservation.class));
    }

    @Test
    void applyRejectsZeroOrNegativeDuration() {
        when(meetingRoomService.getById(1L)).thenReturn(room);
        Reservation reservation = newReservation();
        reservation.setEndTime(reservation.getStartTime());
        assertEquals("invalid_time", service.apply(reservation, session));
        reservation.setEndTime(reservation.getStartTime().minusMinutes(15));
        assertEquals("invalid_time", service.apply(reservation, session));
        verify(reservationMapper, never()).insert(any(Reservation.class));
    }

    @Test
    void applyReturnsOverCapacityWhenAttendeesExceedRoom() {
        // Arrange
        when(meetingRoomService.getById(1L)).thenReturn(room);
        Reservation r = newReservation();
        r.setAttendeeCount(21);

        // Act
        String result = service.apply(r, session);

        // Assert
        assertEquals("over_capacity", result);
    }

    @Test
    void applyReturnsPastTimeWhenStartIsPast() {
        // Arrange
        when(meetingRoomService.getById(1L)).thenReturn(room);
        Reservation r = newReservation();
        r.setStartTime(LocalDateTime.now().minusHours(1));

        // Act
        String result = service.apply(r, session);

        // Assert
        assertEquals("past_time", result);
    }

    @Test
    void studentApplyReturnsConflictWhenOverlapExists() {
        // Arrange
        when(meetingRoomService.getById(1L)).thenReturn(room);
        when(reservationMapper.findConflicting(anyLong(), any(), any()))
                .thenReturn(List.of(new Reservation()));

        // Act
        String result = service.apply(newReservation(), session);

        // Assert
        assertEquals("conflict", result);
        verify(reservationMapper, never()).insert(any(Reservation.class));
    }

    @Test
    void studentApplyCreatesPendingReservation() {
        // Arrange
        when(meetingRoomService.getById(1L)).thenReturn(room);
        when(reservationMapper.findConflicting(anyLong(), any(), any())).thenReturn(List.of());
        when(reservationMapper.insert(any(Reservation.class))).thenReturn(1);

        // Act
        String result = service.apply(newReservation(), session);

        // Assert
        assertEquals("success", result);
        verify(reservationMapper).insert(any(Reservation.class));
        verify(attendanceRecordService, never()).save(any());
    }

    @Test
    void teacherApplyOverridesStudentConflictsAndAutoApproves() {
        // Arrange
        when(session.getAttribute("role")).thenReturn("TEACHER");
        when(meetingRoomService.getById(1L)).thenReturn(room);
        when(reservationMapper.findConflicting(anyLong(), any(), any())).thenReturn(List.of());

        // Act
        String result = service.apply(newReservation(), session);

        // Assert
        assertEquals("success", result);
        verify(reservationMapper).insert(any(Reservation.class));
        // 教师预约自动创建签到记录
        verify(attendanceRecordService).save(any(AttendanceRecord.class));
    }

    @Test
    void teacherApplyRejectsWhenConflictingWithTeacher() {
        // Arrange
        when(session.getAttribute("role")).thenReturn("TEACHER");
        when(meetingRoomService.getById(1L)).thenReturn(room);

        Reservation conflict = new Reservation();
        conflict.setUserId(200L);
        when(reservationMapper.findConflicting(anyLong(), any(), any())).thenReturn(List.of(conflict));

        User teacher = new User();
        teacher.setId(200L);
        teacher.setRole("TEACHER");
        when(userService.listByIds(any())).thenReturn(List.of(teacher));

        // Act
        String result = service.apply(newReservation(), session);

        // Assert
        assertEquals("conflict", result);
        verify(reservationMapper, never()).insert(any(Reservation.class));
    }

    @Test
    void teacherApplyOverridesStudentOnlyConflicts() {
        // Arrange
        when(session.getAttribute("role")).thenReturn("TEACHER");
        when(meetingRoomService.getById(1L)).thenReturn(room);

        Reservation conflict = new Reservation();
        conflict.setId(10L);
        conflict.setUserId(200L);
        conflict.setReservationStatus(0);
        when(reservationMapper.findConflicting(anyLong(), any(), any())).thenReturn(List.of(conflict));

        User student = new User();
        student.setId(200L);
        student.setRole("STUDENT");
        when(userService.listByIds(any())).thenReturn(List.of(student));
        when(reservationMapper.updateById(any(Reservation.class))).thenReturn(1);
        when(reservationMapper.insert(any(Reservation.class))).thenReturn(1);

        // Act
        String result = service.apply(newReservation(), session);

        // Assert
        assertEquals("success", result);
        // 学生预约被置为状态 3（被覆盖）
        verify(reservationMapper).updateById(any(Reservation.class));
        verify(reservationMapper).insert(any(Reservation.class));
    }

    // ============ approve ============

    @Test
    void approveGeneratesCodeAndAttendanceRecord() {
        // Arrange
        Reservation pending = new Reservation();
        pending.setId(1L);
        pending.setUserId(100L);
        pending.setReservationStatus(0);
        pending.setStartTime(LocalDateTime.now().plusHours(1));
        when(reservationMapper.selectById(1L)).thenReturn(pending);
        when(reservationMapper.update(any(Reservation.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);

        // Act
        String code = service.approve(1L);

        // Assert
        assertNotNull(code);
        assertEquals(4, code.length());
        assertEquals(1, pending.getReservationStatus());
        verify(attendanceRecordService).save(any(AttendanceRecord.class));
    }

    @Test
    void approveReturnsErrorWhenReservationMissing() {
        // Arrange
        when(reservationMapper.selectById(99L)).thenReturn(null);

        // Act
        String result = service.approve(99L);

        // Assert
        assertEquals("error", result);
    }

    @Test
    void approveReturnsErrorWhenAlreadyApproved() {
        // Arrange
        Reservation approved = new Reservation();
        approved.setId(1L);
        approved.setReservationStatus(1);
        when(reservationMapper.selectById(1L)).thenReturn(approved);

        // Act
        String result = service.approve(1L);

        // Assert
        assertEquals("error", result);
        verify(reservationMapper, never()).updateById(any(Reservation.class));
    }

    // ============ reject ============

    @Test
    void rejectOnlyRejectsPendingReservations() {
        when(reservationMapper.update(any(Reservation.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenAnswer(invocation -> {
                    Reservation rejected = invocation.getArgument(0);
                    var condition = (com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<?>) invocation.getArgument(1);
                    assertEquals(2, rejected.getReservationStatus());
                    assertEquals("该时段已有教学安排", rejected.getRejectReason());
                    org.junit.jupiter.api.Assertions.assertTrue(condition.getSqlSegment().contains("reservation_status ="));
                    org.junit.jupiter.api.Assertions.assertTrue(condition.getParamNameValuePairs().containsValue(0));
                    return 1;
                });
        service.reject(1L, "该时段已有教学安排");
    }

    @Test
    void rejectUsesDefaultReasonWhenBlank() {
        when(reservationMapper.update(any(Reservation.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenAnswer(invocation -> {
                    Reservation rejected = invocation.getArgument(0);
                    assertEquals("该时段无法安排，如有疑问请联系管理员", rejected.getRejectReason());
                    return 1;
                });
        service.reject(1L, "  ");
    }

    @Test
    void rejectReportsNoChangeInsteadOfSuccess() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.reject(1L, "该时段已有教学安排"));
        verify(reservationMapper, never()).updateById(any(Reservation.class));
    }

    @Test
    void rejectValidatesLengthBeforeDatabaseAccess() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.reject(1L, "拒".repeat(201)));
        org.mockito.Mockito.verifyNoInteractions(reservationMapper);
    }

    @Test
    void rejectAcceptsExactlyTwoHundredCharacters() {
        when(reservationMapper.update(any(Reservation.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(1);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.reject(1L, "拒".repeat(200)));
    }

    @Test
    void approveRejectsExpiredPendingWithoutCreatingAttendance() {
        Reservation pending = newReservation();
        pending.setReservationStatus(0);
        pending.setStartTime(LocalDateTime.now().minusMinutes(1));
        when(reservationMapper.selectById(1L)).thenReturn(pending);
        assertEquals("expired", service.approve(1L));
        org.mockito.Mockito.verifyNoInteractions(attendanceRecordService);
    }

    @Test
    void approveLosingStatusRaceDoesNotCreateAttendance() {
        Reservation pending = newReservation();
        pending.setReservationStatus(0);
        when(reservationMapper.selectById(1L)).thenReturn(pending);
        assertEquals("error", service.approve(1L));
        org.mockito.Mockito.verifyNoInteractions(attendanceRecordService);
    }

    // ============ cancel ============

    @Test
    void cancelReturnsNotOwnerForOtherUsersReservation() {
        // Arrange
        Reservation other = new Reservation();
        other.setId(1L);
        other.setUserId(999L);
        when(reservationMapper.selectById(1L)).thenReturn(other);

        // Act
        String result = service.cancel(1L, 100L);

        // Assert
        assertEquals("not_owner", result);
    }

    @Test
    void cancelReturnsInvalidStatusForRejectedReservation() {
        // Arrange
        Reservation rejected = new Reservation();
        rejected.setId(1L);
        rejected.setUserId(100L);
        rejected.setReservationStatus(2);
        when(reservationMapper.selectById(1L)).thenReturn(rejected);

        // Act
        String result = service.cancel(1L, 100L);

        // Assert
        assertEquals("invalid_status", result);
    }

    @Test
    void cancelReturnsAlreadyCheckedInWhenAttended() {
        // Arrange
        Reservation approved = new Reservation();
        approved.setId(1L);
        approved.setUserId(100L);
        approved.setReservationStatus(1);
        when(reservationMapper.selectById(1L)).thenReturn(approved);

        AttendanceRecord attended = new AttendanceRecord();
        attended.setAttendStatus(1);
        when(attendanceRecordService.findByReservationId(1L)).thenReturn(attended);

        // Act
        String result = service.cancel(1L, 100L);

        // Assert
        assertEquals("already_checked_in", result);
    }

    @Test
    void cancelSetsStatusToCancelled() {
        // Arrange
        Reservation pending = new Reservation();
        pending.setId(1L);
        pending.setUserId(100L);
        pending.setReservationStatus(0);
        when(reservationMapper.selectById(1L)).thenReturn(pending);
        when(reservationMapper.updateById(any(Reservation.class))).thenReturn(1);

        // Act
        String result = service.cancel(1L, 100L);

        // Assert
        assertEquals("success", result);
        assertEquals(4, pending.getReservationStatus());
    }

    // ============ checkIn ============

    private Reservation approvedReservation(LocalDateTime startTime) {
        Reservation r = new Reservation();
        r.setId(1L);
        r.setUserId(100L);
        r.setReservationStatus(1);
        r.setCheckInCode("1234");
        r.setStartTime(startTime);
        r.setEndTime(startTime.plusHours(1));
        return r;
    }

    private AttendanceRecord unattendedRecord() {
        AttendanceRecord record = new AttendanceRecord();
        record.setId(10L);
        record.setReservationId(1L);
        record.setUserId(100L);
        record.setAttendStatus(0);
        return record;
    }

    @Test
    void checkInReturnsErrorWhenReservationNotOwned() {
        // Arrange
        Reservation other = approvedReservation(LocalDateTime.now().plusMinutes(5));
        other.setUserId(999L);
        when(reservationMapper.selectById(1L)).thenReturn(other);

        // Act
        String result = service.checkIn(1L, 100L, "1234", "127.0.0.1");

        // Assert
        assertEquals("error", result);
    }

    @Test
    void checkInReturnsTooEarlyBeforeWindow() {
        // Arrange
        when(reservationMapper.selectById(1L))
                .thenReturn(approvedReservation(LocalDateTime.now().plusMinutes(20)));

        // Act
        String result = service.checkIn(1L, 100L, "1234", "127.0.0.1");

        // Assert
        assertEquals("too_early", result);
    }

    @Test
    void checkInReturnsExpiredAfterWindow() {
        // Arrange
        when(reservationMapper.selectById(1L))
                .thenReturn(approvedReservation(LocalDateTime.now().minusMinutes(20)));

        // Act
        String result = service.checkIn(1L, 100L, "1234", "127.0.0.1");

        // Assert
        assertEquals("expired", result);
    }

    @Test
    void checkInLocksAfterFiveWrongCodes() {
        // Arrange
        when(reservationMapper.selectById(1L))
                .thenReturn(approvedReservation(LocalDateTime.now().plusMinutes(5)));

        // Act：连续输错 5 次
        for (int i = 0; i < 5; i++) {
            assertEquals("wrong_code", service.checkIn(1L, 100L, "0000", "127.0.0.1"));
        }

        // Assert：第 6 次即使输对也被锁定
        assertEquals("too_many_attempts", service.checkIn(1L, 100L, "1234", "127.0.0.1"));
    }

    @Test
    void checkInSucceedsWithCorrectCode() {
        // Arrange
        when(reservationMapper.selectById(1L))
                .thenReturn(approvedReservation(LocalDateTime.now().plusMinutes(5)));
        when(attendanceRecordService.findByReservationId(1L)).thenReturn(unattendedRecord());
        when(attendanceRecordService.update(any(AttendanceRecord.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenAnswer(invocation -> {
                    var condition = (com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<?>) invocation.getArgument(1);
                    org.junit.jupiter.api.Assertions.assertTrue(condition.getSqlSegment().contains("attend_status ="));
                    org.junit.jupiter.api.Assertions.assertTrue(condition.getParamNameValuePairs().containsValue(0));
                    return true;
                });

        // Act
        String result = service.checkIn(1L, 100L, "1234", "127.0.0.1");

        // Assert
        assertEquals("success", result);
        verify(attendanceRecordService).update(any(AttendanceRecord.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    void concurrentCheckInsBothReadZeroButOnlyConditionalUpdateWinnerSucceeds() throws Exception {
        when(reservationMapper.selectById(1L))
                .thenReturn(approvedReservation(LocalDateTime.now().plusMinutes(5)));
        var readers = new java.util.concurrent.CyclicBarrier(2);
        when(attendanceRecordService.findByReservationId(1L)).thenAnswer(invocation -> {
            readers.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return unattendedRecord();
        });
        // 模拟数据库对 WHERE attend_status=0 的原子更新；另有断言检查实际条件。
        var claimed = new java.util.concurrent.atomic.AtomicBoolean();
        when(attendanceRecordService.update(any(AttendanceRecord.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenAnswer(invocation -> claimed.compareAndSet(false, true));
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.checkIn(1L, 100L, "1234", "127.0.0.1"));
            var second = executor.submit(() -> service.checkIn(1L, 100L, "1234", "127.0.0.2"));
            var results = List.of(first.get(5, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter("success"::equals).count());
            assertEquals(1, results.stream().filter("already_checked_in"::equals).count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void checkInLosingConditionalUpdateDoesNotReportSuccess() {
        when(reservationMapper.selectById(1L))
                .thenReturn(approvedReservation(LocalDateTime.now().plusMinutes(5)));
        when(attendanceRecordService.findByReservationId(1L)).thenReturn(unattendedRecord());
        when(attendanceRecordService.update(any(AttendanceRecord.class), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(false);
        assertEquals("already_checked_in", service.checkIn(1L, 100L, "1234", "127.0.0.1"));
        verify(attendanceRecordService, never()).updateById(any(AttendanceRecord.class));
    }

    @Test
    void checkInReturnsAlreadyCheckedInForSecondAttempt() {
        // Arrange
        when(reservationMapper.selectById(1L))
                .thenReturn(approvedReservation(LocalDateTime.now().plusMinutes(5)));

        AttendanceRecord attended = unattendedRecord();
        attended.setAttendStatus(1);
        when(attendanceRecordService.findByReservationId(1L)).thenReturn(attended);

        // Act
        String result = service.checkIn(1L, 100L, "1234", "127.0.0.1");

        // Assert
        assertEquals("already_checked_in", result);
    }
}
