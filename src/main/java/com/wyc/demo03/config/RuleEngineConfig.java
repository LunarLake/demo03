package com.wyc.demo03.config;

import com.wyc.demo03.rule.RuleParamsProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 规则引擎装配。
 *
 * <p>P2 阶段参数为文档默认常量；P3 会提供基于 {@code t_rule_config} 的实现，
 * 用 {@code @ConditionalOnMissingBean} 保证两者不会争抢同一个 Bean。
 */
@Configuration
public class RuleEngineConfig {

    @Bean
    @ConditionalOnMissingBean(RuleParamsProvider.class)
    public RuleParamsProvider staticRuleParamsProvider() {
        return RuleParamsProvider.defaults();
    }
}
