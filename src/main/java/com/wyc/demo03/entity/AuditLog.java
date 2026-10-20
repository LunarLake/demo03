package com.wyc.demo03.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务审计日志（{@code t_audit_log}）。
 *
 * <p>与访问日志 {@code t_log} 分开：
 * <ul>
 *   <li>{@code t_log} 记"谁访问了哪个 URL"，用于流量统计，7 天滚动；</li>
 *   <li>{@code t_audit_log} 记"谁把哪笔预约改成了什么，依据哪版规则"，用于责任追溯。</li>
 * </ul>
 * 文档 4.1 T12 要求记录预约变更、规则判定版本、信用流水与指令结果。
 */
@Data
@TableName("t_audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作者用户名，或 SYSTEM */
    private String actor;

    /** 动作，见 {@link Action} */
    private String action;

    /** RESERVATION/APPEAL/DEVICE/RULE */
    private String targetType;

    private Long targetId;

    private String detail;

    private Long ruleVersionId;

    private LocalDateTime createdAt;

    /** 审计动作码。 */
    public enum Action {
        RESERVE,
        APPROVE,
        REJECT,
        CANCEL,
        RELEASE,
        CHECKIN,
        OPEN_DOOR,
        RULE_PUBLISH,
        APPEAL_DECIDE,
        CREDIT_ADJUST
    }
}
