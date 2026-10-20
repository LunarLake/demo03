package com.wyc.demo03.rule.basic;

import com.wyc.demo03.domain.RuleContext;
import com.wyc.demo03.domain.RuleOutcome;
import com.wyc.demo03.rule.BasicRule;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * B06 — 无未处理的严重违规记录。
 *
 * <p>"未处理"的实现口径：存在 {@code reason_code = VIOLATION} 的信用流水，
 * 且没有一条申诉把该笔流水判为成立（{@code status = REVOKED}）。
 * 已通过申诉撤销的违规不再拦人 —— 否则申诉赢了也约不上，等于申诉没用。
 */
@Component
@Order(60)
public class NoActiveViolationRule implements BasicRule {

    @Override
    public String code() {
        return "B06_VIOLATION";
    }

    @Override
    public String description() {
        return "无未处理的严重违规记录";
    }

    @Override
    public Optional<RuleOutcome> check(RuleContext ctx) {
        if (ctx.hasActiveViolation()) {
            return Optional.of(RuleOutcome.reject(code(),
                    "存在未处理的违规记录，暂不可预约；请先完成申诉复核"));
        }
        return Optional.empty();
    }
}
