package com.wyc.demo03.device;

import com.wyc.demo03.domain.ReservationStatus;
import com.wyc.demo03.entity.Reservation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 日志版通知：只记录应当显示什么，不真的发 MQTT。
 *
 * <p>{@code app.door.mode=log}（默认）时生效。它与 {@link LogDoorController} 配对，
 * 让整条业务链路在没有 Broker 的情况下也能完整跑通，同时不假装真的控制了设备。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.door", name = "mode", havingValue = "log", matchIfMissing = true)
public class LogDeviceNotifier implements DeviceNotifier {

    @Override
    public void snapshot(Reservation reservation, ReservationStatus status) {
        if (reservation == null) {
            log.info("[模拟屏幕] 清屏");
            return;
        }
        log.info("[模拟屏幕] reservation={} status={}",
                reservation.getId(), status == null ? "-" : status.label());
    }

    @Override
    public boolean remind(Reservation reservation) {
        log.info("[模拟提醒] reservation={} 将在结束前提示", reservation == null ? null : reservation.getId());
        return false;
    }
}
