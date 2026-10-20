package com.wyc.demo03.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 门禁通道配置（{@code app.door.*}）。
 */
@Data
@ConfigurationProperties(prefix = "app.door")
public class DoorProperties {

    /** log = 仅记录不执行（默认）；mqtt = 通过 Mosquitto 下发。 */
    private String mode = "log";

    /**
     * 一次性指令的有效期（秒）。
     *
     * <p>必须显著小于"人能走完一段路"的时间，但又够设备完成一次指令往返。
     * 30 秒是文档建议值：留足 MQTT 重连重试的余量，又不会让残留指令在很久之后被重放。
     */
    private int ttlSeconds = 30;
}
