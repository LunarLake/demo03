package com.wyc.demo03.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wyc.demo03.domain.ReservationStatus;
import com.wyc.demo03.entity.AccessLog;
import com.wyc.demo03.entity.CheckinCode;
import com.wyc.demo03.entity.DeviceCommand;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.entity.User;
import com.wyc.demo03.mapper.AccessLogMapper;
import com.wyc.demo03.mapper.CheckinCodeMapper;
import com.wyc.demo03.mapper.DeviceCommandMapper;
import com.wyc.demo03.mapper.ReservationMapper;
import com.wyc.demo03.service.AccessService;
import com.wyc.demo03.service.DeviceCommandService;
import com.wyc.demo03.service.ReservationService;
import com.wyc.demo03.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 签到→开门→再次开门的真库链路。
 *
 * <p>这一段必须在真库上验：它横跨预约状态、签到记录、设备指令、开门日志四张表，
 * 任何一处唯一键或时间窗写错，表现都是"人站在门口打不开"。
 */
@SpringBootTest
@Transactional
class AccessFlowIntegrationTest {

    @Autowired private ReservationService reservationService;
    @Autowired private AccessService accessService;
    @Autowired private DeviceCommandService deviceCommandService;
    @Autowired private ReservationMapper reservationMapper;
    @Autowired private CheckinCodeMapper checkinCodeMapper;
    @Autowired private DeviceCommandMapper deviceCommandMapper;
    @Autowired private AccessLogMapper accessLogMapper;
    @Autowired private UserService userService;

    private User student;

    @BeforeEach
    void setUp() {
        student = userService.lambdaQuery().eq(User::getUsername, "student01").one();
        assertNotNull(student);
    }

    /** 造一笔"此刻开始"的已通过预约 + 签到码。 */
    private Reservation insertApprovedNow() {
        LocalDateTime start = LocalDateTime.now().withSecond(0).withNano(0);
        Reservation r = new Reservation();
        r.setUserId(student.getId());
        r.setRoomId(1L);
        r.setStartTime(start);
        r.setEndTime(start.plusHours(1));
        r.setAttendeeCount(2);
        r.setReservationStatus(ReservationStatus.APPROVED.code());
        reservationMapper.insert(r);

        CheckinCode code = new CheckinCode();
        code.setReservationId(r.getId());
        code.setCode("4321");
        code.setIssuedAt(LocalDateTime.now());
        checkinCodeMapper.insert(code);
        return r;
    }

    private List<AccessLog> logs(Long reservationId) {
        return accessLogMapper.findByReservationId(reservationId);
    }

    private List<DeviceCommand> commands(Long reservationId) {
        return deviceCommandMapper.selectList(new LambdaQueryWrapper<DeviceCommand>()
                .eq(DeviceCommand::getReservationId, reservationId));
    }

    // ====================================================================

    @Test
    @DisplayName("签到成功 → 自动下发开锁指令并回执，同时留一条已授权的开门记录")
    void checkInTriggersUnlock() {
        Reservation r = insertApprovedNow();

        String result = reservationService.checkIn(r.getId(), student.getId(), "4321", "127.0.0.1");

        assertEquals("success", result, "log 模式下模拟设备应立刻回执 OK");

        List<DeviceCommand> cmds = commands(r.getId());
        assertEquals(1, cmds.size(), "签到应只下发一条开锁指令");
        assertEquals("ACKED", cmds.get(0).getStatus());
        assertEquals("UNLOCK", cmds.get(0).getCmdType());
        assertNotNull(cmds.get(0).getExpiresAt(), "一次性指令必须有有效期");

        List<AccessLog> accessLogs = logs(r.getId());
        assertEquals(1, accessLogs.size());
        assertEquals(1, accessLogs.get(0).getGranted());
        assertEquals(cmds.get(0).getId(), accessLogs.get(0).getCommandId());
    }

    @Test
    @DisplayName("未签到就想开门 → 拒绝并留痕，不下发指令")
    void cannotOpenBeforeCheckIn() {
        Reservation r = insertApprovedNow();

        AccessService.UnlockResult result = accessService.reopen(r.getId(), student.getId(), "127.0.0.1");

        assertFalse(result.granted());
        assertEquals("STATUS_INVALID", result.denyReason());
        assertTrue(commands(r.getId()).isEmpty(), "被拒时不应产生指令");

        List<AccessLog> accessLogs = logs(r.getId());
        assertEquals(1, accessLogs.size());
        assertEquals(0, accessLogs.get(0).getGranted());
        assertEquals("STATUS_INVALID", accessLogs.get(0).getDenyReason());
    }

    @Test
    @DisplayName("已签到后可再次开门：每次一条指令、一条记录")
    void canReopenAfterCheckIn() {
        Reservation r = insertApprovedNow();
        reservationService.checkIn(r.getId(), student.getId(), "4321", "127.0.0.1");

        AccessService.UnlockResult again = accessService.reopen(r.getId(), student.getId(), "127.0.0.1");

        assertTrue(again.granted(), "已签到且在有效期内，应允许再次开门");
        assertEquals(2, commands(r.getId()).size(), "首次开锁 + 再次开门各一条指令");
        assertEquals(2, logs(r.getId()).size(), "每次开门都要留记录");
    }

    @Test
    @DisplayName("别人不能替你开门")
    void otherUserCannotOpen() {
        Reservation r = insertApprovedNow();
        reservationService.checkIn(r.getId(), student.getId(), "4321", "127.0.0.1");

        Long otherUserId = userService.lambdaQuery().eq(User::getUsername, "student02").one().getId();
        AccessService.UnlockResult result = accessService.reopen(r.getId(), otherUserId, "127.0.0.1");

        assertFalse(result.granted());
        assertEquals("NOT_OWNER", result.denyReason());
        assertEquals(1, commands(r.getId()).size(), "拒绝不应新增指令");
    }

    @Test
    @DisplayName("过期指令被置 EXPIRED，不会被迟到的回执改成 ACKED")
    void expiredCommandIsNotReplayable() {
        DeviceCommand cmd = deviceCommandService.issue(1L, null, DeviceCommand.Type.UNLOCK, "{}", -5);
        assertEquals("PENDING", cmd.getStatus());

        int expired = deviceCommandService.expireOverdue(LocalDateTime.now());
        assertTrue(expired >= 1);
        assertEquals("EXPIRED", deviceCommandService.findByMsgId(cmd.getMsgId()).getStatus());

        assertFalse(deviceCommandService.ack(cmd.getMsgId(), DeviceCommand.Status.ACKED, "OK"),
                "已过期/非 SENT 的指令不接受回执");
    }

    @Test
    @DisplayName("重复回执只生效一次：QoS1 重复投递不会把状态改两次")
    void ackIsIdempotentByStatus() {
        DeviceCommand cmd = deviceCommandService.issue(1L, null, DeviceCommand.Type.UNLOCK, "{}", 60);

        assertTrue(deviceCommandService.markSent(cmd.getMsgId()), "PENDING → SENT 应成功");
        assertTrue(deviceCommandService.ack(cmd.getMsgId(), DeviceCommand.Status.ACKED, "OK"));
        assertFalse(deviceCommandService.ack(cmd.getMsgId(), DeviceCommand.Status.ACKED, "OK"),
                "第二次回执应被 status='SENT' 条件挡住");
    }
}
