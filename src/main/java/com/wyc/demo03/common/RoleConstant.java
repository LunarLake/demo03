package com.wyc.demo03.common;

/**
 * 角色常量：统一管理角色字符串，避免魔法值散落各处。
 */
public final class RoleConstant {

    // 学生：默认注册角色，预约需审批
    public static final String STUDENT = "STUDENT";
    // 教师：会议室管理 + 预约业务特权（自动通过、覆盖学生预约）
    public static final String TEACHER = "TEACHER";
    // 管理员：审批管理 + 控制台总览 + 系统日志
    public static final String ADMIN = "ADMIN";

    private RoleConstant() {
    }

    /**
     * 角色中文文案映射，未知值原样返回（兜底）。
     */
    public static String displayName(String role) {
        return switch (role) {
            case ADMIN -> "管理员";
            case TEACHER -> "教师";
            case STUDENT -> "学生";
            default -> role;
        };
    }
}
