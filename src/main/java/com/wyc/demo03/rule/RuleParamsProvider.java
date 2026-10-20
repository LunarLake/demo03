package com.wyc.demo03.rule;

import com.wyc.demo03.domain.RuleParams;

/**
 * 规则参数来源。
 *
 * <p>P2 用固定默认值；P3 改为读取当前生效的 {@code t_rule_version} + {@code t_rule_config}。
 * 抽出接口是为了让 {@link RuleEngine} 不关心"参数从哪来"，
 * 也让规则测试可以注入极端参数（如信用分阈值 100）验证边界。
 */
public interface RuleParamsProvider {

    /** 当前生效参数。 */
    RuleParams current();

    /** 默认实现：文档 3.2.2 的一期常量。 */
    static RuleParamsProvider defaults() {
        return RuleParams::defaults;
    }

    /** 测试用：直接给定一组参数。 */
    static RuleParamsProvider of(RuleParams params) {
        return () -> params;
    }
}
