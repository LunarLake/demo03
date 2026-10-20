package com.wyc.demo03.rule.basic;

import com.wyc.demo03.domain.RuleContext;
import com.wyc.demo03.domain.RuleOutcome;
import com.wyc.demo03.domain.RuleParams;
import com.wyc.demo03.rule.BasicRule;
import com.wyc.demo03.rule.RuleParamsProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * B05 — 信用分不得低于基础下限（默认 60）。
 *
 * <p>低于下限是**直接拒绝**，不是转人工。文档 3.2.2 基础规则写的是"信用分不低于 60"，
 * 且"任一条不满足即拒绝并说明理由"；表 3-3 末行又说"低于 60 不再自动通过，改由人工审核"，
 * 两处口径冲突。按从严取拒绝（见 docs/重构改造计划.md 第 10 节 Q3）。
 *
 * <p>若团队决定给人工留口子，把本类从 {@code @Component} 改为不注册，
 * 并在 {@link com.wyc.demo03.rule.RuleEngine} 的附加规则里加一档即可。
 */
@Component
@Order(50)
@RequiredArgsConstructor
public class CreditFloorRule implements BasicRule {

    private final RuleParamsProvider params;

    @Override
    public String code() {
        return "B05_CREDIT";
    }

    @Override
    public String description() {
        return "信用分不低于 60 分";
    }

    @Override
    public Optional<RuleOutcome> check(RuleContext ctx) {
        RuleParams p = params.current();
        if (ctx.creditScore() < p.baseRuleMinCredit()) {
            return Optional.of(RuleOutcome.reject(code(),
                    "信用分 " + ctx.creditScore() + " 低于 " + p.baseRuleMinCredit()
                            + " 分，暂不可预约；如有异议可在 7 日内提交申诉"));
        }
        return Optional.empty();
    }
}
