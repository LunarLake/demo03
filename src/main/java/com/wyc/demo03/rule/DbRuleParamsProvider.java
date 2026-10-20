package com.wyc.demo03.rule;

import com.wyc.demo03.domain.RuleParamKey;
import com.wyc.demo03.domain.RuleParams;
import com.wyc.demo03.entity.RuleConfig;
import com.wyc.demo03.entity.RuleVersion;
import com.wyc.demo03.mapper.RuleConfigMapper;
import com.wyc.demo03.mapper.RuleVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从数据库读取当前生效的规则参数（P3 取代 P2 的静态默认值）。
 *
 * <p>取 {@code effective_from <= now} 中版本号最大的一版。参数缺失或非法时**回落到默认值**，
 * 而不是让整个提交链路抛异常 —— 规则配置出错不应该导致用户约不上房间。
 * 每次回落都会打一条 warn，便于在日志里发现配置被改坏。
 *
 * <p>不做缓存：一次提交最多查 2 次（一次给 RuleEngine 判档，一次给基础规则 B05），
 * 相比"缓存失效逻辑写错导致读到旧阈值"，这点查询开销不值得省。
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class DbRuleParamsProvider implements RuleParamsProvider {

    private final RuleVersionMapper ruleVersionMapper;
    private final RuleConfigMapper ruleConfigMapper;
    private final Clock clock;

    @Override
    public RuleParams current() {
        RuleParams defaults = RuleParams.defaults();
        RuleVersion version = ruleVersionMapper.findCurrent(LocalDateTime.now(clock));
        if (version == null) {
            return defaults;
        }
        List<RuleConfig> configs = ruleConfigMapper.findByVersionId(version.getId());
        Map<String, String> values = new HashMap<>();
        for (RuleConfig c : configs) {
            values.put(c.getParamKey(), c.getParamValue());
        }
        return new RuleParams(
                intOf(values, RuleParamKey.AUTO_APPROVE_CREDIT, defaults.autoApproveCredit()),
                intOf(values, RuleParamKey.MID_CREDIT_MIN, defaults.midCreditMin()),
                intOf(values, RuleParamKey.MID_MAX_DURATION_MINUTES, defaults.midMaxDurationMinutes()),
                intOf(values, RuleParamKey.AUTO_APPROVE_DAILY_LIMIT, defaults.autoApproveDailyLimit()),
                intOf(values, RuleParamKey.MAX_CONTINUOUS_MINUTES, defaults.maxContinuousMinutes()),
                intOf(values, RuleParamKey.MANUAL_REVIEW_HEADCOUNT, defaults.manualReviewHeadcount()),
                intOf(values, RuleParamKey.MANUAL_REVIEW_DUE_HOURS, defaults.manualReviewDueHours()),
                intOf(values, RuleParamKey.BASE_RULE_MIN_CREDIT, defaults.baseRuleMinCredit()));
    }

    private int intOf(Map<String, String> values, RuleParamKey key, int fallback) {
        String raw = values.get(key.name());
        if (raw == null) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (!key.accepts(parsed)) {
                log.warn("规则参数 {} 越界：{}（允许 {}），回落默认值 {}",
                        key.name(), parsed, key.rangeText(), fallback);
                return fallback;
            }
            return parsed;
        } catch (NumberFormatException e) {
            log.warn("规则参数 {} 不是整数：{}，回落默认值 {}", key.name(), raw, fallback);
            return fallback;
        }
    }
}
