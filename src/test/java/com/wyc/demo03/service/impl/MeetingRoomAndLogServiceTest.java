package com.wyc.demo03.service.impl;

import com.wyc.demo03.entity.Log;
import com.wyc.demo03.entity.MeetingRoom;
import com.wyc.demo03.mapper.LogMapper;
import com.wyc.demo03.mapper.MeetingRoomMapper;
import com.wyc.demo03.mapper.ReservationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MeetingRoomServiceImpl（更新/删除的活跃预约守卫）与 LogServiceImpl 的单元测试。
 */
@ExtendWith(MockitoExtension.class)
class MeetingRoomAndLogServiceTest {

    @Mock
    private MeetingRoomMapper meetingRoomMapper;
    @Mock
    private ReservationMapper reservationMapper;
    @Mock
    private LogMapper logMapper;

    private MeetingRoomServiceImpl meetingRoomService;
    private LogServiceImpl logService;

    @BeforeEach
    void setUp() {
        meetingRoomService = new MeetingRoomServiceImpl(reservationMapper);
        ReflectionTestUtils.setField(meetingRoomService, "baseMapper", meetingRoomMapper);

        logService = new LogServiceImpl();
        ReflectionTestUtils.setField(logService, "baseMapper", logMapper);
    }

    // ============ MeetingRoomServiceImpl.updateRoom ============

    @Test
    void updateRoomRejectsMaintenanceWhenActiveReservationsExist() {
        // Arrange：房间从正常切换为维护中，存在活跃预约
        MeetingRoom room = new MeetingRoom();
        room.setId(1L);
        room.setRoomStatus(1);

        MeetingRoom existing = new MeetingRoom();
        existing.setId(1L);
        existing.setRoomStatus(0);
        when(meetingRoomMapper.selectById(1L)).thenReturn(existing);
        when(reservationMapper.selectCount(any())).thenReturn(2L);

        // Act
        String result = meetingRoomService.updateRoom(room);

        // Assert
        assertEquals("has_active:2", result);
        verify(meetingRoomMapper, never()).updateById(any(MeetingRoom.class));
    }

    @Test
    void updateRoomProceedsWhenNoActiveReservations() {
        // Arrange
        MeetingRoom room = new MeetingRoom();
        room.setId(1L);
        room.setRoomStatus(1);

        MeetingRoom existing = new MeetingRoom();
        existing.setId(1L);
        existing.setRoomStatus(0);
        when(meetingRoomMapper.selectById(1L)).thenReturn(existing);
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(meetingRoomMapper.updateById(any(MeetingRoom.class))).thenReturn(1);

        // Act
        String result = meetingRoomService.updateRoom(room);

        // Assert
        assertEquals("success", result);
        verify(meetingRoomMapper).updateById(room);
    }

    @Test
    void updateRoomSkipsGuardWhenNotSwitchingToMaintenance() {
        // Arrange：房间状态不变（保持正常），无需守卫检查
        MeetingRoom room = new MeetingRoom();
        room.setId(1L);
        room.setRoomStatus(0);
        when(meetingRoomMapper.updateById(any(MeetingRoom.class))).thenReturn(1);

        // Act
        String result = meetingRoomService.updateRoom(room);

        // Assert
        assertEquals("success", result);
        verify(reservationMapper, never()).selectCount(any());
    }

    // ============ MeetingRoomServiceImpl.deleteRoom ============

    @Test
    void deleteRoomRejectsWhenActiveReservationsExist() {
        // Arrange
        when(reservationMapper.selectCount(any())).thenReturn(3L);

        // Act
        String result = meetingRoomService.deleteRoom(1L);

        // Assert
        assertEquals("has_active:3", result);
        verify(meetingRoomMapper, never()).deleteById(any(java.io.Serializable.class));
    }

    @Test
    void deleteRoomProceedsWhenNoActiveReservations() {
        // Arrange
        when(reservationMapper.selectCount(any())).thenReturn(0L);
        when(meetingRoomMapper.deleteById(1L)).thenReturn(1);

        // Act
        String result = meetingRoomService.deleteRoom(1L);

        // Assert
        assertEquals("success", result);
        verify(meetingRoomMapper).deleteById(1L);
    }

    // ============ LogServiceImpl ============

    @Test
    void saveAsyncDelegatesToBaseMapper() {
        // Arrange
        Log sysLog = new Log();
        sysLog.setUsername("admin");
        when(logMapper.insert(any(Log.class))).thenReturn(1);

        // Act
        logService.saveAsync(sysLog);

        // Assert
        verify(logMapper).insert(sysLog);
    }

    @Test
    void countQueriesDelegateToBaseMapper() {
        // Arrange
        when(logMapper.countByUrl()).thenReturn(List.of(Map.of("url", "/rooms")));
        when(logMapper.countTop6ByUrl()).thenReturn(List.of());
        when(logMapper.countByUsername()).thenReturn(List.of(Map.of("username", "admin")));
        when(logMapper.countTop6ByUsername()).thenReturn(List.of());
        when(logMapper.countByDay()).thenReturn(List.of(Map.of("day", "2026-08-13")));

        // Act & Assert
        assertEquals(1, logService.countByUrl().size());
        assertEquals(0, logService.countTop6ByUrl().size());
        assertEquals(1, logService.countByUsername().size());
        assertEquals(0, logService.countTop6ByUsername().size());
        assertEquals(1, logService.countByDay().size());
    }

    @Test
    void deleteBeforeDelegatesToBaseMapperWithCutoff() {
        // Arrange
        when(logMapper.delete(any())).thenReturn(42);

        // Act
        int deleted = logService.deleteBefore(java.time.LocalDateTime.now().minusDays(30));

        // Assert
        assertEquals(42, deleted);
        verify(logMapper).delete(any());
    }
}
