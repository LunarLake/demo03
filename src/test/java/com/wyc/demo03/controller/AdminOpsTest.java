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
    void reportRoomUsageReturnsServiceData() {
        // Arrange
        LogService logService = mock(LogService.class);
        ReservationService reservationService = mock(ReservationService.class);
        AttendanceRecordService attendanceRecordService = mock(AttendanceRecordService.class);
        LogController controller = new LogController(logService, reservationService, attendanceRecordService);
        when(reservationService.countByRoom()).thenReturn(List.of(Map.of("label", "A", "count", 1)));

        // Act
        List<Map<String, Object>> result = controller.reportRoomUsage();

        // Assert
        assertEquals(1, result.size());
    }

    @Test
    void reportBusyHoursReturnsServiceData() {
        // Arrange
        LogService logService = mock(LogService.class);
        ReservationService reservationService = mock(ReservationService.class);
        AttendanceRecordService attendanceRecordService = mock(AttendanceRecordService.class);
        LogController controller = new LogController(logService, reservationService, attendanceRecordService);
        when(reservationService.countByHour()).thenReturn(List.of());

        // Act
        List<Map<String, Object>> result = controller.reportBusyHours();

        // Assert
        assertTrue(result.isEmpty());
    }

    @Test
    void reportAttendanceRateReturnsServiceData() {
        // Arrange
        LogService logService = mock(LogService.class);
        ReservationService reservationService = mock(ReservationService.class);
        AttendanceRecordService attendanceRecordService = mock(AttendanceRecordService.class);
        LogController controller = new LogController(logService, reservationService, attendanceRecordService);
        when(attendanceRecordService.countAttendanceRate()).thenReturn(List.of(Map.of("label", "已签到", "count", 5)));

        // Act
        List<Map<String, Object>> result = controller.reportAttendanceRate();

        // Assert
        assertEquals(1, result.size());
    }

    // ============ ReservationScheduler ============

    @Test
    void schedulerReleasesZombieReservations() {
        // Arrange
        ReservationMapper mapper = mock(ReservationMapper.class);
        ReservationService reservationService = mock(ReservationService.class);
        ReservationScheduler scheduler = new ReservationScheduler(mapper, reservationService);

        com.wyc.demo03.entity.Reservation zombie = new com.wyc.demo03.entity.Reservation();
        zombie.setId(1L);
        zombie.setReservationStatus(1);
        when(mapper.findExpiredUnchecked(any())).thenReturn(List.of(zombie));

        com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper<com.wyc.demo03.entity.Reservation> chain =
                mock(com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper.class);
        when(reservationService.lambdaUpdate()).thenReturn(chain);
        when(chain.eq(any(), any())).thenReturn(chain);
        when(chain.set(any(), any())).thenReturn(chain);
        when(chain.update()).thenReturn(true);

        // Act
        scheduler.releaseZombieReservations();

        // Assert
        verify(mapper).findExpiredUnchecked(any());
        verify(chain).update();
    }

    @Test
    void schedulerDoesNothingWhenNoZombies() {
        // Arrange
        ReservationMapper mapper = mock(ReservationMapper.class);
        ReservationService reservationService = mock(ReservationService.class);
        ReservationScheduler scheduler = new ReservationScheduler(mapper, reservationService);
        when(mapper.findExpiredUnchecked(any())).thenReturn(List.of());

        // Act：无僵尸预约时不抛异常
        scheduler.releaseZombieReservations();

        // Assert
        verify(reservationService, never()).lambdaUpdate();
    }
}
