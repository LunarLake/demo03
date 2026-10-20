package com.wyc.demo03.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 门禁设备（{@code t_device}）。
 *
 * <p>{@code deviceCode} 同时是 MQTT 主题中的标识，设备的 ACL 只允许读写自己的前缀。
 * {@code secret} 用于应用层 HMAC 校验 —— 即使 Broker 被误配置成匿名可写，
 * 伪造的指令也会因为签名不对被设备丢弃。
 */
@Data
@TableName("t_device")
public class Device {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 设备编码，形如 ROOM-A-01 */
    private String deviceCode;

    /** 绑定房间 */
    private Long roomId;

    /** HMAC-SHA256 密钥（演示期明文，正式环境需加密存储） */
    private String secret;

    private String firmwareVer;

    private LocalDateTime lastHeartbeatAt;

    /** 0-离线 1-在线 */
    private Integer online;

    /** 是否在线。 */
    public boolean isOnline() {
        return Integer.valueOf(1).equals(online);
    }
}
