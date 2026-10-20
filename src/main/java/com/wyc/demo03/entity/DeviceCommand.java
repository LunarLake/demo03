package com.wyc.demo03.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 设备指令（{@code t_device_command}）。
 *
 * <p>每条一次性指令（开锁/蜂鸣/清屏）都落库，用于追溯"服务端确实发了什么、设备回了什么"。
 *
 * <p><b>不把指令当保留消息重放</b>：{@code expiresAt} 到期后设备端必须丢弃，
 * 服务端也会把它标记为 EXPIRED。这样"上一笔预约残留的开锁指令"不会在下一笔预约上被执行。
 */
@Data
@TableName("t_device_command")
public class DeviceCommand {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** UUID，设备端据此幂等去重（QoS1 允许重复投递） */
    private String msgId;

    private Long deviceId;

    private Long reservationId;

    /** 指令类型，见 {@link Type} */
    private String cmdType;

    /** 载荷 JSON */
    private String payload;

    /** 投递状态，见 {@link Status} */
    private String status;

    private LocalDateTime issuedAt;

    /** 失效时刻：过期指令设备必须丢弃 */
    private LocalDateTime expiresAt;

    private LocalDateTime ackedAt;

    /** 回执结果码 */
    private String resultCode;

    private Integer retryCount;

    /** 是否已过期。 */
    public boolean isExpired(LocalDateTime now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public enum Type {
        /** 开锁脉冲 */
        UNLOCK,
        /** 更新屏幕快照 */
        DISPLAY,
        /** 清屏 */
        CLEAR,
        /** 结束前提醒 */
        REMIND,
        /** 纯蜂鸣提示 */
        BUZZ
    }

    public enum Status {
        /** 已入库待发 */
        PENDING,
        /** 已投递待回执 */
        SENT,
        /** 设备已回执 */
        ACKED,
        /** 设备回执失败 */
        FAILED,
        /** 超时未回执 */
        EXPIRED
    }
}
