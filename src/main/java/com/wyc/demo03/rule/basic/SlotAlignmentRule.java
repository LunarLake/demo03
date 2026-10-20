package com.wyc.demo03.rule.basic;

import com.wyc.demo03.domain.RuleContext;
import com.wyc.demo03.domain.RuleOutcome;
import com.wyc.demo03.domain.SlotCheck;
import com.wyc.demo03.rule.BasicRule;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * B02 — 时段必须满足 15 分钟单元规则（对齐 / 时长 / 开放时段 / 提前天数 / 非过去）。
 *
 * <p>判定逻辑全部在 {@link com.wyc.demo03.domain.SlotSchema} 里，这里只负责把结果转成规则结论，
 * 保证"前端拦"和"后端判"用的是同一份代码。
 */
@Component
@Order(20)
public class SlotAlignmentRule implements BasicRule {

    @Override
    public String code() {
        return "B02_SLOT";
    }

    @Override
    public String description() {
        return "起止对齐 15 分钟整点、15 分钟–4 小时、最多提前 7 天";
    }

    @Override
    public Optional<RuleOutcome> check(RuleContext ctx) {
        SlotCheck check = ctx.slotCheck();
        if (check != null && check.invalid()) {
            // 直接透传 SlotCheck 的 code 与文案，避免同一类错误在两处维护两套措辞
            return Optional.of(new RuleOutcome(RuleOutcome.Kind.REJECT,
                    java.util.List.of(code() + ":" + check.code()), check.message()));
        }
        return Optional.empty();
    }
}
