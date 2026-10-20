package com.wyc.demo03.domain;

import java.util.List;

/**
 * 规则判定结果。
 *
 * <p>规则引擎（基础规则 + 附加规则）每命中一条就产出一个 {@code RuleOutcome}，
 * 由引擎按"从严优先"顺序聚合出最终结论。{@code hitRuleCodes} 会写入
 * {@code t_reservation.hit_rule_codes}，申诉时直接展示依据（文档 3.2.2 要求"判定记录命中的规则编号"）。
 *
 * @param kind         结论类型
 * @param hitRuleCodes 命中的规则编号；PASS 时为空
 * @param message      面向用户/管理员的说明
 */
public record RuleOutcome(Kind kind, List<String> hitRuleCodes, String message) {

    /** 结论类型。顺序即聚合优先级：REJECT > FORCE_MANUAL > AUTO_APPROVE > PASS。 */
    public enum Kind {
        /** 未命中任何限制，继续往下判定。 */
        PASS,
        /** 基础规则不通过，直接拒绝。 */
        REJECT,
        /** 必须转人工（人数阈值、冻结、信用不足但未到拒绝线等）。 */
        FORCE_MANUAL,
        /** 附加规则全部满足，可以自动通过。 */
        AUTO_APPROVE
    }

    public RuleOutcome {
        hitRuleCodes = hitRuleCodes == null ? List.of() : List.copyOf(hitRuleCodes);
    }

    /** 未命中限制。 */
    public static RuleOutcome pass() {
        return new RuleOutcome(Kind.PASS, List.of(), null);
    }

    public static RuleOutcome reject(String ruleCode, String message) {
        return new RuleOutcome(Kind.REJECT, List.of(ruleCode), message);
    }

    public static RuleOutcome forceManual(String ruleCode, String message) {
        return new RuleOutcome(Kind.FORCE_MANUAL, List.of(ruleCode), message);
    }

    public static RuleOutcome autoApprove(String ruleCode, String message) {
        return new RuleOutcome(Kind.AUTO_APPROVE, List.of(ruleCode), message);
    }

    /** 是否已经给出确定结论（PASS 表示"继续判定"）。 */
    public boolean decided() {
        return kind != Kind.PASS;
    }

    public boolean rejected() {
        return kind == Kind.REJECT;
    }

    public boolean forcedManual() {
        return kind == Kind.FORCE_MANUAL;
    }

    public boolean autoApproved() {
        return kind == Kind.AUTO_APPROVE;
    }

    /** 命中规则编号（取第一条，用于日志与页面展示）。 */
    public String firstRuleCode() {
        return hitRuleCodes.isEmpty() ? null : hitRuleCodes.get(0);
    }
}
