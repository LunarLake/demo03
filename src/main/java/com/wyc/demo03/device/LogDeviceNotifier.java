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
 * <p>与 MQTT 版一样按房间当前时刻重算（BUG-002），并且**共用同一份
 * {@link ScreenPayloadBuilder}**：模拟屏打印的内容与真机屏幕逐字一致，
 * 包括签到码只在签到窗口内出现这一点。
 *
 * <p>因此无设备时签到码只出现在<b>服务端日志</b>里（运维/演示者可见），
 * 不会出现在任何网页上 —— 网页能看到码，"到场才能签到"就失效了。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.door", name = "mode", havingValue = "log", matchIfMissing = true)
public class LogDeviceNotifier implements DeviceNotifier {

    private final ReservationMapper reservationMapper;
    private final ScreenPayloadBuilder screenPayloadBuilder;
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
        log.info("[模拟屏幕] room={} {}", roomId, screenPayloadBuilder.build(current, now));
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
