package com.wyc.demo03.domain;

/**
 * 审核判定路径。写入 {@code t_reservation.decision_path}，用于申诉时还原"这笔预约当初是怎么进来的"。
 *
 * <p>与 {@link ReservationStatus} 的区别：状态是"现在在哪"，判定路径是"怎么到的这里"。
 * 两者不能互相替代 —— 例如「已通过」既可能来自规则自动通过，也可能来自人工批准。
 */
public enum DecisionPath {

    /** 附加规则全部满足，系统直接通过。 */
    AUTO_PASS("规则自动通过"),

    /** 基础规则不通过，系统直接拒绝（不进入人工）。 */
    REJECTED_BASIC("基础规则拒绝"),

    /** 转人工，等待管理员处理。 */
    MANUAL_PENDING("转人工待审"),

    /** 人工批准。 */
    MANUAL_APPROVED("人工批准"),

    /** 人工拒绝。 */
    MANUAL_REJECTED("人工拒绝"),

    /** 教师预约：单元无冲突时自动通过。 */
    TEACHER_AUTO("教师自动通过"),

    /** 申诉成立后按剩余单元重建的预约。 */
    APPEAL_REBUILD("申诉后重建"),

    /**
     * v1 迁移过来的历史数据。v1 没有规则引擎，无法还原当时的判定依据，
     * 因此不做语义臆测，统一标记为 LEGACY。
     */
    LEGACY("历史数据");

    private final String label;

    DecisionPath(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
