package com.wyc.demo03.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wyc.demo03.config.MqttProperties;
import com.wyc.demo03.config.SimulatorProperties;
import com.wyc.demo03.mqtt.MqttEnvelope;
import com.wyc.demo03.mqtt.MqttGateway;
import com.wyc.demo03.mqtt.MqttTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 设备模拟器：硬件未到货期间顶替 STM32F103 + SSD1306 + 蜂鸣器 + 电控锁。
 *
 * <p>它不是"假实现"——校验逻辑与真机完全一致，正因为如此才有价值：
 * <ul>
 *   <li>同样订阅 {@code room/{code}/cmd} 与 {@code snapshot}；</li>
 *   <li>同样按 5 步校验：deviceCode → HMAC → expiresAt → msgId 去重 → reservationId 绑定；</li>
 *   <li>同样回 {@code ack}，带 {@code resultCode=OK/ERR_HMAC/ERR_EXPIRED/ERR_DUP/ERR_BIND}；</li>
 *   <li>同样每 30 秒发心跳。</li>
 * </ul>
 *
 * <p>故障注入 {@code app.simulator.fail-rate=0.2} 会随机丢 ack，用来验证
 * "签到成功但开锁失败不回滚"（文档 3.2.3）——这条路径在真机上也一定会遇到。
 *
 * <p>屏幕输出同时写 {@code target/simulator/screen.txt}，答辩时可以直接截图。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.simulator", name = "enabled", havingValue = "true")
public class DeviceSimulator {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** 最近指令去重表容量：QoS1 允许重复投递，正常不会超过几条。 */
    private static final int DEDUP_CAPACITY = 16;

    private final SimulatorProperties properties;
    private final MqttProperties mqttProperties;
    private final MqttGateway gateway;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 已处理过的 msgId（插入顺序），用于识别重放。 */
    private final Set<String> seenMsgIds = new LinkedHashSet<>();

    /** 当前屏幕上显示的预约（来自 snapshot），用于"跨预约指令不得执行"的绑定校验。 */
    private volatile Long displayedReservationId;

    /** 最近一次快照的载荷，供测试断言"码只在签到窗口内上屏"（BUG-002）。 */
    private volatile String lastSnapshotPayload;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        gateway.subscribe(MqttTopics.cmd(properties.getDeviceCode()), mqttProperties.getQos(), this::onCommand);
        gateway.subscribe(MqttTopics.snapshot(properties.getDeviceCode()), mqttProperties.getQos(),
                this::onSnapshot);
        log.info("[SIM] 设备模拟器已启动：device={} failRate={}",
                properties.getDeviceCode(), properties.getFailRate());
    }

    // ====================================================================
    // 屏幕快照
    // ====================================================================
    void onSnapshot(String json) {
        MqttEnvelope envelope = parse(json);
        if (envelope == null || !verifyCommon(envelope, false)) {
            return;
        }
        displayedReservationId = envelope.getReservationId();
        lastSnapshotPayload = envelope.getPayload();
        String screen = "[SCREEN] " + (envelope.getPayload() == null ? "空闲" : envelope.getPayload());
        System.out.println(screen);
        writeScreen(screen);
    }

    // ====================================================================
    // 指令
    // ====================================================================
    void onCommand(String json) {
        MqttEnvelope envelope = parse(json);
        if (envelope == null) {
            return;
        }
        String reject = verifyCommonReject(envelope);
        if (reject != null) {
            log.warn("[SIM] 丢弃指令：msgId={} 原因={}", envelope.getMsgId(), reject);
            publishAck(envelope, reject);
            return;
        }

        // 跨预约绑定：屏幕显示的是 A 的码，就不能执行 B 的开锁
        if (envelope.getReservationId() != null
                && displayedReservationId != null
                && !displayedReservationId.equals(envelope.getReservationId())) {
            log.warn("[SIM] 指令与当前显示预约不一致，丢弃：cmd={} displayed={}",
                    envelope.getReservationId(), displayedReservationId);
            publishAck(envelope, "ERR_BIND");
            return;
        }

        remember(envelope.getMsgId());
        execute(envelope);

        if (shouldDropAck()) {
            log.warn("[SIM] 故障注入：丢弃本次回执 msgId={}（用于验证服务端不谎报开锁）",
                    envelope.getMsgId());
            return;
        }
        publishAck(envelope, "OK");
    }

    private void execute(MqttEnvelope envelope) {
        String reason = com.wyc.demo03.mqtt.MqttMessageRouter.field(envelope.getPayload(), "reason");
        switch (envelope.getType() == null ? "" : envelope.getType()) {
            case "UNLOCK" -> {
                System.out.println("[LOCK] PULSE 3000ms  reason=" + reason);
                writeScreen("[LOCK] PULSE 3000ms");
            }
            case "BUZZ" -> System.out.println("[BUZZ] 1x");
            case "REMIND" -> {
                System.out.println("[REMIND] 预约即将结束");
                writeScreen("[REMIND] 5 分钟后结束");
            }
            case "CLEAR" -> {
                displayedReservationId = null;
                System.out.println("[SCREEN] 已清屏");
                writeScreen("[SCREEN] 空闲");
            }
            default -> log.warn("[SIM] 未知指令类型：{}", envelope.getType());
        }
    }

    // ====================================================================
    // 心跳
    // ====================================================================
    @Scheduled(fixedDelay = 30_000)
    public void heartbeat() {
        MqttEnvelope envelope = baseEnvelope("HEARTBEAT");
        envelope.setPayload("uptime=" + (System.currentTimeMillis() / 1000) + ";rssi=-58");
        publish(MqttTopics.heartbeat(properties.getDeviceCode()), envelope);
    }

    // ====================================================================
    // 校验
    // ====================================================================

    /** 只判断通过与否（snapshot 用）。 */
    private boolean verifyCommon(MqttEnvelope envelope, boolean dedup) {
        return verifyCommonReject(envelope) == null;
    }

    /**
     * 设备端五步校验，返回拒绝码；通过时返回 {@code null}。
     *
     * <p>顺序与计划 6.2 一致，且**先验签再判过期**：不能让攻击者用过期时间探测密钥。
     */
    private String verifyCommonReject(MqttEnvelope envelope) {
        if (!properties.getDeviceCode().equals(envelope.getDeviceCode())) {
            return "ERR_DEVICE";
        }
        if (!envelope.verify(properties.getSecret())) {
            return "ERR_HMAC";
        }
        if (isExpired(envelope.getExpiresAt())) {
            return "ERR_EXPIRED";
        }
        if (envelope.getMsgId() != null && seenMsgIds.contains(envelope.getMsgId())) {
            return "ERR_DUP";
        }
        return null;
    }

    private boolean isExpired(String expiresAt) {
        if (expiresAt == null) {
            // 没有有效期的指令一律拒绝：一次性指令必须能过期，否则等于永久授权
            return true;
        }
        try {
            return !LocalDateTime.now(clock).isBefore(LocalDateTime.parse(expiresAt, ISO));
        } catch (RuntimeException e) {
            return true;
        }
    }

    private void remember(String msgId) {
        if (msgId == null) {
            return;
        }
        seenMsgIds.add(msgId);
        while (seenMsgIds.size() > DEDUP_CAPACITY) {
            seenMsgIds.remove(seenMsgIds.iterator().next());
        }
    }

    private boolean shouldDropAck() {
        return properties.getFailRate() > 0 && Math.random() < properties.getFailRate();
    }

    // ====================================================================
    // 发布
    // ====================================================================
    private void publishAck(MqttEnvelope command, String resultCode) {
        MqttEnvelope ack = baseEnvelope("ACK");
        ack.setMsgId(command.getMsgId());
        ack.setReservationId(command.getReservationId());
        ack.setPayload("resultCode=" + resultCode);
        publish(MqttTopics.ack(properties.getDeviceCode()), ack);
    }

    private MqttEnvelope baseEnvelope(String type) {
        MqttEnvelope envelope = new MqttEnvelope();
        envelope.setV(MqttEnvelope.VERSION);
        envelope.setType(type);
        envelope.setDeviceCode(properties.getDeviceCode());
        envelope.setIssuedAt(LocalDateTime.now(clock).format(ISO));
        // 回执/心跳也带 expiresAt，超过 120 秒视为陈旧，服务端不采信
        envelope.setExpiresAt(LocalDateTime.now(clock).plusSeconds(120).format(ISO));
        return envelope;
    }

    private void publish(String topic, MqttEnvelope envelope) {
        envelope.setHmac(envelope.sign(properties.getSecret()));
        try {
            gateway.publish(topic, objectMapper.writeValueAsString(envelope), mqttProperties.getQos(), false);
        } catch (Exception e) {
            log.error("[SIM] 发布失败：{}", topic, e);
        }
    }

    private MqttEnvelope parse(String json) {
        try {
            return objectMapper.readValue(json, MqttEnvelope.class);
        } catch (Exception e) {
            log.warn("[SIM] 无法解析消息：{}", json, e);
            return null;
        }
    }

    private void writeScreen(String line) {
        try {
            Path dir = Path.of(properties.getScreenDir());
            Files.createDirectories(dir);
            String stamp = "[" + LocalDateTime.now(clock).format(HH_MM) + "] ";
            Files.writeString(dir.resolve("screen.txt"), stamp + line + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("[SIM] 写屏幕文件失败", e);
        }
    }

    // 供测试观察
    public Long displayedReservationId() {
        return displayedReservationId;
    }

    /** 最近一次收到的屏幕快照载荷（{@code k=v;k=v}），未收到过则为 {@code null}。 */
    public String lastSnapshotPayload() {
        return lastSnapshotPayload;
    }

    public int seenMsgCount() {
        return seenMsgIds.size();
    }
}
