package com.wyc.demo03.rule.basic;

import com.wyc.demo03.domain.RuleContext;
import com.wyc.demo03.domain.RuleOutcome;
import com.wyc.demo03.rule.BasicRule;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * B04 — 与同房间有效占用单元无重叠。
 *
 * <p>★ 这里只看**有效占用**（已通过 / 已签到）。待审批的申请不在占用表里，
 * 因此同一时段允许多笔待审批并存 —— 这是 v2 与 v1 最本质的区别，
 * 也是"平时看着能约、提交才被拒"那个老问题的根因修复。
 */
@Component
@Order(40)
public class SlotOccupiedRule implements BasicRule {

    @Override
    public String code() {
        return "B04_OCCUPIED";
    }

    @Override
    public String description() {
        return "与同房间有效占用单元不重叠";
    }

    @Override
    public Optional<RuleOutcome> check(RuleContext ctx) {
        if (ctx.slotOccupied()) {
            return Optional.of(RuleOutcome.reject(code(), "所选时段已被占用，请选择其他时间"));
        }
        return Optional.empty();
    }
}
