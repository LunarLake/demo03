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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @Autowired private com.wyc.demo03.device.DeviceNotifier deviceNotifier;

    private User student;

    @BeforeEach
    void setUp() {
        student = userService.lambdaQuery().eq(User::getUsername, "student01").one();
        assertNotNull(student);

        // BUG-012：只有 Broker 连着不算设备可达，必须有心跳把 online 置 1。
        // 真实场景里设备上电就会发心跳，这里显式补一次，让前置状态与现场一致。
        simulator.heartbeat();
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

    // ====================================================================
    // 屏幕绑定（BUG-002）
    //
    // 旧行为是"哪笔预约触发事件就上屏哪笔"，于是批准未来预约会抢走正在使用
    // 房间的那笔的屏幕绑定，当事人再次开门被设备拒为 ERR_BIND。
    // 下面四条把这四类场景钉死。
    // ====================================================================

    @Test
    @DisplayName("批准未来预约不会抢走当前预约的屏幕绑定，A 仍可再次开门")
    void futureApprovalDoesNotStealScreen() {
        Reservation a = insertApprovedNow();
        reservationService.checkIn(a.getId(), student.getId(), "4321", "127.0.0.1");
        assertEquals(a.getId(), simulator.displayedReservationId(), "签到后屏幕应绑定 A");

        // 批准同房间明天上午的 B（自动通过）
        Reservation b = insertFutureApproved();
        assertNotNull(b.getId());

        assertEquals(a.getId(), simulator.displayedReservationId(),
                "批准未来预约不得改写屏幕：A 正在使用房间");

        var again = accessService.reopen(a.getId(), student.getId(), "127.0.0.1");
        assertTrue(again.granted(), "A 仍在使用房间，应当授权");
        assertTrue(again.acknowledged(), "指令所属预约与屏幕绑定一致，不得被 ERR_BIND 拒绝");
    }

    @Test
    @DisplayName("取消未来预约也不会清掉当前预约的屏幕绑定")
    void cancelFutureReservationKeepsScreen() {
        Reservation a = insertApprovedNow();
        reservationService.checkIn(a.getId(), student.getId(), "4321", "127.0.0.1");
        Reservation b = insertFutureApproved();

        assertEquals("success", reservationService.cancel(b.getId(), student.getId()));

        assertEquals(a.getId(), simulator.displayedReservationId(),
                "取消的是未来的 B，屏幕上仍应是正在进行的 A");
    }

    @Test
    @DisplayName("窗口切换后屏幕自动翻到下一笔（上一场结束、下一场开始）")
    void screenSwitchesWhenWindowChanges() {
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        Reservation a = insertApprovedAt(now.minusMinutes(10), 30, "1111");
        Reservation b = insertFutureApproved();
        deviceNotifier.refresh(1L);
        assertEquals(a.getId(), simulator.displayedReservationId(), "B 还没开始，屏幕是 A");

        // 模拟时间推进：A 已结束、B 正在进行（窗口切换不是事件，靠收敛任务兜底）
        a.setEndTime(now.minusMinutes(1));
        reservationMapper.updateById(a);
        b.setStartTime(now.minusMinutes(1));
        b.setEndTime(now.plusMinutes(59));
        reservationMapper.updateById(b);

        deviceNotifier.refresh(1L);

        assertEquals(b.getId(), simulator.displayedReservationId(), "A 结束后屏幕应翻到 B");
    }

    @Test
    @DisplayName("签到码只在当前有效的签到窗口内上屏")
    void codeOnlyShownDuringCheckinWindow() {
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        // 已开始 10 分钟 > 5 分钟窗口：码不该再挂着
        Reservation r = insertApprovedAt(now.minusMinutes(10), 45, "1111");
        deviceNotifier.refresh(1L);

        assertEquals(r.getId(), simulator.displayedReservationId());
        String payload = simulator.lastSnapshotPayload();
        assertNotNull(payload);
        assertFalse(payload.contains("code="), "窗口已过，码不得继续上屏：payload=" + payload);
        assertTrue(payload.contains("endsAt="), "设备要能自己算倒计时");

        // 把开始时刻挪到 1 分钟前 → 落回窗口内
        r.setStartTime(now.minusMinutes(1));
        r.setEndTime(now.plusMinutes(44));
        reservationMapper.updateById(r);
        deviceNotifier.refresh(1L);

        assertTrue(simulator.lastSnapshotPayload().contains("code=1111"),
                "窗口内应能看到码：payload=" + simulator.lastSnapshotPayload());
    }

    /**
     * 经真实审批链路插一笔未来预约（明天上午，对齐 15 分钟且在 08:00–22:00 内）。
     *
     * <p>不能直接插"三小时后"：本地时间晚于 19:00 时那已经超出开放时段，规则会拒绝。
     */
    private Reservation insertFutureApproved() {
        LocalDateTime start = LocalDateTime.now().plusDays(1).toLocalDate().atTime(9, 0);
        Reservation form = new Reservation();
        form.setRoomId(1L);
        form.setStartTime(start);
        form.setEndTime(start.plusHours(1));
        form.setAttendeeCount(3);

        var result = reservationService.submit(form, student.getId(), "STUDENT");
        assertEquals(com.wyc.demo03.domain.ReservationStatus.APPROVED, result.status(),
                "信用 100、人数 3 应自动通过");
        return reservationMapper.selectById(result.reservationId());
    }

    /** 插一笔指定开始时刻、已通过且已发码的预约。 */
    private Reservation insertApprovedAt(LocalDateTime start, int durationMinutes, String code) {
        Reservation r = new Reservation();
        r.setUserId(student.getId());
        r.setRoomId(1L);
        r.setStartTime(start);
        r.setEndTime(start.plusMinutes(durationMinutes));
        r.setAttendeeCount(2);
        r.setReservationStatus(ReservationStatus.APPROVED.code());
        reservationMapper.insert(r);

        CheckinCode cc = new CheckinCode();
        cc.setReservationId(r.getId());
        cc.setCode(code);
        cc.setIssuedAt(LocalDateTime.now());
        checkinCodeMapper.insert(cc);
        return r;
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

        // BUG-012：room 2 的设备从未上报心跳（online=0），Broker 在线也不代表可达，
        // 因此不下发指令，而是明确拒绝为门禁离线
        var unlock = accessService.openAfterCheckIn(r.getId(), student.getId(), "127.0.0.1");
        assertEquals("DEVICE_OFFLINE", unlock.denyReason(), "设备离线时不应假装下发");
        assertTrue(commands(r.getId()).isEmpty(), "离线设备不产生指令");

        // 但签到本身必须成功
        assertEquals(ReservationStatus.CHECKED_IN.code(),
                reservationMapper.selectById(r.getId()).getReservationStatus());
    }
}
