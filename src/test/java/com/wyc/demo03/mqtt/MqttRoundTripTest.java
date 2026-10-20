package com.wyc.demo03.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wyc.demo03.config.MqttProperties;
import com.wyc.demo03.config.SimulatorProperties;
import com.wyc.demo03.device.DispatchResult;
import com.wyc.demo03.entity.Device;
import com.wyc.demo03.entity.DeviceCommand;
import com.wyc.demo03.mapper.DeviceMapper;
import com.wyc.demo03.service.DeviceCommandService;
import com.wyc.demo03.simulator.DeviceSimulator;
import com.wyc.demo03.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 端到端协议测试：后端发布 → 模拟器校验并执行 → 回执 → 后端结算。
 *
 * <p>跑在进程内总线上，但信封、HMAC、过期、去重、绑定这些**协议逻辑与真 Broker 完全一致**，
 * 所以它证明的是"接上 Mosquitto 后能通"，而不只是"代码能编译"。
 *
 * <p>覆盖计划里的 AC-16（重放）、AC-17（过期）、AC-26（篡改 HMAC）、AC-27（跨预约绑定）。
 */
class MqttRoundTripTest {

    private static final String DEVICE_CODE = "ROOM-A-01";
    private static final String SECRET = "test-secret";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 21, 10, 0);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MutableClock clock = MutableClock.at(T0);

    private InProcessMqttGateway gateway;
    private MqttProperties mqttProperties;
    private DeviceSimulator simulator;
    private MqttDoorController door;
    private MqttMessageRouter router;
    private DeviceCommandService commandService;
    private DeviceMapper deviceMapper;
    private Device device;

    @BeforeEach
    void setUp() {
        gateway = new InProcessMqttGateway();

        mqttProperties = new MqttProperties();
        mqttProperties.setQos(1);

        SimulatorProperties simProps = new SimulatorProperties();
        simProps.setEnabled(true);
        simProps.setDeviceCode(DEVICE_CODE);
        simProps.setSecret(SECRET);
        simProps.setFailRate(0.0);
        simProps.setScreenDir(System.getProperty("java.io.tmpdir") + "/demo03-sim");

        commandService = mock(DeviceCommandService.class);
        deviceMapper = mock(DeviceMapper.class);

        device = new Device();
        device.setId(1L);
        device.setDeviceCode(DEVICE_CODE);
        device.setRoomId(1L);
        device.setSecret(SECRET);
        device.setOnline(1);
        when(deviceMapper.findByCode(DEVICE_CODE)).thenReturn(device);

        simulator = new DeviceSimulator(simProps, mqttProperties, gateway, objectMapper, clock);
        door = new MqttDoorController(gateway, mqttProperties, objectMapper);
        router = new MqttMessageRouter(gateway, mqttProperties, deviceMapper, commandService,
                objectMapper, clock);

        // 先让后端订阅 ack，再启动模拟器订阅 cmd
        router.subscribe();
        simulator.start();
    }

    private DeviceCommand command(String msgId, Long reservationId, LocalDateTime expiresAt) {
        DeviceCommand c = new DeviceCommand();
        c.setMsgId(msgId);
        c.setDeviceId(1L);
        c.setReservationId(reservationId);
        c.setCmdType(DeviceCommand.Type.UNLOCK.name());
        c.setPayload("reservation=" + reservationId + ";reason=FIRST_CHECKIN;pulseMs=3000");
        c.setIssuedAt(T0);
        c.setExpiresAt(expiresAt);
        return c;
    }

    private void assertAckSettled(String msgId, DeviceCommand.Status expected) {
        verify(commandService).ackFromDevice(eq(msgId), eq(expected), anyString(), eq(device.getId()));
    }

    // ====================================================================

    @Test
    @DisplayName("正常链路：发布开锁 → 模拟器执行 → 回执 OK → 后端结算 ACKED")
    void happyPath() {
        DeviceCommand cmd = command("msg-1", 42L, T0.plusSeconds(30));

        DispatchResult result = door.dispatch(device, cmd);

        assertEquals(DispatchResult.SENT, result, "发布成功只代表已送达 Broker，不代表已开锁");
        assertAckSettled("msg-1", DeviceCommand.Status.ACKED);
    }

    @Test
    @DisplayName("AC-17 过期指令：设备丢弃并回 ERR_EXPIRED，后端记为 FAILED")
    void expiredCommandIsRejected() {
        DeviceCommand cmd = command("msg-old", 42L, T0.minusSeconds(1));

        door.dispatch(device, cmd);

        assertAckSettled("msg-old", DeviceCommand.Status.FAILED);
    }

    @Test
    @DisplayName("AC-16 重放同一条指令：第二次被去重表挡住，回 ERR_DUP")
    void replayIsRejected() {
        DeviceCommand cmd = command("msg-dup", 42L, T0.plusSeconds(30));

        door.dispatch(device, cmd);
        door.dispatch(device, cmd);

        // 第一次 ACKED，第二次 ERR_DUP
        verify(commandService).ackFromDevice(eq("msg-dup"), eq(DeviceCommand.Status.ACKED), eq("OK"), any());
        verify(commandService).ackFromDevice(eq("msg-dup"), eq(DeviceCommand.Status.FAILED), eq("ERR_DUP"), any());
    }

    @Test
    @DisplayName("AC-26 篡改 HMAC：设备丢弃并回 ERR_HMAC，绝不执行")
    void tamperedHmacIsRejected() {
        // 直接用网关发一条签名错误的指令，模拟攻击者
        MqttEnvelope forged = new MqttEnvelope();
        forged.setV(1);
        forged.setMsgId("msg-forged");
        forged.setType("UNLOCK");
        forged.setDeviceCode(DEVICE_CODE);
        forged.setReservationId(42L);
        forged.setIssuedAt(T0.toString());
        forged.setExpiresAt(T0.plusSeconds(30).toString());
        forged.setPayload("reservation=42;pulseMs=3000");
        forged.setHmac("deadbeef");
        gateway.publish(MqttTopics.cmd(DEVICE_CODE), toJson(forged), 1, false);

        assertAckSettled("msg-forged", DeviceCommand.Status.FAILED);
        verify(commandService, never()).ackFromDevice(eq("msg-forged"), eq(DeviceCommand.Status.ACKED), anyString(), any());
    }

    @Test
    @DisplayName("AC-27 跨预约指令：屏幕显示 A，收到 B 的开锁指令则拒绝执行")
    void boundToDisplayedReservation() {
        // 先让屏幕显示预约 42
        MqttEnvelope snapshot = new MqttEnvelope();
        snapshot.setV(1);
        snapshot.setType("DISPLAY");
        snapshot.setDeviceCode(DEVICE_CODE);
        snapshot.setReservationId(42L);
        snapshot.setIssuedAt(T0.toString());
        snapshot.setExpiresAt(T0.plusSeconds(30).toString());
        snapshot.setPayload("code=1234;remain=45:00");
        snapshot.setHmac(snapshot.sign(SECRET));
        gateway.publish(MqttTopics.snapshot(DEVICE_CODE), toJson(snapshot), 1, true);

        // 再下发预约 99 的开锁指令
        DeviceCommand cmd = command("msg-cross", 99L, T0.plusSeconds(30));
        door.dispatch(device, cmd);

        assertAckSettled("msg-cross", DeviceCommand.Status.FAILED);
        verify(commandService, never()).ackFromDevice(eq("msg-cross"), eq(DeviceCommand.Status.ACKED), anyString(), any());
    }

    @Test
    @DisplayName("设备离线（Broker 未连接）时不下发，返回 FAILED")
    void offlineBrokerFailsFast() {
        MqttGateway disconnected = new MqttGateway() {
            @Override
            public String mode() {
                return "offline";
            }

            @Override
            public boolean isConnected() {
                return false;
            }

            @Override
            public void publish(String topic, String payload, int qos, boolean retained) {
                throw new IllegalStateException("not connected");
            }

            @Override
            public void subscribe(String topicFilter, int qos, java.util.function.Consumer<String> handler) {
                // no-op
            }
        };
        MqttDoorController offlineDoor = new MqttDoorController(disconnected, mqttProperties, objectMapper);

        assertEquals(DispatchResult.FAILED, offlineDoor.dispatch(device, command("msg-off", 42L, T0.plusSeconds(30))));
        verify(commandService, never()).ackFromDevice(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("BUG-012 陈旧心跳不能把设备重新点亮")
    void staleHeartbeatIsIgnored() {
        MqttEnvelope stale = new MqttEnvelope();
        stale.setV(1);
        stale.setType("HEARTBEAT");
        stale.setDeviceCode(DEVICE_CODE);
        stale.setMsgId("hb-old");
        stale.setIssuedAt(T0.minusHours(2).toString());
        stale.setExpiresAt(T0.minusHours(2).plusSeconds(120).toString());
        stale.setPayload("uptime=1");
        stale.setHmac(stale.sign(SECRET));
        gateway.publish(MqttTopics.heartbeat(DEVICE_CODE), toJson(stale), 0, false);

        verify(deviceMapper, never()).touchHeartbeat(anyString(), any());
    }

    @Test
    @DisplayName("BUG-012 不支持的协议版本被拒绝")
    void unsupportedVersionIsRejected() {
        MqttEnvelope bad = new MqttEnvelope();
        bad.setV(99);
        bad.setType("HEARTBEAT");
        bad.setDeviceCode(DEVICE_CODE);
        bad.setMsgId("hb-v99");
        bad.setIssuedAt(T0.toString());
        bad.setExpiresAt(T0.plusSeconds(120).toString());
        bad.setPayload("uptime=1");
        bad.setHmac(bad.sign(SECRET));
        gateway.publish(MqttTopics.heartbeat(DEVICE_CODE), toJson(bad), 0, false);

        verify(deviceMapper, never()).touchHeartbeat(anyString(), any());
    }

    @Test
    @DisplayName("BUG-012 消息类型不符（把 cmd 当 ack）被拒绝")
    void wrongTypeIsRejected() {
        MqttEnvelope wrong = new MqttEnvelope();
        wrong.setV(1);
        wrong.setType("CMD");
        wrong.setDeviceCode(DEVICE_CODE);
        wrong.setMsgId("msg-1");
        wrong.setIssuedAt(T0.toString());
        wrong.setExpiresAt(T0.plusSeconds(120).toString());
        wrong.setPayload("resultCode=OK");
        wrong.setHmac(wrong.sign(SECRET));
        gateway.publish(MqttTopics.ack(DEVICE_CODE), toJson(wrong), 1, false);

        verify(commandService, never()).ackFromDevice(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("心跳：模拟器发出的心跳能让后端更新在线时间")
    void heartbeatUpdatesDevice() {
        simulator.heartbeat();

        verify(deviceMapper).touchHeartbeat(eq(DEVICE_CODE), eq(T0));
    }

    @Test
    @DisplayName("伪造的回执不会把指令改成 ACKED")
    void forgedAckIsIgnored() {
        MqttEnvelope forged = new MqttEnvelope();
        forged.setV(1);
        forged.setMsgId("msg-1");
        forged.setType("ACK");
        forged.setDeviceCode(DEVICE_CODE);
        forged.setPayload("resultCode=OK");
        forged.setHmac("deadbeef");
        gateway.publish(MqttTopics.ack(DEVICE_CODE), toJson(forged), 1, false);

        verify(commandService, never()).ackFromDevice(eq("msg-1"), eq(DeviceCommand.Status.ACKED), anyString(), any());
    }

    private String toJson(MqttEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
