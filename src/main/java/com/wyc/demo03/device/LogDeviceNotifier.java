package com.wyc.demo03.device;

import com.wyc.demo03.entity.MeetingRoom;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.mapper.MeetingRoomMapper;
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
    private final MeetingRoomMapper meetingRoomMapper;
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

    /**
     * 每分钟一轮，等价于"真机屏幕按当前时刻重画"。
     *
     * <p><b>这里必须真的逐房间算一遍</b>：签到码只在
     * {@code [开始, 开始+5min)} 内上屏，而"进入签到窗口"不对应任何业务事件
     * （审批一定在开始前、签到一定在开始后），所以没有这一轮，无设备时码永远不会出现。
     *
     * <p>空闲房间不打印，避免每分钟刷一屏 {@code 空闲}。
     */
    @Override
    public void refreshAll() {
        for (MeetingRoom room : meetingRoomMapper.selectList(null)) {
            LocalDateTime now = LocalDateTime.now(clock);
            Reservation current = reservationMapper.findCurrentForRoom(room.getId(), now);
            if (current == null) {
                continue;
            }
            log.info("[模拟屏幕] room={} {}", room.getId(), screenPayloadBuilder.build(current, now));
        }
    }

    @Override
    public boolean remind(Reservation reservation) {
        log.info("[模拟提醒] reservation={} 将在结束前提示", reservation == null ? null : reservation.getId());
        return false;
    }
}
