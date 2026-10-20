package com.wyc.demo03.device;

import com.wyc.demo03.config.MqttProperties;
import com.wyc.demo03.mapper.DeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 心跳看门狗（计划 6.3）。
 *
 * <p>每 30 秒扫一次：超过 90 秒没心跳的设备置为离线。
 * <b>只影响展示与开门</b>，不阻止业务状态流转 —— 签到照常成功，
 * 只是立刻提示"门禁离线，请使用机械释放杆"（文档 3.2.3）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeartbeatWatchdog {

    private final DeviceMapper deviceMapper;
    private final MqttProperties mqttProperties;
    private final Clock clock;

    @Scheduled(fixedDelay = 30_000)
    public void sweep() {
        LocalDateTime deadline = LocalDateTime.now(clock)
                .minusSeconds(mqttProperties.getHeartbeatTimeoutSeconds());
        int offlined = deviceMapper.markOffline(deadline);
        if (offlined > 0) {
            log.warn("设备心跳超时，已置离线：{} 台（阈值 {} 秒）",
                    offlined, mqttProperties.getHeartbeatTimeoutSeconds());
        }
    }
}
