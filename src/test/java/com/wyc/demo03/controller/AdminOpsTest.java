package com.wyc.demo03.controller;

import com.wyc.demo03.entity.MeetingRoom;
import com.wyc.demo03.mapper.ReservationMapper;
import com.wyc.demo03.service.AttendanceRecordService;
import com.wyc.demo03.service.LogService;
import com.wyc.demo03.service.MeetingRoomService;
import com.wyc.demo03.service.ReservationService;
import com.wyc.demo03.service.impl.MeetingRoomServiceImpl;
import com.wyc.demo03.service.impl.ReservationServiceImpl;
import com.wyc.demo03.task.ReservationScheduler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RoomController 管理操作 / LogController API / ReservationScheduler 的单元测试。
 */
class AdminOpsTest {

    // ============ RoomController 管理操作 ============

    @Test
    void roomUpdateShowsActiveReservationHint() {
        // Arrange
        MeetingRoomService meetingRoomService = mock(MeetingRoomService.class);
        ReservationService reservationService = mock(ReservationService.class);
        RoomController controller = new RoomController(meetingRoomService, reservationService);
        when(meetingRoomService.updateRoom(any())).thenReturn("has_active:3");

        // Act
        String view = controller.update(new MeetingRoom(), mock(org.springframework.web.servlet.mvc.support.RedirectAttributes.class));

        // Assert
        assertEquals("redirect:/rooms", view);
    }

    @Test
    void roomUpdateSuccess() {
        // Arrange
        MeetingRoomService meetingRoomService = mock(MeetingRoomService.class);
        ReservationService reservationService = mock(ReservationService.class);
        RoomController controller = new RoomController(meetingRoomService, reservationService);
        when(meetingRoomService.updateRoom(any())).thenReturn("success");

        // Act
        String view = controller.update(new MeetingRoom(), mock(org.springframework.web.servlet.mvc.support.RedirectAttributes.class));

        // Assert
        assertEquals("redirect:/rooms", view);
    }

    @Test
    void roomDeleteDelegatesToService() {
        // Arrange
        MeetingRoomService meetingRoomService = mock(MeetingRoomService.class);
        ReservationService reservationService = mock(ReservationService.class);
        RoomController controller = new RoomController(meetingRoomService, reservationService);
        when(meetingRoomService.deleteRoom(1L)).thenReturn("success");

        // Act
        String view = controller.delete(1L, mock(org.springframework.web.servlet.mvc.support.RedirectAttributes.class));

        // Assert
        assertEquals("redirect:/rooms", view);
        verify(meetingRoomService).deleteRoom(1L);
    }

    @Test
    void roomAddSavesRoom() {
        // Arrange
        MeetingRoomService meetingRoomService = mock(MeetingRoomService.class);
        ReservationService reservationService = mock(ReservationService.class);
        RoomController controller = new RoomController(meetingRoomService, reservationService);

        // Act
        MeetingRoom room = new MeetingRoom();
        room.setRoomName("新会议室");
        String view = controller.add(room, mock(org.springframework.web.servlet.mvc.support.RedirectAttributes.class));

        // Assert
        assertEquals("redirect:/rooms", view);
        verify(meetingRoomService).save(room);
    }

    // ============ LogController API ============

    @Test
    void reportRoomUsageReturnsEnvelope() {
        // Arrange
        LogService logService = mock(LogService.class);
        ReservationService reservationService = mock(ReservationService.class);
        AttendanceRecordService attendanceRecordService = mock(AttendanceRecordService.class);
        LogController controller = new LogController(logService, reservationService, attendanceRecordService);
        when(reservationService.countByRoom()).thenReturn(List.of(Map.of("label", "A", "count", 1)));

        // Act
        var result = controller.reportRoomUsage();

        // Assert
        assertTrue(result.success());
        assertEquals(1, result.data().size());
    }

    @Test
    void reportBusyHoursReturnsEnvelope() {
        // Arrange
        LogService logService = mock(LogService.class);
        ReservationService reservationService = mock(ReservationService.class);
        AttendanceRecordService attendanceRecordService = mock(AttendanceRecordService.class);
        LogController controller = new LogController(logService, reservationService, attendanceRecordService);
        when(reservationService.countByHour()).thenReturn(List.of());

        // Act
        var result = controller.reportBusyHours();

        // Assert
        assertTrue(result.success());
        assertTrue(result.data().isEmpty());
    }

    @Test
    void reportAttendanceRateReturnsEnvelope() {
        // Arrange
        LogService logService = mock(LogService.class);
        ReservationService reservationService = mock(ReservationService.class);
        AttendanceRecordService attendanceRecordService = mock(AttendanceRecordService.class);
        LogController controller = new LogController(logService, reservationService, attendanceRecordService);
        when(attendanceRecordService.countAttendanceRate()).thenReturn(List.of(Map.of("label", "已签到", "count", 5)));

        // Act
        var result = controller.reportAttendanceRate();

        // Assert
        assertTrue(result.success());
        assertEquals(1, result.data().size());
    }

    // ============ ReservationScheduler ============
    // 调度器编排的行为测试已迁到 com.wyc.demo03.task.ReservationExpiryTest
    // （v1 在 AdminOpsTest 里重复测了一遍调度器，v2 起统一收口到 task 包）。
}

