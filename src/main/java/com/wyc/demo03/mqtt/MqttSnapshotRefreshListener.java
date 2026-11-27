package com.wyc.demo03.mqtt;

import com.wyc.demo03.device.DeviceNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * MQTT 连接就绪后立刻补发一轮屏幕快照。
 *
 * <h3>为什么独立成类，而不是写在 {@link com.wyc.demo03.mqtt.MqttDeviceNotifier} 里</h3>
 * {@code MqttDeviceNotifier} 实现了 {@code DeviceNotifier} 接口，Spring 会为它创建
 * <b>JDK 动态代理</b>；而 {@code @EventListener} 方法不在接口上，Spring 处理注解时
 * 会直接抛 "Need to invoke method ... but not found in any interface(s) of the exposed proxy type"，
 * 整个上下文起不来。要么改全局 CGLIB，要么把监听器拿出来——后者影响面最小。
 *
 * <h3>为什么需要这个监听</h3>
 * 后端启动时第一次 {@code refreshAll()} 常常早于 MQTT 连接建立，必然以
 * "MQTT 未连接，无法发布" 失败，下一轮要等 {@code fixedDelay=60s}。
 * 这段时间没有 retained 快照，设备拿不到时间基准，会一直拒绝指令。
 * 断线重连同样要补：Broker 重启后 retained 消息全部丢失。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.door", name = "mode", havingValue = "mqtt")
public class MqttSnapshotRefreshListener {

    private final DeviceNotifier deviceNotifier;

    /**
     * {@code @Async} 是必须的：本方法由 Paho 回调线程触发，
     * 在里面直接查库和发布会把收包线程堵住，连回执都处理不了。
     */
    @Async
    @EventListener
    public void onMqttConnected(MqttConnectedEvent event) {
        log.info("MQTT 就绪（{}，reconnect={}），立即补发屏幕快照",
                event.serverUri(), event.reconnect());
        deviceNotifier.refreshAll();
    }
}
