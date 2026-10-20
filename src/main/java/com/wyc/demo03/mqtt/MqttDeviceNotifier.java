package com.wyc.demo03.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wyc.demo03.config.DoorProperties;
import com.wyc.demo03.config.MqttProperties;
import com.wyc.demo03.config.SlotProperties;
import com.wyc.demo03.device.DeviceNotifier;
import com.wyc.demo03.device.DispatchResult;
import com.wyc.demo03.device.DoorController;
import com.wyc.demo03.domain.ReservationStatus;
import com.wyc.demo03.entity.CheckinCode;
import com.wyc.demo03.entity.Device;
import com.wyc.demo03.entity.DeviceCommand;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.mapper.DeviceMapper;
import com.wyc.demo03.mapper.ReservationMapper;
import com.wyc.demo03.service.CheckinCodeService;
import com.wyc.demo03.service.DeviceCommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * MQTT 版设备通知：发布 retained 屏幕快照 + 下发结束提醒。
 *
 * <p>{@code app.door.mode=mqtt} 时生效。
 *
 * <p>快照用 <b>retained</b>：设备掉电重连后 Broker 会立刻补发最后一条快照，
 * 屏幕不会停在旧内容上（计划 6.3 的"重连校验快照"）。指令则**绝不能** retained，
 * 否则重连时会把过期的开锁指令重放一次。
 *
 * <h3>屏幕状态按房间当前时刻重算（BUG-002）</h3>
 * 旧实现是"哪笔预约触发了事件就推哪笔"，于是批准三天后的预约会立刻改写屏幕绑定，
 * 正在使用房间的人再次开门被设备以 {@code ERR_BIND} 拒绝，未来预约的码也提前上屏。
 * 现在只接受 {@code roomId}，用 {@link ReservationMapper#findCurrentForRoom} 取
 * {@code now ∈ [start, end)} 的那笔；没有就是 {@code FREE}。
 *
 * <p>快照每次都无条件重发（而不是"变化才发"）：retained 消息一旦被 Broker 丢失或过期，
 * 靠"内容没变所以不重发"的策略永远补不回来。房间数量是个位数，每分钟一轮的开销可忽略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.door", name = "mode", havingValue = "mqtt")
public class MqttDeviceNotifier implements DeviceNotifier {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final DeviceMapper deviceMapper;
    private final ReservationMapper reservationMapper;
    private final CheckinCodeService checkinCodeService;
    private final DeviceCommandService deviceCommandService;
    private final DoorController doorController;
    private final MqttGateway gateway;
    private final MqttProperties mqttProperties;
    private final DoorProperties doorProperties;
    private final SlotProperties slotProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // ====================================================================
    // 屏幕快照
    // ====================================================================
    @Override
    public void refresh(Long roomId) {
        if (roomId == null) {
            return;
        }
        Device device = deviceMapper.findByRoomId(roomId);
        if (device == null) {
            log.debug("房间 {} 未配置设备，跳过屏幕同步", roomId);
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Reservation current = reservationMapper.findCurrentForRoom(roomId, now);
        String payload = current == null ? "status=FREE" : buildPayload(current, now);

        MqttEnvelope envelope = baseEnvelope("DISPLAY", device.getDeviceCode());
        envelope.setReservationId(current == null ? null : current.getId());
        envelope.setPayload(payload);
        publish(MqttTopics.snapshot(device.getDeviceCode()), envelope, device.getSecret(), true);
    }

    @Override
    public void refreshAll() {
        for (Device device : deviceMapper.findBound()) {
            try {
                refresh(device.getRoomId());
            } catch (RuntimeException e) {
                // 一台设备的屏幕同步失败不能连累其它房间
                log.warn("屏幕刷新失败：device={}", device.getDeviceCode(), e);
            }
        }
    }

    // ====================================================================
    // 结束提醒
    // ====================================================================
    @Override
    public boolean remind(Reservation reservation) {
        Device device = resolve(reservation);
        if (device == null || !gateway.isConnected()) {
            return false;
        }
        DeviceCommand command = deviceCommandService.issue(device.getId(), reservation.getId(),
                DeviceCommand.Type.REMIND,
                "reservation=" + reservation.getId()
                        + ";endsAt=" + reservation.getEndTime().format(ISO),
                doorProperties.getTtlSeconds());
        deviceCommandService.markSent(command.getMsgId());
        DispatchResult result = doorController.dispatch(device, command);
        if (result == DispatchResult.ACKED) {
            deviceCommandService.ack(command.getMsgId(), DeviceCommand.Status.ACKED, "OK");
            return true;
        }
        return result == DispatchResult.SENT;
    }

    // ====================================================================

    private Device resolve(Reservation reservation) {
        if (reservation == null || reservation.getRoomId() == null) {
            return null;
        }
        Device device = deviceMapper.findByRoomId(reservation.getRoomId());
        if (device == null) {
            log.debug("房间 {} 未配置设备，跳过结束提醒", reservation.getRoomId());
        }
        return device;
    }

    /**
     * 屏幕内容。
     *
     * <p><b>码只在当前有效的签到窗口内下发</b>：窗口是 {@code [开始, min(开始+窗口, 结束))}，
     * 与后端签到校验同源（{@code app.slot.checkin-window-minutes}）。
     * 提前上屏等于把凭证贴给任何路过的人，窗口过后继续显示同样没有意义（已签到后靠人脸/刷卡，
     * 不再靠码）。
     *
     * <p>同时下发 {@code startsAt}/{@code endsAt}：设备要能自己算倒计时，
     * 不能指望服务端每分钟替它重算 {@code remain}。
     */
    private String buildPayload(Reservation reservation, LocalDateTime now) {
        ReservationStatus status = reservation.statusOrNull();
        StringBuilder sb = new StringBuilder();
        sb.append("reservation=").append(reservation.getId());
        sb.append(";status=").append(status == null ? "UNKNOWN" : status.name());
        if (reservation.getStartTime() != null) {
            sb.append(";startsAt=").append(reservation.getStartTime().format(ISO));
        }
        if (reservation.getEndTime() != null) {
            sb.append(";endsAt=").append(reservation.getEndTime().format(ISO));
        }
        sb.append(";remain=").append(remainText(reservation.getEndTime()));

        if (status == ReservationStatus.APPROVED && inCheckinWindow(reservation, now)) {
            CheckinCode code = checkinCodeService.findByReservationId(reservation.getId());
            if (code != null && code.isActive()) {
                sb.append(";code=").append(code.getCode());
            }
        }
        return sb.toString();
    }

    /** 签到窗口 {@code [开始, min(开始+窗口, 结束))}：开始包含、截止不包含。 */
    private boolean inCheckinWindow(Reservation r, LocalDateTime now) {
        LocalDateTime start = r.getStartTime();
        if (start == null || now.isBefore(start)) {
            return false;
        }
        LocalDateTime end = start.plusMinutes(slotProperties.getCheckinWindowMinutes());
        if (r.getEndTime() != null && end.isAfter(r.getEndTime())) {
            end = r.getEndTime();
        }
        return now.isBefore(end);
    }

    /** 剩余时间 {@code mm:ss}；已结束则 00:00。 */
    private String remainText(LocalDateTime end) {
        if (end == null) {
            return "00:00";
        }
        long seconds = Duration.between(LocalDateTime.now(clock), end).getSeconds();
        if (seconds <= 0) {
            return "00:00";
        }
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }

    private MqttEnvelope baseEnvelope(String type, String deviceCode) {
        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        MqttEnvelope envelope = new MqttEnvelope();
        envelope.setV(MqttEnvelope.VERSION);
        envelope.setMsgId(java.util.UUID.randomUUID().toString());
        envelope.setType(type);
        envelope.setDeviceCode(deviceCode);
        envelope.setIssuedAt(now.format(ISO));
        // 快照是状态，不是授权，给一个较短有效期即可；每分钟都会重发，retained 上始终是新的
        envelope.setExpiresAt(now.plusSeconds(600).format(ISO));
        return envelope;
    }

    private void publish(String topic, MqttEnvelope envelope, String secret, boolean retained) {
        if (secret == null || secret.isBlank()) {
            log.warn("设备密钥为空，跳过屏幕快照：topic={}", topic);
            return;
        }
        envelope.setHmac(envelope.sign(secret));
        try {
            gateway.publish(topic, objectMapper.writeValueAsString(envelope),
                    mqttProperties.getQos(), retained);
        } catch (Exception e) {
            // 屏幕同步失败不影响业务：门禁能用、预约有效，只是屏幕没更新
            log.warn("屏幕快照发布失败：topic={}", topic, e);
        }
    }
}
