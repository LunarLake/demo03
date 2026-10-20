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
import java.time.Duration;
import java.time.format.DateTimeFormatter;
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

    /** 入站消息允许的时钟偏差（秒）。超过即视为陈旧，不再采信。 */
    private static final long FRESH_SECONDS = 90;

    /**
     * 订阅在应用就绪时执行，但<b>不依赖此刻是否已连上 Broker</b>（BUG-003）：
     * 未连接时 {@link MqttGateway#subscribe} 只登记意图，连接/重连成功后自动补发，
     * 因此这里与网关的连接动作谁先谁后都无所谓。
     */
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
        Device device = verifiedDevice(envelope, "ACK");
        if (device == null) {
            return;
        }
        String resultCode = field(envelope.getPayload(), "resultCode");
        if (resultCode == null) {
            resultCode = "UNKNOWN";
        }
        DeviceCommand.Status status = "OK".equals(resultCode)
                ? DeviceCommand.Status.ACKED : DeviceCommand.Status.FAILED;

        // 归属校验：指令必须属于这台设备，否则别的设备知道 msgId 就能代为结算
        boolean updated = deviceCommandService.ackFromDevice(
                envelope.getMsgId(), status, resultCode, device.getId());
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
        Device device = verifiedDevice(envelope, "HEARTBEAT");
        if (device == null) {
            return;
        }
        // 时效校验：重放一条签名合法的旧心跳，不能让设备"起死回生"
        if (!isFresh(envelope.getIssuedAt())) {
            log.warn("心跳已过期，忽略：device={} issuedAt={}", device.getDeviceCode(), envelope.getIssuedAt());
            return;
        }
        deviceMapper.touchHeartbeat(device.getDeviceCode(), LocalDateTime.now(clock).withNano(0));
    }

    /** 设备事件：一期只记录，不自动改业务状态。 */
    void onEvent(String json) {
        MqttEnvelope envelope = parse(json);
        Device device = verifiedDevice(envelope, "EVENT");
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
     * 设备存在 + 协议版本受支持 + 消息类型符合预期 + HMAC 通过，缺一即丢弃。
     *
     * @param expectedType 期望的消息类型；不匹配直接丢弃，避免把 cmd 当 ack 处理
     */
    private Device verifiedDevice(MqttEnvelope envelope, String expectedType) {
        if (envelope == null || envelope.getDeviceCode() == null
                || envelope.getV() == null || envelope.getV() != MqttEnvelope.VERSION) {
            log.warn("协议版本不支持或缺失，丢弃：device={} v={}",
                    envelope == null ? null : envelope.getDeviceCode(),
                    envelope == null ? null : envelope.getV());
            return null;
        }
        if (!expectedType.equalsIgnoreCase(envelope.getType())) {
            log.warn("消息类型不符，丢弃：期望 {} 实际 {}（device={}）",
                    expectedType, envelope.getType(), envelope.getDeviceCode());
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

    /** issuedAt 是否在允许的时钟偏差内（默认 90 秒，与心跳超时一致）。 */
    private boolean isFresh(String issuedAt) {
        if (issuedAt == null) {
            return false;
        }
        try {
            LocalDateTime issued = LocalDateTime.parse(issuedAt, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return Math.abs(Duration.between(issued, LocalDateTime.now(clock)).getSeconds()) <= FRESH_SECONDS;
        } catch (RuntimeException e) {
            return false;
        }
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
