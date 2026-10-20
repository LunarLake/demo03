package com.wyc.demo03.mqtt;

import com.wyc.demo03.config.MqttProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * 客户端开启了自动重连，Broker 恢复后无需重启应用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.mqtt", name = "mode", havingValue = "paho")
public class PahoMqttGateway implements MqttGateway {

    private final MqttProperties properties;

    private MqttClient client;
    private volatile boolean connecting;

    @Override
    public String mode() {
        return "paho";
    }

    @Override
    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    @Override
    public void publish(String topic, String payload, int qos, boolean retained) {
        if (!isConnected()) {
            throw new IllegalStateException("MQTT 未连接，无法发布：" + topic);
        }
        try {
            MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
            message.setQos(qos);
            message.setRetained(retained);
            client.publish(topic, message);
        } catch (MqttException e) {
            throw new IllegalStateException("MQTT 发布失败：" + topic, e);
        }
    }

    @Override
    public void subscribe(String topicFilter, int qos, Consumer<String> handler) {
        if (!isConnected()) {
            log.warn("MQTT 未连接，订阅延迟到连接建立后：{}", topicFilter);
            return;
        }
        try {
            client.subscribe(topicFilter, qos, (topic, message) -> {
                try {
                    handler.accept(new String(message.getPayload(), StandardCharsets.UTF_8));
                } catch (RuntimeException e) {
                    log.error("MQTT 消息处理失败：topic={}", topic, e);
                }
            });
        } catch (MqttException e) {
            log.error("MQTT 订阅失败：{}", topicFilter, e);
        }
    }

    /**
     * 应用就绪后再连：避免 Broker 不可达时拖慢启动、也不影响容器完成装配。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void connectAsync() {
        if (connecting || isConnected()) {
            return;
        }
        connecting = true;
        Thread thread = new Thread(this::doConnect, "mqtt-connect");
        thread.setDaemon(true);
        thread.start();
    }

    private void doConnect() {
        try {
            client = new MqttClient(properties.getBrokerUrl(),
                    properties.getClientId(), new MemoryPersistence());

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
            client.connect(options);
            log.info("MQTT 已连接：{}（clientId={}）", properties.getBrokerUrl(), properties.getClientId());
        } catch (MqttException e) {
            // 不抛：Broker 恢复后由自动重连接管；此期间开门会被明确拒绝为 DEVICE_OFFLINE
            log.error("MQTT 连接失败（门禁将不可用，业务不受影响）：{}", properties.getBrokerUrl(), e);
        } finally {
            connecting = false;
        }
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            try {
                if (client.isConnected()) {
                    client.disconnect();
                }
                client.close();
            } catch (MqttException e) {
                log.warn("关闭 MQTT 客户端时出错", e);
            }
        }
    }
}
