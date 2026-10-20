package com.wyc.demo03.rule;

import com.wyc.demo03.domain.RuleContext;
import com.wyc.demo03.domain.RuleOutcome;
import com.wyc.demo03.domain.RuleParams;
import com.wyc.demo03.domain.SlotSchema;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 三层审核中的前两层：基础规则（内置只读）→ 附加规则（可配参数）。
 * 第三层「人工审核」不在本类，由 {@link com.wyc.demo03.service.ReservationService#approve} 承担。
 *
 * <h3>判定顺序（刻意固定）</h3>
 * <pre>
 * 1. 基础规则逐条检查，任一拒绝即返回 REJECT（不再往下判）
 * 2. 强制转人工条件，按"从严优先"先于自动通过：
 *    a. 人数达到阈值（默认 5 人）        → FORCE_MANUAL
 *    b. 当日自动通过笔数达上限（默认 3） → FORCE_MANUAL
 * 3. 信用分档次：
 *    a. ≥ 90                              → AUTO_APPROVE
 *    b. 70–89 且时长 ≤ 2 小时             → AUTO_APPROVE
 *    c. 70–89 但时长 > 2 小时             → FORCE_MANUAL
 *    d. < 70 且未填用途                   → REJECT（要求补用途）
 *    e. < 70 且已填用途                   → FORCE_MANUAL
 * </pre>
 *
 * <p><b>为什么"从严优先"</b>：文档 3.2.2 同时存在"信用分 90 以上自动通过"与
 * "人数达 5 人一律转人工"。若先判自动通过，一个信用 95 分的 6 人申请会被自动放过，
 * 违背"人数阈值转人工"的硬要求。因此人工条件必须先判。
 *
 * <p>{@code maxContinuousMinutes}（单间连续占用 ≤ 4 小时）未在此处重复校验：
 * 单元规则的上限就是 16 格 = 240 分钟，等价且更严格。
 */
@Component
@RequiredArgsConstructor
public class RuleEngine {

    /** 附加规则编号（写入 hit_rule_codes，便于申诉举证）。 */
    public static final String RULE_HEADCOUNT = "E01_HEADCOUNT";
    public static final String RULE_DAILY_LIMIT = "E02_DAILY_LIMIT";
    public static final String RULE_CREDIT_HIGH = "E03_CREDIT_HIGH";
    public static final String RULE_CREDIT_MID_SHORT = "E04_CREDIT_MID_SHORT";
    public static final String RULE_CREDIT_MID_LONG = "E05_CREDIT_MID_LONG";
    public static final String RULE_PURPOSE_REQUIRED = "E06_PURPOSE_REQUIRED";
    public static final String RULE_CREDIT_LOW = "E07_CREDIT_LOW";

    private final List<BasicRule> basicRules;
    private final RuleParamsProvider params;
    private final SlotSchema slotSchema;

    /**
     * 对一个申请做判定。
     *
     * @return 结论；{@code PASS} 不会出现 —— 本方法一定给出 REJECT / FORCE_MANUAL / AUTO_APPROVE 之一
     */
    public RuleOutcome evaluate(RuleContext ctx) {
        // ---- 第一层：基础规则 ----
        for (BasicRule rule : basicRules) {
            Optional<RuleOutcome> hit = rule.check(ctx);
            if (hit.isPresent()) {
                RuleOutcome outcome = hit.get();
                if (outcome.rejected()) {
                    return outcome;
                }
            }
        }

        RuleParams p = params.current();

        // ---- 第二层：强制转人工（先于自动通过） ----
        if (ctx.attendeeCount() >= p.manualReviewHeadcount()) {
            return RuleOutcome.forceManual(RULE_HEADCOUNT,
                    "参会人数达到 " + p.manualReviewHeadcount() + " 人，需管理员人工审核");
        }
        if (ctx.autoPassToday() >= p.autoApproveDailyLimit()) {
            return RuleOutcome.forceManual(RULE_DAILY_LIMIT,
                    "今日自动通过笔数已达 " + p.autoApproveDailyLimit() + " 笔，需管理员人工审核");
        }

        // ---- 信用分档次 ----
        int credit = ctx.creditScore();
        if (credit >= p.autoApproveCredit()) {
            return RuleOutcome.autoApprove(RULE_CREDIT_HIGH,
                    "信用分 " + credit + " 分，符合自动通过条件");
        }
        if (credit >= p.midCreditMin()) {
            int minutes = ctx.durationMinutes(slotSchema.unitMinutes());
            if (minutes <= p.midMaxDurationMinutes()) {
                return RuleOutcome.autoApprove(RULE_CREDIT_MID_SHORT,
                        "信用分 " + credit + " 分且时长不超过 " + p.midMaxDurationMinutes() + " 分钟，自动通过");
            }
            return RuleOutcome.forceManual(RULE_CREDIT_MID_LONG,
                    "信用分 " + credit + " 分，超过 " + p.midMaxDurationMinutes() + " 分钟的申请需人工审核");
        }
        if (!ctx.hasPurpose()) {
            return RuleOutcome.reject(RULE_PURPOSE_REQUIRED,
                    "信用分低于 " + p.midCreditMin() + " 分，提交申请时必须填写用途说明");
        }
        return RuleOutcome.forceManual(RULE_CREDIT_LOW,
                "信用分 " + credit + " 分，需管理员人工审核");
    }
}
