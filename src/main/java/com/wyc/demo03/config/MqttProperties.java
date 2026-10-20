package com.wyc.demo03.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MQTT 通道配置（{@code app.mqtt.*}）。
 */
@Data
@ConfigurationProperties(prefix = "app.mqtt")
public class MqttProperties {

    /**
     * 传输实现：{@code inprocess} = 进程内总线（无 Broker，用于演示与测试）；
     * {@code paho} = 连真实 Mosquitto。
     */
    private String mode = "inprocess";

    private String brokerUrl = "tcp://127.0.0.1:1883";

    private String clientId = "demo03-backend";

    private String username = "backend";

    private String password = "";

    /** 指令与回执用 QoS1；心跳用 QoS0。 */
    private int qos = 1;

    /** 设备心跳超时判定（秒），默认 90 = 3 个心跳周期。 */
    private int heartbeatTimeoutSeconds = 90;
}
