package com.wyc.demo03.mqtt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 进程内总线：没有 Broker 也能跑通"发布 → 订阅 → 回执"全链路。
 *
 * <p>用途有两个：
 * <ul>
 *   <li>答辩现场不想额外起 Mosquitto 时，{@code app.mqtt.mode=inprocess} 即可演示；</li>
 *   <li>集成测试里用它把后端与 {@link com.wyc.demo03.simulator.DeviceSimulator}
 *       接在一起，验证信封、HMAC、去重、回执——这些协议逻辑与真实 Broker 完全一致。</li>
 * </ul>
 *
 * <p>语义上刻意贴近 MQTT：支持 {@code +} 与 {@code #} 通配、QoS 参数被忽略（进程内不需要），
 * retained 也忽略（没有新订阅者补发的场景）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.mqtt", name = "mode", havingValue = "inprocess", matchIfMissing = true)
public class InProcessMqttGateway implements MqttGateway {

    private final Map<String, List<Consumer<String>>> subscriptions = new ConcurrentHashMap<>();

    @Override
    public String mode() {
        return "inprocess";
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public void publish(String topic, String payload, int qos, boolean retained) {
        for (Map.Entry<String, List<Consumer<String>>> entry : subscriptions.entrySet()) {
            if (!matches(entry.getKey(), topic)) {
                continue;
            }
            for (Consumer<String> handler : entry.getValue()) {
                try {
                    handler.accept(payload);
                } catch (RuntimeException e) {
                    // 一个订阅者出错不能影响其它订阅者，也不能把异常抛回发布方
                    log.error("进程内订阅者处理失败：filter={} topic={}", entry.getKey(), topic, e);
                }
            }
        }
    }

    @Override
    public void subscribe(String topicFilter, int qos, Consumer<String> handler) {
        subscriptions.computeIfAbsent(topicFilter, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /**
     * MQTT 主题匹配：{@code +} 匹配单层，{@code #} 匹配剩余所有层（只能出现在末尾）。
     */
    static boolean matches(String filter, String topic) {
        String[] f = filter.split("/");
        String[] t = topic.split("/");
        int i = 0;
        for (; i < f.length; i++) {
            if ("#".equals(f[i])) {
                return true;
            }
            if (i >= t.length) {
                return false;
            }
            if ("+".equals(f[i])) {
                continue;
            }
            if (!f[i].equals(t[i])) {
                return false;
            }
        }
        return i == t.length;
    }
}
