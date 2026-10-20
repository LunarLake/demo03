package com.wyc.demo03.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wyc.demo03.config.DoorProperties;
import com.wyc.demo03.config.MqttProperties;
import com.wyc.demo03.device.DeviceNotifier;
import com.wyc.demo03.device.DispatchResult;
import com.wyc.demo03.device.DoorController;
import com.wyc.demo03.domain.ReservationStatus;
import com.wyc.demo03.entity.CheckinCode;
import com.wyc.demo03.entity.Device;
import com.wyc.demo03.entity.DeviceCommand;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.mapper.DeviceMapper;
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
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.door", name = "mode", havingValue = "mqtt")
public class MqttDeviceNotifier implements DeviceNotifier {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final DeviceMapper deviceMapper;
    private final CheckinCodeService checkinCodeService;
    private final DeviceCommandService deviceCommandService;
    private final DoorController doorController;
    private final MqttGateway gateway;
    private final MqttProperties mqttProperties;
    private final DoorProperties doorProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    public void snapshot(Reservation reservation, ReservationStatus status) {
        Device device = resolve(reservation);
        if (device == null) {
            return;
        }
        String payload = reservation == null
                ? "status=FREE"
                : buildPayload(reservation, status);
        MqttEnvelope envelope = baseEnvelope("DISPLAY", device.getDeviceCode());
        envelope.setReservationId(reservation == null ? null : reservation.getId());
        envelope.setPayload(payload);
        publish(MqttTopics.snapshot(device.getDeviceCode()), envelope, device.getSecret(), true);
    }

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
            log.debug("房间 {} 未配置设备，跳过屏幕同步", reservation.getRoomId());
        }
        return device;
    }

    /**
     * 屏幕内容。码只在"待签到"阶段下发 —— 已签到后码没有意义，
     * 继续显示反而给了旁边路过的人一个可用凭证。
     */
    private String buildPayload(Reservation reservation, ReservationStatus status) {
        StringBuilder sb = new StringBuilder();
        sb.append("reservation=").append(reservation.getId());
        sb.append(";status=").append(status == null ? "UNKNOWN" : status.name());

        if (status == ReservationStatus.APPROVED) {
            CheckinCode code = checkinCodeService.findByReservationId(reservation.getId());
            if (code != null && code.isActive()) {
                sb.append(";code=").append(code.getCode());
            }
        }
        sb.append(";remain=").append(remainText(reservation.getEndTime()));
        return sb.toString();
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
        // 快照是状态，不是授权，给一个较短有效期即可；retained 会让它一直留在 Broker
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
