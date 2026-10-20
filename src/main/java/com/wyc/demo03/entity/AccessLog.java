package com.wyc.demo03.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 开门操作记录（{@code t_access_log}）。
 *
 * <p>每一次"申请开门"都留一行，**成功与失败都记**。
 * 文档 3.2.3 明确：已签到预约在有效期内可再次认证开门，每次都要生成独立操作记录；
 * 而拒绝（不在时段、非本人、码已撤销）同样需要留痕，否则排查"为什么没开"时无从下手。
 */
@Data
@TableName("t_access_log")
public class AccessLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long reservationId;

    /** 申请人 */
    private Long userId;

    private Long deviceId;

    /** 关联的设备指令 */
    private Long commandId;

    /** 1-已授权下发 0-拒绝 */
    private Integer granted;

    /** 拒绝原因，见 {@link DenyReason} */
    private String denyReason;

    private String ip;

    private LocalDateTime createdAt;

    /** 拒绝原因码。 */
    public enum DenyReason {
        NOT_OWNER,
        STATUS_INVALID,
        OUT_OF_WINDOW,
        NO_CODE,
        DEVICE_OFFLINE
    }
}
