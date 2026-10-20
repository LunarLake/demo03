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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 MQTT 链路（进程内总线）下的签到与开门。
 *
 * <p>与 {@link AccessFlowIntegrationTest} 的区别：那边用 `LogDoorController`（直接回 ACKED），
 * 这边把 {@code app.door.mode} 切成 {@code mqtt}，让指令真正走一遍
 * "信封 + HMAC → 模拟器五步校验 → 回执 → 服务端结算"。
 *
 * <p>这是唯一能证明"接上 Broker 后真的能开锁"的测试，也是项目从纯软件变成软硬件结合的分界线。
 */
@SpringBootTest(properties = {
        "app.door.mode=mqtt",
        "app.mqtt.mode=inprocess",
        "app.simulator.enabled=true",
        "app.simulator.device-code=ROOM-A-01",
        "app.simulator.secret=demo-secret-room-a-please-change",
        "app.simulator.fail-rate=0.0",
        "app.simulator.screen-dir=target/simulator-it"
})
@Transactional
class MqttAccessIntegrationTest {

    @Autowired private ReservationService reservationService;
    @Autowired private AccessService accessService;
    @Autowired private ReservationMapper reservationMapper;
    @Autowired private CheckinCodeMapper checkinCodeMapper;
    @Autowired private DeviceCommandMapper deviceCommandMapper;
    @Autowired private AccessLogMapper accessLogMapper;
    @Autowired private UserService userService;
    @Autowired private com.wyc.demo03.simulator.DeviceSimulator simulator;

    private User student;

    @BeforeEach
    void setUp() {
        student = userService.lambdaQuery().eq(User::getUsername, "student01").one();
        assertNotNull(student);
    }

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

    private List<DeviceCommand> commands(Long reservationId) {
        return deviceCommandMapper.selectList(new LambdaQueryWrapper<DeviceCommand>()
                .eq(DeviceCommand::getReservationId, reservationId));
    }

    @Test
    @DisplayName("签到 → 走 MQTT → 模拟器校验并执行 → 回执 → 服务端结算 ACKED")
    void checkInUnlocksThroughMqtt() {
        Reservation r = insertApprovedNow();

        String result = reservationService.checkIn(r.getId(), student.getId(), "4321", "127.0.0.1");
        assertEquals("success", result, "签到本身只落库");

        // 控制器在签到提交后才下发指令（BUG-004）
        var unlock = accessService.openAfterCheckIn(r.getId(), student.getId(), "127.0.0.1");
        assertTrue(unlock.granted(), "应已授权下发");
        assertTrue(unlock.acknowledged(), "进程内总线同步回执，应已 ACKED");

        List<DeviceCommand> cmds = commands(r.getId());
        assertEquals(1, cmds.size());
        assertEquals("ACKED", cmds.get(0).getStatus(), "设备已回执，指令必须结算为 ACKED");
        assertTrue(cmds.get(0).getPayload().contains("reason=FIRST_CHECKIN"),
                "载荷要说明是首次签到触发，便于设备与排障区分");

        List<AccessLog> logs = accessLogMapper.findByReservationId(r.getId());
        assertEquals(1, logs.size());
        assertEquals(1, logs.get(0).getGranted());
    }

    @Test
    @DisplayName("批准 → 屏幕显示：自动通过后设备会收到快照并绑定当前预约")
    void approvalPushesSnapshotToScreen() {
        // 明天 10:00–11:00，人数 3、信用 100 → 自动通过
        LocalDateTime start = java.time.LocalDate.now().plusDays(3).atTime(10, 0);
        Reservation form = new Reservation();
        form.setRoomId(1L);
        form.setStartTime(start);
        form.setEndTime(start.plusHours(1));
        form.setAttendeeCount(3);

        var result = reservationService.submit(form, student.getId(), "STUDENT");

        assertEquals(com.wyc.demo03.domain.ReservationStatus.APPROVED, result.status());
        assertEquals(result.reservationId(), simulator.displayedReservationId(),
                "批准后屏幕应显示这笔预约（设备端据此做跨预约开锁校验）");
    }

    @Test
    @DisplayName("已签到后再次开门：第二次指令的载荷标记为 REOPEN")
    void reopenThroughMqtt() {
        Reservation r = insertApprovedNow();
        reservationService.checkIn(r.getId(), student.getId(), "4321", "127.0.0.1");
        accessService.openAfterCheckIn(r.getId(), student.getId(), "127.0.0.1");

        AccessService.UnlockResult again = accessService.reopen(r.getId(), student.getId(), "127.0.0.1");

        assertTrue(again.granted());
        List<DeviceCommand> cmds = commands(r.getId());
        assertEquals(2, cmds.size());
        assertTrue(cmds.stream().anyMatch(c -> c.getPayload().contains("reason=REOPEN")));
        assertTrue(cmds.stream().allMatch(c -> "ACKED".equals(c.getStatus())),
                "两条指令都应被设备回执");
    }

    @Test
    @DisplayName("房间没有设备覆盖时，指令不会被回执，签到仍返回 success 但门禁状态为等待")
    void otherRoomHasNoSimulator() {
        // 研讨室B（roomId=2）没有模拟器接管，指令会停在 SENT 并最终过期
        LocalDateTime start = LocalDateTime.now().withSecond(0).withNano(0);
        Reservation r = new Reservation();
        r.setUserId(student.getId());
        r.setRoomId(2L);
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

        String result = reservationService.checkIn(r.getId(), student.getId(), "4321", "127.0.0.1");
        assertEquals("success", result, "签到本身只落库");

        var unlock = accessService.openAfterCheckIn(r.getId(), student.getId(), "127.0.0.1");
        assertEquals("SENT", unlock.commandStatus(), "没有设备回执时必须如实说等待，不能谎报已开锁");
        List<DeviceCommand> cmds = commands(r.getId());
        assertEquals(1, cmds.size());
        assertEquals("SENT", cmds.get(0).getStatus());

        // 但签到本身必须成功
        assertEquals(ReservationStatus.CHECKED_IN.code(),
                reservationMapper.selectById(r.getId()).getReservationStatus());
    }
}
