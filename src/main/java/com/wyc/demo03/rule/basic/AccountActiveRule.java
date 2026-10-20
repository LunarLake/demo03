package com.wyc.demo03.rule.basic;

import com.wyc.demo03.domain.RuleContext;
import com.wyc.demo03.domain.RuleOutcome;
import com.wyc.demo03.rule.BasicRule;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * B01 — 账号有效且角色可预约。
 *
 * <p>"角色与房间权限匹配"在一期没有按房间分权的需求（文档未定义房间级授权），
 * 因此这里只校验角色本身是系统已知角色；房间有效性由调用方在规则之前判定，
 * 以便给出"房间不存在/维护中"这种更精确的提示。
 */
@Component
@Order(10)
public class AccountActiveRule implements BasicRule {

    private static final Set<String> KNOWN_ROLES = Set.of("STUDENT", "TEACHER", "ADMIN");

    @Override
    public String code() {
        return "B01_ACCOUNT";
    }

    @Override
    public String description() {
        return "账号有效且角色可预约";
    }

    @Override
    public Optional<RuleOutcome> check(RuleContext ctx) {
        if (ctx.role() == null || !KNOWN_ROLES.contains(ctx.role())) {
            return Optional.of(RuleOutcome.reject(code(), "账号角色无效，无法发起预约"));
        }
        return Optional.empty();
    }
}
