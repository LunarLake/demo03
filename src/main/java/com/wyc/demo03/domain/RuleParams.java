package com.wyc.demo03.domain;

/**
 * 审核规则的参数集合（文档 3.2.2 默认值）。
 *
 * <p>P2 阶段由 {@code StaticRuleParamsProvider} 提供默认常量；
 * P3 阶段改为从 {@code t_rule_version} + {@code t_rule_config} 读取，
 * 本记录的形状不变，因此规则实现无需改动。
 */
public record RuleParams(
        /** 信用分达到此值，直接自动通过 */
        int autoApproveCredit,
        /** 中间档下限：介于 [midCreditMin, autoApproveCredit) 之间时只放行短时长 */
        int midCreditMin,
        /** 中间档允许的最长时长（分钟） */
        int midMaxDurationMinutes,
        /** 同一账号每日自动通过上限 */
        int autoApproveDailyLimit,
        /** 单间连续占用上限（分钟） */
        int maxContinuousMinutes,
        /** 达到此人数必须转人工 */
        int manualReviewHeadcount,
        /** 人工审核办结时限（小时） */
        int manualReviewDueHours,
        /** 基础规则：信用分下限，低于此值直接拒绝 */
        int baseRuleMinCredit
) {

    /** 文档 3.2.2 / 表 3-3 的一期默认值。 */
    public static RuleParams defaults() {
        return new RuleParams(90, 70, 120, 3, 240, 5, 24, 60);
    }
}
