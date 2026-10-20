package com.wyc.demo03.rule;

import com.wyc.demo03.domain.RuleContext;
import com.wyc.demo03.domain.RuleOutcome;
import com.wyc.demo03.domain.RuleParams;
import com.wyc.demo03.domain.SlotCheck;
import com.wyc.demo03.domain.SlotSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附加规则判定（含 BUG-013 的连续占用上限）。
 *
 * <p>这里只搭 {@link RuleEngine}，不加载基础规则 —— 目的是把"附加规则怎么组合"单独钉住，
 * 不被 6 条基础规则的拒绝分支干扰。
 */
class RuleEngineTest {

    private final SlotSchema schema = SlotSchema.of(8 * 60, 22 * 60, 15, 1, 16, 7);

    private RuleEngine engine(RuleParams params) {
        return new RuleEngine(List.of(), () -> params, schema);
    }

    private RuleContext ctx(int credit, int units) {
        return new RuleContext("STUDENT", true, SlotCheck.ok(), false,
                3, 6, null, credit, false, 0, units);
    }

    private RuleParams params(int maxContinuousMinutes) {
        return new RuleParams(90, 70, 120, 3, maxContinuousMinutes, 5, 24, 60);
    }

    @Test
    @DisplayName("BUG-013 把上限调到 60 分钟后，120 分钟的申请必须转人工（原本被 240 分钟兜底放过）")
    void continuousLimitIsEnforced() {
        RuleEngine engine = engine(params(60));

        RuleOutcome longApply = engine.evaluate(ctx(95, 8));
        assertEquals(RuleOutcome.Kind.FORCE_MANUAL, longApply.kind());
        assertEquals(RuleEngine.RULE_MAX_CONTINUOUS, longApply.firstRuleCode());

        RuleOutcome shortApply = engine.evaluate(ctx(95, 4));
        assertEquals(RuleOutcome.Kind.AUTO_APPROVE, shortApply.kind());
    }

    @Test
    @DisplayName("中间信用档也要受上限约束，不能被 midMaxDuration 绕过去")
    void continuousLimitBeatsMidBand() {
        // mid 档允许 120 分钟，但连续占用上限被调到 60
        RuleEngine engine = engine(params(60));

        RuleOutcome mid = engine.evaluate(ctx(75, 8));
        assertEquals(RuleOutcome.Kind.FORCE_MANUAL, mid.kind());
        assertEquals(RuleEngine.RULE_MAX_CONTINUOUS, mid.firstRuleCode());
    }

    @Test
    @DisplayName("默认 240 分钟时行为不变：4 小时申请仍可自动通过")
    void defaultLimitKeepsOldBehaviour() {
        RuleEngine engine = engine(params(240));

        assertEquals(RuleOutcome.Kind.AUTO_APPROVE, engine.evaluate(ctx(95, 16)).kind());
    }

    @Test
    @DisplayName("恰好等于上限时允许自动通过（边界取闭区间）")
    void boundaryIsInclusive() {
        RuleEngine engine = engine(params(60));

        RuleOutcome exactly = engine.evaluate(ctx(95, 4));
        assertEquals(RuleOutcome.Kind.AUTO_APPROVE, exactly.kind());
        assertTrue(exactly.message().contains("自动通过"));
    }
}
