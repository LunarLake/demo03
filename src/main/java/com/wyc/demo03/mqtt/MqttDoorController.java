package com.wyc.demo03.mqtt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wyc.demo03.config.MqttProperties;
import com.wyc.demo03.device.DispatchResult;
import com.wyc.demo03.device.DoorController;
import com.wyc.demo03.entity.Device;
import com.wyc.demo03.entity.DeviceCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * MQTT 门禁实现：把一次性指令封装成带 HMAC 的信封，发布到 {@code room/{code}/cmd}。
 *
 * <p>启用条件：{@code app.door.mode=mqtt}。
 *
 * <p><b>投递成功不等于开锁成功</b>：这里最多返回 {@link DispatchResult#SENT}，
 * 真正的 {@code ACKED} 必须由设备回执触发（{@link MqttMessageRouter}）。
 * 把"发布出去了"当成"门开了"，是这类系统最常见也最难查的谎报。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.door", name = "mode", havingValue = "mqtt")
public class MqttDoorController implements DoorController {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final MqttGateway gateway;
    private final MqttProperties mqttProperties;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "mqtt:" + gateway.mode();
    }

    @Override
    public boolean reachable(Device device) {
        // Broker 连着不代表设备在线：设备可能早已掉电，靠心跳看门狗维护 online 标记。
        // 只看 gateway.isConnected() 会把离线设备的指令一直发到 Broker 里（BUG-012）
        return device != null
                && gateway.isConnected()
                && Integer.valueOf(1).equals(device.getOnline());
    }

    @Override
    public DispatchResult dispatch(Device device, DeviceCommand command) {
        if (device == null || !gateway.isConnected()) {
            return DispatchResult.FAILED;
        }
        MqttEnvelope envelope = build(device, command);
        try {
            gateway.publish(MqttTopics.cmd(device.getDeviceCode()),
                    objectMapper.writeValueAsString(envelope),
                    mqttProperties.getQos(), false);
            log.info("开锁指令已发布：device={} msgId={} expiresAt={}",
                    device.getDeviceCode(), envelope.getMsgId(), envelope.getExpiresAt());
            return DispatchResult.SENT;
        } catch (JsonProcessingException | RuntimeException e) {
            log.error("开锁指令发布失败：device={} msgId={}", device.getDeviceCode(), command.getMsgId(), e);
            return DispatchResult.FAILED;
        }
    }

    private MqttEnvelope build(Device device, DeviceCommand command) {
        MqttEnvelope envelope = new MqttEnvelope();
        envelope.setV(MqttEnvelope.VERSION);
        envelope.setMsgId(command.getMsgId());
        envelope.setType(command.getCmdType());
        envelope.setDeviceCode(device.getDeviceCode());
        envelope.setReservationId(command.getReservationId());
        envelope.setIssuedAt(command.getIssuedAt() == null ? null : command.getIssuedAt().format(ISO));
        envelope.setExpiresAt(command.getExpiresAt() == null ? null : command.getExpiresAt().format(ISO));
        envelope.setPayload(command.getPayload());
        envelope.setHmac(envelope.sign(device.getSecret()));
        return envelope;
    }
}
