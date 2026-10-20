package com.wyc.demo03.service.impl;

import com.wyc.demo03.domain.RuleParamKey;
import com.wyc.demo03.domain.RuleParams;
import com.wyc.demo03.entity.AuditLog;
import com.wyc.demo03.entity.RuleConfig;
import com.wyc.demo03.entity.RuleVersion;
import com.wyc.demo03.mapper.AuditLogMapper;
import com.wyc.demo03.mapper.RuleConfigMapper;
import com.wyc.demo03.mapper.RuleVersionMapper;
import com.wyc.demo03.rule.RuleParamsProvider;
import com.wyc.demo03.service.RuleConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RuleConfigServiceImpl implements RuleConfigService {

    private final RuleVersionMapper ruleVersionMapper;
    private final RuleConfigMapper ruleConfigMapper;
    private final RuleParamsProvider paramsProvider;
    private final AuditLogMapper auditLogMapper;
    private final Clock clock;

    @Override
    public RuleVersion currentVersion() {
        return ruleVersionMapper.findCurrent(LocalDateTime.now(clock));
    }

    @Override
    public RuleParams currentParams() {
        return paramsProvider.current();
    }

    @Override
    public List<ParamView> currentParamsView() {
        RuleParams params = currentParams();
        Map<String, Integer> current = asMap(params);
        List<ParamView> views = new ArrayList<>(RuleParamKey.values().length);
        for (RuleParamKey key : RuleParamKey.ordered()) {
            views.add(new ParamView(key.name(), key.label(), key.unit(), key.min(), key.max(),
                    key.defaultValue(), current.getOrDefault(key.name(), key.defaultValue()),
                    key.description()));
        }
        return views;
    }

    @Override
    public List<VersionView> history() {
        List<RuleVersion> versions = ruleVersionMapper.selectList(null);
        return versions.stream()
                .sorted((a, b) -> Integer.compare(b.getVersionNo(), a.getVersionNo()))
                .map(v -> new VersionView(v.getId(), v.getVersionNo(), v.getEffectiveFrom(), v.getNote()))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RuleVersion publish(Map<String, String> submitted, Long adminId, String note) {
        RuleParams current = currentParams();
        Map<String, Integer> merged = asMap(current);

        // 1) 只处理白名单内的键；未知 key 直接拒绝（防止拼错键导致静默失效）
        if (submitted != null) {
            for (Map.Entry<String, String> entry : submitted.entrySet()) {
                RuleParamKey key = RuleParamKey.ofKey(entry.getKey())
                        .orElseThrow(() -> new IllegalArgumentException("未知的规则参数：" + entry.getKey()));
                merged.put(key.name(), parseAndValidate(key, entry.getValue()));
            }
        }

        RuleParams candidate = toParams(merged);
        String crossError = RuleParamKey.checkCreditThresholds(candidate);
        if (crossError != null) {
            throw new IllegalArgumentException(crossError);
        }

        // 2) 新版本号
        Integer max = ruleVersionMapper.maxVersionNo();
        int nextNo = (max == null ? 0 : max) + 1;
        RuleVersion version = new RuleVersion();
        version.setVersionNo(nextNo);
        // 截到秒：DATETIME 无小数位，MySQL 会把 .9 秒四舍五入到下一秒，
        // 导致刚发布的版本在 1 秒内"尚未生效"，读到的还是旧参数
        version.setEffectiveFrom(LocalDateTime.now(clock).withNano(0));
        version.setCreatedBy(adminId);
        version.setNote(note == null || note.isBlank() ? "参数调整" : note.trim());
        ruleVersionMapper.insert(version);

        // 3) 写入完整参数快照（不是增量）—— 这样后续版本解析不必回溯历史
        for (Map.Entry<String, Integer> entry : merged.entrySet()) {
            RuleConfig config = new RuleConfig();
            config.setVersionId(version.getId());
            config.setParamKey(entry.getKey());
            config.setParamValue(String.valueOf(entry.getValue()));
            ruleConfigMapper.insert(config);
        }

        AuditLog audit = new AuditLog();
        audit.setActor(adminId == null ? "SYSTEM" : String.valueOf(adminId));
        audit.setAction(AuditLog.Action.RULE_PUBLISH.name());
        audit.setTargetType("RULE");
        audit.setTargetId(version.getId());
        audit.setDetail("发布规则版本 v" + nextNo + "：" + version.getNote());
        audit.setRuleVersionId(version.getId());
        audit.setCreatedAt(LocalDateTime.now(clock));
        auditLogMapper.insert(audit);

        log.info("规则版本已发布：v{}（{}）", nextNo, version.getNote());
        return version;
    }

    // ==================== 私有 ====================

    private int parseAndValidate(RuleParamKey key, String raw) {
        if (raw == null || raw.isBlank()) {
            return key.defaultValue();
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key.label() + " 必须是整数，当前值：" + raw);
        }
        if (!key.accepts(value)) {
            throw new IllegalArgumentException(
                    key.label() + " 超出允许范围（" + key.rangeText() + "），当前值：" + value);
        }
        return value;
    }

    /** 把 RuleParams 摊平成 key → value，避免在多个地方重复写 8 组 getter。 */
    private static Map<String, Integer> asMap(RuleParams p) {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put(RuleParamKey.AUTO_APPROVE_CREDIT.name(), p.autoApproveCredit());
        map.put(RuleParamKey.MID_CREDIT_MIN.name(), p.midCreditMin());
        map.put(RuleParamKey.MID_MAX_DURATION_MINUTES.name(), p.midMaxDurationMinutes());
        map.put(RuleParamKey.AUTO_APPROVE_DAILY_LIMIT.name(), p.autoApproveDailyLimit());
        map.put(RuleParamKey.MAX_CONTINUOUS_MINUTES.name(), p.maxContinuousMinutes());
        map.put(RuleParamKey.MANUAL_REVIEW_HEADCOUNT.name(), p.manualReviewHeadcount());
        map.put(RuleParamKey.MANUAL_REVIEW_DUE_HOURS.name(), p.manualReviewDueHours());
        map.put(RuleParamKey.BASE_RULE_MIN_CREDIT.name(), p.baseRuleMinCredit());
        return map;
    }

    private static RuleParams toParams(Map<String, Integer> m) {
        return new RuleParams(
                m.getOrDefault(RuleParamKey.AUTO_APPROVE_CREDIT.name(), RuleParamKey.AUTO_APPROVE_CREDIT.defaultValue()),
                m.getOrDefault(RuleParamKey.MID_CREDIT_MIN.name(), RuleParamKey.MID_CREDIT_MIN.defaultValue()),
                m.getOrDefault(RuleParamKey.MID_MAX_DURATION_MINUTES.name(), RuleParamKey.MID_MAX_DURATION_MINUTES.defaultValue()),
                m.getOrDefault(RuleParamKey.AUTO_APPROVE_DAILY_LIMIT.name(), RuleParamKey.AUTO_APPROVE_DAILY_LIMIT.defaultValue()),
                m.getOrDefault(RuleParamKey.MAX_CONTINUOUS_MINUTES.name(), RuleParamKey.MAX_CONTINUOUS_MINUTES.defaultValue()),
                m.getOrDefault(RuleParamKey.MANUAL_REVIEW_HEADCOUNT.name(), RuleParamKey.MANUAL_REVIEW_HEADCOUNT.defaultValue()),
                m.getOrDefault(RuleParamKey.MANUAL_REVIEW_DUE_HOURS.name(), RuleParamKey.MANUAL_REVIEW_DUE_HOURS.defaultValue()),
                m.getOrDefault(RuleParamKey.BASE_RULE_MIN_CREDIT.name(), RuleParamKey.BASE_RULE_MIN_CREDIT.defaultValue()));
    }
}
