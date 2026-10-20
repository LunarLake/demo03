package com.wyc.demo03.device;

import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.mapper.ReservationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 日志版通知：只记录应当显示什么，不真的发 MQTT。
 *
 * <p>{@code app.door.mode=log}（默认）时生效。它与 {@link LogDoorController} 配对，
 * 让整条业务链路在没有 Broker 的情况下也能完整跑通，同时不假装真的控制了设备。
 *
 * <p>与 MQTT 版一样按房间当前时刻重算（BUG-002），否则这条"假链路"反而会掩盖
 * 真机才会暴露的屏幕绑定问题 —— 模拟实现与真机口径不同，等于没模拟。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.door", name = "mode", havingValue = "log", matchIfMissing = true)
public class LogDeviceNotifier implements DeviceNotifier {

    private final ReservationMapper reservationMapper;
    private final Clock clock;

    @Override
    public void refresh(Long roomId) {
        if (roomId == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Reservation current = reservationMapper.findCurrentForRoom(roomId, now);
        if (current == null) {
            log.info("[模拟屏幕] room={} 空闲", roomId);
            return;
        }
        var status = current.statusOrNull();
        log.info("[模拟屏幕] room={} reservation={} status={}",
                roomId, current.getId(), status == null ? "-" : status.label());
    }

    @Override
    public void refreshAll() {
        // 没有真实设备可刷，日志版只在 refresh(roomId) 里体现；这里保持静默避免刷屏
        log.debug("[模拟屏幕] 按当前时刻刷新全部房间");
    }

    @Override
    public boolean remind(Reservation reservation) {
        log.info("[模拟提醒] reservation={} 将在结束前提示", reservation == null ? null : reservation.getId());
        return false;
    }
}
