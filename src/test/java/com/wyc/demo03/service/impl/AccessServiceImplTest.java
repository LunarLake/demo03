package com.wyc.demo03.service.impl;

import com.wyc.demo03.config.DoorProperties;
import com.wyc.demo03.device.DispatchResult;
import com.wyc.demo03.device.DoorController;
import com.wyc.demo03.domain.ReservationStatus;
import com.wyc.demo03.entity.AccessLog;
import com.wyc.demo03.entity.Device;
import com.wyc.demo03.entity.DeviceCommand;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.mapper.AccessLogMapper;
import com.wyc.demo03.mapper.DeviceMapper;
import com.wyc.demo03.mapper.ReservationMapper;
import com.wyc.demo03.service.AccessService;
import com.wyc.demo03.service.DeviceCommandService;
import com.wyc.demo03.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开门授权规则。
 *
 * <p>核心命题：门禁是**物理世界**的最终防线，任何一条校验漏掉都能让人进到不该进的房间。
 * 因此这里逐条验证五种拒绝原因，并且要求"拒绝也要留痕"。
 */
@ExtendWith(MockitoExtension.class)
class AccessServiceImplTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 10, 10);
    private static final Long USER = 7L;
    private static final Long RES_ID = 42L;

    @Mock private ReservationMapper reservationMapper;
    @Mock private DeviceMapper deviceMapper;
    @Mock private AccessLogMapper accessLogMapper;
    @Mock private DeviceCommandService deviceCommandService;
    @Mock private DoorController doorController;

    private final MutableClock clock = MutableClock.at(NOW);
    private final DoorProperties doorProperties = new DoorProperties();
    private AccessServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AccessServiceImpl(reservationMapper, deviceMapper, accessLogMapper,
                deviceCommandService, doorController, doorProperties, clock);
    }

    private Reservation reservation(int status, LocalDateTime start, LocalDateTime end) {
        Reservation r = new Reservation();
        r.setId(RES_ID);
        r.setUserId(USER);
        r.setRoomId(1L);
        r.setReservationStatus(status);
        r.setStartTime(start);
        r.setEndTime(end);
        return r;
    }

    private Device device() {
        Device d = new Device();
        d.setId(3L);
        d.setDeviceCode("ROOM-A-01");
        d.setRoomId(1L);
        d.setOnline(1);
        return d;
    }

    private AccessLog capturedLog() {
        ArgumentCaptor<AccessLog> captor = ArgumentCaptor.forClass(AccessLog.class);
        verify(accessLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    // ==================== 拒绝分支 ====================

    @Test
    @DisplayName("非本人 → NOT_OWNER，且不泄露预约是否存在")
    void notOwner() {
        when(reservationMapper.selectById(RES_ID))
                .thenReturn(reservation(ReservationStatus.CHECKED_IN.code(), NOW.minusMinutes(5), NOW.plusHours(1)));

        AccessService.UnlockResult result = service.reopen(RES_ID, 999L, "127.0.0.1");

        assertFalse(result.granted());
        assertEquals("NOT_OWNER", result.denyReason());
        assertEquals(0, capturedLog().getGranted());
        verify(deviceCommandService, never()).issue(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("未签到就想开门 → 提示先签到（首次开锁应由签到触发）")
    void notCheckedInYet() {
        when(reservationMapper.selectById(RES_ID))
                .thenReturn(reservation(ReservationStatus.APPROVED.code(), NOW.minusMinutes(1), NOW.plusHours(1)));

        AccessService.UnlockResult result = service.reopen(RES_ID, USER, "127.0.0.1");

        assertEquals("STATUS_INVALID", result.denyReason());
        assertTrue(result.message().contains("签到"));
    }

    @Test
    @DisplayName("已结束的预约不能开门")
    void afterEnd() {
        when(reservationMapper.selectById(RES_ID))
                .thenReturn(reservation(ReservationStatus.CHECKED_IN.code(), NOW.minusHours(2), NOW.minusMinutes(1)));

        assertEquals("OUT_OF_WINDOW", service.reopen(RES_ID, USER, "127.0.0.1").denyReason());
    }

    @Test
    @DisplayName("还没开始也不能开门")
    void beforeStart() {
        when(reservationMapper.selectById(RES_ID))
                .thenReturn(reservation(ReservationStatus.CHECKED_IN.code(), NOW.plusMinutes(10), NOW.plusHours(1)));

        assertEquals("OUT_OF_WINDOW", service.reopen(RES_ID, USER, "127.0.0.1").denyReason());
    }

    @Test
    @DisplayName("房间没有设备 → DEVICE_OFFLINE，且记录里 device_id 为空（仍要留痕）")
    void noDevice() {
        when(reservationMapper.selectById(RES_ID))
                .thenReturn(reservation(ReservationStatus.CHECKED_IN.code(), NOW.minusMinutes(5), NOW.plusHours(1)));
        when(deviceMapper.findByRoomId(1L)).thenReturn(null);

        AccessService.UnlockResult result = service.reopen(RES_ID, USER, "127.0.0.1");

        assertEquals("DEVICE_OFFLINE", result.denyReason());
        AccessLog log = capturedLog();
        assertNull(log.getDeviceId());
        assertEquals(0, log.getGranted());
    }

    @Test
    @DisplayName("设备不可达 → DEVICE_OFFLINE，不下发指令")
    void deviceUnreachable() {
        when(reservationMapper.selectById(RES_ID))
                .thenReturn(reservation(ReservationStatus.CHECKED_IN.code(), NOW.minusMinutes(5), NOW.plusHours(1)));
        when(deviceMapper.findByRoomId(1L)).thenReturn(device());
        when(doorController.reachable(any())).thenReturn(false);

        assertEquals("DEVICE_OFFLINE", service.reopen(RES_ID, USER, "127.0.0.1").denyReason());
        verify(deviceCommandService, never()).issue(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    // ==================== 放行分支 ====================

    @Test
    @DisplayName("放行：下发一次性指令并回执，记录 granted=1")
    void grantedAndAcked() {
        when(reservationMapper.selectById(RES_ID))
                .thenReturn(reservation(ReservationStatus.CHECKED_IN.code(), NOW.minusMinutes(5), NOW.plusHours(1)));
        when(deviceMapper.findByRoomId(1L)).thenReturn(device());
        when(doorController.reachable(any())).thenReturn(true);
        DeviceCommand command = new DeviceCommand();
        command.setMsgId("msg-1");
        command.setId(11L);
        when(deviceCommandService.issue(eq(3L), eq(RES_ID), eq(DeviceCommand.Type.UNLOCK), any(), eq(30)))
                .thenReturn(command);
        when(doorController.dispatch(any(), any())).thenReturn(DispatchResult.ACKED);

        AccessService.UnlockResult result = service.reopen(RES_ID, USER, "127.0.0.1");

        assertTrue(result.granted());
        assertTrue(result.acknowledged());
        assertEquals("msg-1", result.msgId());
        verify(deviceCommandService).ack("msg-1", DeviceCommand.Status.ACKED, "OK");

        AccessLog log = capturedLog();
        assertEquals(1, log.getGranted());
        assertEquals(3L, log.getDeviceId());
        assertEquals(11L, log.getCommandId());
    }

    @Test
    @DisplayName("指令发出但设备未回执 → 仍算已授权，状态 SENT")
    void grantedButNotDelivered() {
        when(reservationMapper.selectById(RES_ID))
                .thenReturn(reservation(ReservationStatus.CHECKED_IN.code(), NOW.minusMinutes(5), NOW.plusHours(1)));
        when(deviceMapper.findByRoomId(1L)).thenReturn(device());
        when(doorController.reachable(any())).thenReturn(true);
        DeviceCommand command = new DeviceCommand();
        command.setMsgId("msg-2");
        when(deviceCommandService.issue(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(command);
        when(doorController.dispatch(any(), any())).thenReturn(DispatchResult.SENT);

        AccessService.UnlockResult result = service.reopen(RES_ID, USER, "127.0.0.1");

        assertTrue(result.granted());
        assertFalse(result.acknowledged());
        assertEquals("SENT", result.commandStatus());
        verify(deviceCommandService, never()).ack(any(), any(), any());
    }

    @Test
    @DisplayName("签到后的自动开门：不校验签到码，但仍校验时段")
    void openAfterCheckInChecksWindow() {
        Reservation r = reservation(ReservationStatus.CHECKED_IN.code(), NOW.minusHours(3), NOW.minusMinutes(10));

        AccessService.UnlockResult result = service.openAfterCheckIn(r, USER, "127.0.0.1");

        assertFalse(result.granted());
        assertEquals("OUT_OF_WINDOW", result.denyReason());
    }

    @Test
    @DisplayName("签到后自动开门成功：指令载荷包含预约与时段，便于设备端自检")
    void openAfterCheckInPayload() {
        Reservation r = reservation(ReservationStatus.CHECKED_IN.code(), NOW.minusMinutes(1), NOW.plusHours(1));
        when(deviceMapper.findByRoomId(1L)).thenReturn(device());
        when(doorController.reachable(any())).thenReturn(true);
        DeviceCommand command = new DeviceCommand();
        command.setMsgId("msg-3");
        when(deviceCommandService.issue(any(), any(), any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(command);
        when(doorController.dispatch(any(), any())).thenReturn(DispatchResult.ACKED);

        assertTrue(service.openAfterCheckIn(r, USER, "127.0.0.1").granted());

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(deviceCommandService).issue(any(), any(), any(), payload.capture(),
                org.mockito.ArgumentMatchers.anyInt());
        assertTrue(payload.getValue().contains("reservation=42"));
        assertTrue(payload.getValue().contains("start="));
    }
}
