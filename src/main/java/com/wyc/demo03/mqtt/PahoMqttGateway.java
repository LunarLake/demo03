package com.wyc.demo03.mqtt;

import com.wyc.demo03.config.MqttProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 真实 MQTT 传输（连 Mosquitto）。
 *
 * <p>启用条件：{@code app.mqtt.mode=paho}。
 *
 * <h3>连不上时为什么不阻止启动</h3>
 * 门禁不可用不等于预约系统不可用：学生仍然要能预约、签到、申诉。
 * 连不上就保持"未连接"，开门请求会得到 {@code DEVICE_OFFLINE} 的明确提示
 * （文档 3.2.3：签到仍可成功，提示使用机械释放杆）。
 *
 * <h3>订阅意图与连接恢复（BUG-003）</h3>
 * <ul>
 *   <li><b>订阅先登记、后执行</b>：未连接时不再直接丢弃，而是把
 *       {@code topicFilter → (qos, handler)} 记进 {@link #subscriptions}，
 *       连接成功或自动重连成功后统一恢复。</li>
 *   <li><b>Paho 的自动重连只在"曾经连上过"之后才生效</b>：第一次 {@code connect()}
 *       就失败时它不会重试。因此这里额外做一层应用层初连重试，退避 1→2→4→8→15→30 秒封顶，
 *       失败就一直退避重试（Broker 比应用晚启动的场景必须靠它），应用关闭时停止。</li>
 *   <li><b>{@code cleanSession=true} 后 Broker 不保留订阅</b>，所以
 *       {@link MqttCallbackExtended#connectComplete(boolean, String)} 里必须重新 subscribe，
 *       否则重连后收不到任何心跳与回执 —— 表现就是"看起来连上了，门却永远开不了"。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.mqtt", name = "mode", havingValue = "paho")
public class PahoMqttGateway implements MqttGateway {

    /** 初连退避阶梯（毫秒），最后一项为上限：封顶而不是无限指数增长。 */
    private static final long[] BACKOFF_MS = {1_000, 2_000, 4_000, 8_000, 15_000, 30_000};

    private final MqttProperties properties;

    /** 已登记的订阅意图；连接/重连后据此恢复。 */
    private final Map<String, Subscription> subscriptions = new LinkedHashMap<>();

    private volatile MqttClient client;
    private volatile boolean connecting;
    private volatile boolean closed;
    private volatile Thread connectThread;
    private final AtomicInteger attempts = new AtomicInteger();

    /** 一条订阅意图：主题过滤器 + QoS + 处理器。 */
    private record Subscription(int qos, Consumer<String> handler) {
    }

    @Override
    public String mode() {
        return "paho";
    }

    @Override
    public boolean isConnected() {
        MqttClient c = client;
        return c != null && c.isConnected();
    }

    @Override
    public void publish(String topic, String payload, int qos, boolean retained) {
        MqttClient c = client;
        if (c == null || !c.isConnected()) {
            throw new IllegalStateException("MQTT 未连接，无法发布：" + topic);
        }
        try {
            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            message.setQos(qos);
            message.setRetained(retained);
            c.publish(topic, message);
        } catch (MqttException e) {
            throw new IllegalStateException("MQTT 发布失败：" + topic, e);
        }
    }

    /**
     * 登记订阅。未连接时<b>只登记不丢弃</b>（BUG-003）：连接建立后由
     * {@link #restoreSubscriptions} 补发，应用不必重启。
     */
    @Override
    public void subscribe(String topicFilter, int qos, Consumer<String> handler) {
        synchronized (subscriptions) {
            subscriptions.put(topicFilter, new Subscription(qos, handler));
        }
        MqttClient c = client;
        if (c == null || !c.isConnected()) {
            log.warn("MQTT 未连接，订阅已登记，连接建立后自动恢复：{}", topicFilter);
            return;
        }
        subscribeNow(c, topicFilter, new Subscription(qos, handler));
    }

    // ====================================================================
    // 连接
    // ====================================================================
    /**
     * 应用就绪后再连：避免 Broker 不可达时拖慢启动、也不影响容器完成装配。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void connectAsync() {
        if (closed || connecting || isConnected()) {
            return;
        }
        connecting = true;
        Thread thread = new Thread(this::connectWithRetry, "mqtt-connect");
        thread.setDaemon(true);
        connectThread = thread;
        thread.start();
    }

    /**
     * 连接并按封顶退避重试，直到成功或应用关闭。
     *
     * <p>为什么需要自己的重试：Paho 的 {@code automaticReconnect} 只在连接<b>曾经建立过</b>
     * 之后才接管，首次 {@code connect()} 失败它不会重试 —— "Broker 比应用晚启动"这个
     * 现场最常见的场景会永远连不上，除非重启应用。
     */
    void connectWithRetry() {
        try {
            while (!closed) {
                int attempt = attempts.incrementAndGet();
                MqttClient candidate = null;
                try {
                    candidate = new MqttClient(properties.getBrokerUrl(),
                            properties.getClientId(), new MemoryPersistence());
                    candidate.setCallback(callback());
                    candidate.connect(buildOptions());
                    client = candidate;
                    log.info("MQTT 已连接：{}（clientId={}，第 {} 次尝试）",
                            properties.getBrokerUrl(), properties.getClientId(), attempt);
                    restoreSubscriptions(false);
                    return;
                } catch (MqttException e) {
                    closeQuietly(candidate);
                    if (closed) {
                        return;
                    }
                    long delay = backoffOf(attempt);
                    if (attempt == 1 || attempt % 5 == 0) {
                        log.error("MQTT 连接失败，{} 秒后重试（第 {} 次）：{}",
                                delay / 1000, attempt, properties.getBrokerUrl());
                    } else {
                        log.warn("MQTT 连接失败，{} 秒后重试（第 {} 次）：{}",
                                delay / 1000, attempt, e.getMessage());
                    }
                    sleepQuietly(delay);
                }
            }
        } finally {
            connecting = false;
        }
    }

    private MqttConnectOptions buildOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setAutomaticReconnect(true);
        options.setConnectionTimeout(5);
        options.setKeepAliveInterval(30);
        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            options.setUserName(properties.getUsername());
        }
        if (properties.getPassword() != null && !properties.getPassword().isBlank()) {
            options.setPassword(properties.getPassword().toCharArray());
        }
        return options;
    }

    /** 退避阶梯：超出数组长度后取最后一项（30 秒封顶）。 */
    long backoffOf(int attempt) {
        int index = Math.min(Math.max(attempt, 1), BACKOFF_MS.length) - 1;
        return BACKOFF_MS[index];
    }

    // ====================================================================
    // 订阅恢复
    // ====================================================================
    /**
     * 重连回调：{@code cleanSession=true} 时 Broker 不保留订阅，
     * 不在这里补发就再也收不到心跳与回执。
     */
    private MqttCallbackExtended callback() {
        return new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                log.info("MQTT {}：{}", reconnect ? "自动重连成功" : "连接建立", serverURI);
                restoreSubscriptions(reconnect);
            }

            @Override
            public void connectionLost(Throwable cause) {
                // 自动重连已开启，这里只记录；恢复后的补订阅由 connectComplete 负责
                log.warn("MQTT 连接断开，等待自动重连：{}",
                        cause == null ? "unknown" : cause.getMessage());
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
                // 各主题走 subscribe(topic, qos, listener) 的分发器，这里不需要兜底
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // QoS1 发布完成回调，业务不关心
            }
        };
    }

    /** 按登记意图重新订阅；{@code restoreSubscriptions} 失败只记日志，不影响连接本身。 */
    void restoreSubscriptions(boolean reconnect) {
        List<Map.Entry<String, Subscription>> snapshot;
        synchronized (subscriptions) {
            snapshot = new ArrayList<>(subscriptions.entrySet());
        }
        if (snapshot.isEmpty()) {
            return;
        }
        MqttClient c = client;
        if (c == null || !c.isConnected()) {
            return;
        }
        log.info("MQTT {}：恢复 {} 个订阅", reconnect ? "重连" : "连接", snapshot.size());
        for (Map.Entry<String, Subscription> entry : snapshot) {
            subscribeNow(c, entry.getKey(), entry.getValue());
        }
    }

    private void subscribeNow(MqttClient c, String topicFilter, Subscription subscription) {
        try {
            c.subscribe(topicFilter, subscription.qos(), (topic, message) -> {
                try {
                    subscription.handler().accept(new String(message.getPayload(), StandardCharsets.UTF_8));
                } catch (RuntimeException e) {
                    log.error("MQTT 消息处理失败：topic={}", topic, e);
                }
            });
            log.info("MQTT 已订阅：{}", topicFilter);
        } catch (MqttException e) {
            log.error("MQTT 订阅失败：{}", topicFilter, e);
        }
    }

    /** 已登记的订阅数量（不论当前是否连上），供排障与测试观察。 */
    int subscriptionCount() {
        synchronized (subscriptions) {
            return subscriptions.size();
        }
    }

    // ====================================================================
    // 关闭
    // ====================================================================
    @PreDestroy
    public void close() {
        closed = true;
        Thread thread = connectThread;
        if (thread != null) {
            thread.interrupt();
        }
        closeQuietly(client);
    }

    private void closeQuietly(MqttClient c) {
        if (c == null) {
            return;
        }
        try {
            if (c.isConnected()) {
                c.disconnect();
            }
            c.close();
        } catch (MqttException e) {
            log.debug("关闭 MQTT 客户端时出错", e);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // 应用关闭时被中断：把中断状态还回去，由 while(!closed) 结束循环
            Thread.currentThread().interrupt();
        }
    }
}
