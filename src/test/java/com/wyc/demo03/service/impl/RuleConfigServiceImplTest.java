package com.wyc.demo03.service.impl;

import com.wyc.demo03.domain.RuleParamKey;
import com.wyc.demo03.domain.RuleParams;
import com.wyc.demo03.entity.RuleConfig;
import com.wyc.demo03.entity.RuleVersion;
import com.wyc.demo03.mapper.AuditLogMapper;
import com.wyc.demo03.mapper.RuleConfigMapper;
import com.wyc.demo03.mapper.RuleVersionMapper;
import com.wyc.demo03.rule.RuleParamsProvider;
import com.wyc.demo03.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 规则版本发布。
 *
 * <p>重点不在"能存进去"，而在"不可能存进非法配置"：
 * 未知参数名、越界数值、阈值倒置，三种都必须被挡住 —— 否则一次误操作就能让
 * 全站的自动审核静默失效。
 */
@ExtendWith(MockitoExtension.class)
class RuleConfigServiceImplTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 20, 10, 0);

    @Mock private RuleVersionMapper ruleVersionMapper;
    @Mock private RuleConfigMapper ruleConfigMapper;
    @Mock private RuleParamsProvider paramsProvider;
    @Mock private AuditLogMapper auditLogMapper;

    private final MutableClock clock = MutableClock.at(T0);
    private RuleConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RuleConfigServiceImpl(ruleVersionMapper, ruleConfigMapper, paramsProvider,
                auditLogMapper, clock);
    }

    private void stubCurrentParams() {
        when(paramsProvider.current()).thenReturn(RuleParams.defaults());
    }

    @Test
    @DisplayName("未知参数名直接拒绝：拼错一个键就会让整条规则静默失效")
    void rejectsUnknownKey() {
        stubCurrentParams();
        assertThrows(IllegalArgumentException.class,
                () -> service.publish(Map.of("AUTO_APPROVE_CREDITX", "95"), 1L, "typo"));
        verify(ruleVersionMapper, never()).insert(any(RuleVersion.class));
    }

    @Test
    @DisplayName("越界数值被拒绝并给出允许范围")
    void rejectsOutOfRange() {
        stubCurrentParams();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.publish(Map.of("AUTO_APPROVE_CREDIT", "900"), 1L, "too high"));
        assertTrue(ex.getMessage().contains("超出允许范围"));
        assertTrue(ex.getMessage().contains("0–100"));
    }

    @Test
    @DisplayName("非整数被拒绝")
    void rejectsNonNumeric() {
        stubCurrentParams();
        assertThrows(IllegalArgumentException.class,
                () -> service.publish(Map.of("AUTO_APPROVE_CREDIT", "九十"), 1L, "abc"));
    }

    @Test
    @DisplayName("阈值倒置被拒绝：低于准入线却还要转人工是自相矛盾的配置")
    void rejectsInvertedThresholds() {
        stubCurrentParams();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.publish(Map.of(
                        "BASE_RULE_MIN_CREDIT", "95",
                        "MID_CREDIT_MIN", "50"), 1L, "inverted"));
        assertTrue(ex.getMessage().contains("不能高于"));
    }

    @Test
    @DisplayName("发布成功：版本号自增，并写入**完整**参数快照（8 条）")
    void publishWritesFullSnapshot() {
        stubCurrentParams();
        when(ruleVersionMapper.maxVersionNo()).thenReturn(3);
        when(ruleVersionMapper.insert(any(RuleVersion.class))).thenAnswer(inv -> {
            inv.getArgument(0, RuleVersion.class).setId(99L);
            return 1;
        });

        RuleVersion version = service.publish(Map.of("MANUAL_REVIEW_HEADCOUNT", "3"), 7L, "答辩前调低");

        assertEquals(4, version.getVersionNo());
        assertEquals(99L, version.getId());
        assertEquals(7L, version.getCreatedBy());

        ArgumentCaptor<RuleConfig> captor = ArgumentCaptor.forClass(RuleConfig.class);
        verify(ruleConfigMapper, org.mockito.Mockito.times(RuleParamKey.values().length))
                .insert(captor.capture());

        List<RuleConfig> configs = captor.getAllValues();
        assertEquals(RuleParamKey.values().length, configs.size(), "必须写全量快照，不能只写改动项");
        RuleConfig headcount = configs.stream()
                .filter(c -> c.getParamKey().equals("MANUAL_REVIEW_HEADCOUNT"))
                .findFirst().orElseThrow();
        assertEquals("3", headcount.getParamValue());
        RuleConfig untouched = configs.stream()
                .filter(c -> c.getParamKey().equals("AUTO_APPROVE_CREDIT"))
                .findFirst().orElseThrow();
        assertEquals("90", untouched.getParamValue(), "未提交的参数应沿用当前值");
    }

    @Test
    @DisplayName("空提交也算一次发布：只改说明不改数值，保留完整可追溯链")
    void publishWithNoChangesStillCreatesVersion() {
        stubCurrentParams();
        when(ruleVersionMapper.maxVersionNo()).thenReturn(null);
        when(ruleVersionMapper.insert(any(RuleVersion.class))).thenAnswer(inv -> {
            inv.getArgument(0, RuleVersion.class).setId(1L);
            return 1;
        });

        RuleVersion version = service.publish(Map.of(), 1L, "只是记录一次确认");

        assertEquals(1, version.getVersionNo(), "空表时从 v1 开始");
        assertEquals("只是记录一次确认", version.getNote());
    }

    @Test
    @DisplayName("配置页展示 8 个白名单参数，并带上当前值")
    void currentParamsViewCoversWhitelist() {
        stubCurrentParams();
        var views = service.currentParamsView();

        assertEquals(RuleParamKey.values().length, views.size());
        assertTrue(views.stream().anyMatch(v -> v.getKey().equals("AUTO_APPROVE_CREDIT")
                && v.getCurrentValue() == 90));
        assertTrue(views.stream().allMatch(v -> v.getMin() <= v.getCurrentValue()
                && v.getCurrentValue() <= v.getMax()));
    }
}
