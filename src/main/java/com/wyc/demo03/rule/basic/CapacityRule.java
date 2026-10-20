package com.wyc.demo03.rule.basic;

import com.wyc.demo03.domain.RuleContext;
import com.wyc.demo03.domain.RuleOutcome;
import com.wyc.demo03.rule.BasicRule;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * B03 — 参会人数在 1 到房间容量之间。
 */
@Component
@Order(30)
public class CapacityRule implements BasicRule {

    @Override
    public String code() {
        return "B03_CAPACITY";
    }

    @Override
    public String description() {
        return "参会人数 1 至房间容量";
    }

    @Override
    public Optional<RuleOutcome> check(RuleContext ctx) {
        if (ctx.attendeeCount() < 1) {
            return Optional.of(RuleOutcome.reject(code(), "参会人数至少为 1 人"));
        }
        if (ctx.capacity() > 0 && ctx.attendeeCount() > ctx.capacity()) {
            return Optional.of(RuleOutcome.reject(code(),
                    "参会人数超过房间容量（" + ctx.capacity() + " 人）"));
        }
        return Optional.empty();
    }
}
