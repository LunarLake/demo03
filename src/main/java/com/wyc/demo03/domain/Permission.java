package com.wyc.demo03.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 权限点。
 *
 * <p>取代 v1 中"按 URL 前缀判断是不是管理员"的做法（{@code AdminInterceptor}）。
 * 文档 3.2.2 / 6.2 要求审核、规则配置、申诉复核按角色分离，且申诉复核必须由
 * **未参与原审核**的人执行 —— 这条无法用"是不是 ADMIN"表达，必须细化到权限点，
 * 再叠加"复核人 ≠ 原审核人"的数据级校验。
 */
public enum Permission {

    /** 提交预约、签到、开门（学生与教师共有）。 */
    RESERVE("预约与签到"),

    /** 审批转人工的申请、强制转人工。 */
    APPROVE("审核"),

    /** 修改并发布附加规则参数。 */
    RULE_CONFIG("规则配置"),

    /** 复核申诉。 */
    APPEAL_REVIEW("申诉复核"),

    /** 设备管理：在线状态、测试指令、回执查询。 */
    DEVICE_ADMIN("设备管理"),

    /** 查看业务审计日志。 */
    AUDIT_VIEW("审计查看"),

    /** 房间增删改。 */
    ROOM_ADMIN("房间管理");

    private final String label;

    Permission(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * 角色的默认权限。
     *
     * <p>教师只保留 {@link #RESERVE}：教师"优先"体现为预约规则（可覆盖未开始的学生预约），
     * 而不是额外的管理权限 —— 否则会出现教师能批准自己申请的自审问题。
     */
    public static Set<Permission> defaultsOf(String role) {
        if (role == null) {
            return EnumSet.noneOf(Permission.class);
        }
        return switch (role) {
            case "ADMIN" -> Collections.unmodifiableSet(EnumSet.allOf(Permission.class));
            case "TEACHER", "STUDENT" -> Collections.unmodifiableSet(EnumSet.of(RESERVE));
            default -> EnumSet.noneOf(Permission.class);
        };
    }

    public static boolean roleHas(String role, Permission permission) {
        return permission != null && defaultsOf(role).contains(permission);
    }
}
