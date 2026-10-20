package com.wyc.demo03.service;

import com.wyc.demo03.domain.RuleParams;
import com.wyc.demo03.entity.RuleVersion;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 附加规则的版本化配置（文档 3.2.2）。
 *
 * <p>发布模型：每改一次参数就产生一个**新版本**，不原地修改。
 * 预约行记录判定时的 {@code rule_version_id}，因此事后调参数不会"篡改历史"，
 * 申诉复核时能把当时的依据原样还原出来。
 */
public interface RuleConfigService {

    /**
     * 参数展示视图。
     *
     * <p>刻意用带 getter 的类而不是 record：Thymeleaf/SpEL 的属性访问走 JavaBean 约定，
     * record 的 {@code label()} 不是 {@code getLabel()}，在模板里取不到值。
     */
    @Getter
    @AllArgsConstructor
    class ParamView {
        private final String key;
        private final String label;
        private final String unit;
        private final int min;
        private final int max;
        private final int defaultValue;
        private final int currentValue;
        private final String description;
    }

    /** 版本摘要。 */
    @Getter
    @AllArgsConstructor
    class VersionView {
        private final Long id;
        private final Integer versionNo;
        private final LocalDateTime effectiveFrom;
        private final String note;
    }

    /** 当前生效版本；空表时返回 null。 */
    RuleVersion currentVersion();

    /** 当前生效参数（含默认值填充）。 */
    RuleParams currentParams();

    /** 配置页展示用：8 个白名单参数及其当前值。 */
    List<ParamView> currentParamsView();

    /** 历史版本，按版本号倒序。 */
    List<VersionView> history();

    /**
     * 发布新版本。
     *
     * @param submitted key → value，只处理白名单内的键，未提交的沿用当前值
     * @param adminId   发布人
     * @param note      变更说明
     * @return 新版本
     * @throws IllegalArgumentException 未知参数、非整数、越界或阈值倒置
     */
    RuleVersion publish(Map<String, String> submitted, Long adminId, String note);
}
