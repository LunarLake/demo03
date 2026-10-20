package com.wyc.demo03.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wyc.demo03.config.MqttProperties;
import com.wyc.demo03.entity.Device;
import com.wyc.demo03.entity.DeviceCommand;
import com.wyc.demo03.mapper.DeviceMapper;
import com.wyc.demo03.service.DeviceCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 后端侧的消息入口：订阅设备回执与心跳。
 *
 * <p><b>回执必须先验 HMAC</b>：MQTT 的 ACL 只管到主题级别，Broker 一旦配置失误或账号泄露，
 * 任何人都能往 {@code room/+/ack} 发消息。若不加验签，伪造一条 "OK" 就能让服务端把
 * 从未执行的开锁指令标记为成功 —— 这是最危险的一类谎报。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.door", name = "mode", havingValue = "mqtt")
public class MqttMessageRouter {

    private final MqttGateway gateway;
    private final MqttProperties mqttProperties;
    private final DeviceMapper deviceMapper;
    private final DeviceCommandService deviceCommandService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @EventListener(ApplicationReadyEvent.class)
    public void subscribe() {
        gateway.subscribe(MqttTopics.SUBSCRIBE_ACK, mqttProperties.getQos(), this::onAck);
        gateway.subscribe(MqttTopics.SUBSCRIBE_HEARTBEAT, 0, this::onHeartbeat);
        gateway.subscribe(MqttTopics.SUBSCRIBE_EVENT, mqttProperties.getQos(), this::onEvent);
        log.info("已订阅设备回执/心跳/事件主题（gateway={}）", gateway.mode());
    }

    /** 指令回执。 */
    void onAck(String json) {
        MqttEnvelope envelope = parse(json);
        Device device = verifiedDevice(envelope);
        if (device == null) {
            return;
        }
        String resultCode = field(envelope.getPayload(), "resultCode");
        if (resultCode == null) {
            resultCode = "UNKNOWN";
        }
        DeviceCommand.Status status = "OK".equals(resultCode)
                ? DeviceCommand.Status.ACKED : DeviceCommand.Status.FAILED;

        boolean updated = deviceCommandService.ack(envelope.getMsgId(), status, resultCode);
        if (updated) {
            log.info("收到回执：device={} msgId={} result={}",
                    device.getDeviceCode(), envelope.getMsgId(), resultCode);
        } else {
            // 指令已过期或已结算，迟到回执不改变状态（幂等）
            log.info("忽略迟到/重复回执：device={} msgId={} result={}",
                    device.getDeviceCode(), envelope.getMsgId(), resultCode);
        }
    }

    /** 心跳：只更新在线时间，不做业务判断。 */
    void onHeartbeat(String json) {
        MqttEnvelope envelope = parse(json);
        Device device = verifiedDevice(envelope);
        if (device == null) {
            return;
        }
        deviceMapper.touchHeartbeat(device.getDeviceCode(), LocalDateTime.now(clock).withNano(0));
    }

    /** 设备事件：一期只记录，不自动改业务状态。 */
    void onEvent(String json) {
        MqttEnvelope envelope = parse(json);
        Device device = verifiedDevice(envelope);
        if (device == null) {
            return;
        }
        log.warn("设备事件：device={} type={} payload={}",
                device.getDeviceCode(), envelope.getType(), envelope.getPayload());
    }

    // ====================================================================

    private MqttEnvelope parse(String json) {
        try {
            return objectMapper.readValue(json, MqttEnvelope.class);
        } catch (Exception e) {
            log.warn("无法解析 MQTT 消息：{}", json, e);
            return null;
        }
    }

    /**
     * 设备存在 + deviceCode 与会话一致 + HMAC 通过，三者缺一即丢弃。
     */
    private Device verifiedDevice(MqttEnvelope envelope) {
        if (envelope == null || envelope.getDeviceCode() == null) {
            return null;
        }
        Device device = deviceMapper.findByCode(envelope.getDeviceCode());
        if (device == null) {
            log.warn("未知设备的 MQTT 消息，已丢弃：{}", envelope.getDeviceCode());
            return null;
        }
        if (!envelope.verify(device.getSecret())) {
            log.warn("MQTT 消息验签失败，已丢弃：device={} msgId={}",
                    envelope.getDeviceCode(), envelope.getMsgId());
            return null;
        }
        return device;
    }

    /** 从扁平载荷里取一个字段（{@code k=v;k=v}）。 */
    public static String field(String payload, String key) {
        if (payload == null) {
            return null;
        }
        for (String part : payload.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equals(key)) {
                return part.substring(eq + 1).trim();
            }
        }
        return null;
    }
}
