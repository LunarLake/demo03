package com.wyc.demo03.rule;

import com.wyc.demo03.domain.RuleContext;
import com.wyc.demo03.domain.RuleOutcome;

import java.util.Optional;

/**
 * 基础规则：系统内置、只读、每次提交必检（文档 3.2.2）。
 *
 * <p>约定：返回 {@link Optional#empty()} 表示"本规则不限制，继续往下判"；
 * 返回 {@link RuleOutcome#reject} 表示"直接拒绝，不再进入后续判定"。
 *
 * <p>基础规则不提供后台配置入口 —— 要改口径只能改代码，这样"账号有效""单元不重叠"
 * 这类底线不会被误配掉。
 */
public interface BasicRule {

    /** 规则编号，写入 {@code hit_rule_codes}，申诉时作为依据。 */
    String code();

    /** 一句话说明，管理端展示用。 */
    String description();

    /** 判定；无限制时返回 empty。 */
    Optional<RuleOutcome> check(RuleContext ctx);
}
