package com.wyc.demo03.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 附加规则参数白名单（文档 3.2.2）。
 *
 * <p>参数化而不是脚本化：后台只能改这 8 个数值，不能写表达式。
 * 未知 key 拒绝保存 —— 否则规则引擎会因为一个拼错的键而静默失效。
 *
 * <p>每个参数自带取值范围，发布时逐项校验，避免出现"信用分阈值填了 900"这种
 * 让所有申请都转人工的配置事故。
 */
public enum RuleParamKey {

    AUTO_APPROVE_CREDIT("信用分自动通过线", 0, 100, "分", 90,
            "达到此分值的申请可直接自动通过"),

    MID_CREDIT_MIN("中间档信用分下限", 0, 100, "分", 70,
            "介于本值与自动通过线之间时，只有短时长申请可自动通过"),

    MID_MAX_DURATION_MINUTES("中间档最长时长", 15, 240, "分钟", 120,
            "中间档信用分可自动通过的最长时长"),

    AUTO_APPROVE_DAILY_LIMIT("每日自动通过上限", 1, 20, "笔", 3,
            "同一账号每天最多自动通过的笔数，超出转人工"),

    MAX_CONTINUOUS_MINUTES("单间连续占用上限", 15, 240, "分钟", 240,
            "单笔预约的最长时长；与单元规则的上限一致"),

    MANUAL_REVIEW_HEADCOUNT("强制人工人数阈值", 1, 50, "人", 5,
            "参会人数达到此值必须人工审核"),

    MANUAL_REVIEW_DUE_HOURS("人工审核办结时限", 1, 72, "小时", 24,
            "管理员须在此时间内办结，超时预警"),

    BASE_RULE_MIN_CREDIT("信用分准入下限", 0, 100, "分", 60,
            "低于此分值基础规则直接拒绝");

    private final String label;
    private final int min;
    private final int max;
    private final String unit;
    private final int defaultValue;
    private final String description;

    RuleParamKey(String label, int min, int max, String unit, int defaultValue, String description) {
        this.label = label;
        this.min = min;
        this.max = max;
        this.unit = unit;
        this.defaultValue = defaultValue;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public int min() {
        return min;
    }

    public int max() {
        return max;
    }

    public String unit() {
        return unit;
    }

    public int defaultValue() {
        return defaultValue;
    }

    public String description() {
        return description;
    }

    /** 关闭区间校验。 */
    public boolean accepts(int value) {
        return value >= min && value <= max;
    }

    public String rangeText() {
        return min + "–" + max + " " + unit;
    }

    public static Optional<RuleParamKey> ofKey(String key) {
        return Arrays.stream(values()).filter(k -> k.name().equals(key)).findFirst();
    }

    public static List<RuleParamKey> ordered() {
        return List.of(values());
    }

    /**
     * 跨字段校验：三个信用分阈值必须单调不增（准入下限 ≤ 中间档 ≤ 自动通过线）。
     *
     * <p>写反了会得到"低于准入线也要转人工"这种自相矛盾的规则，发布前必须挡住。
     *
     * @return 错误说明；合法时返回 null
     */
    public static String checkCreditThresholds(RuleParams params) {
        if (params.baseRuleMinCredit() > params.midCreditMin()) {
            return "信用分准入下限（" + params.baseRuleMinCredit()
                    + "）不能高于中间档下限（" + params.midCreditMin() + "）";
        }
        if (params.midCreditMin() > params.autoApproveCredit()) {
            return "中间档下限（" + params.midCreditMin()
                    + "）不能高于自动通过线（" + params.autoApproveCredit() + "）";
        }
        return null;
    }
}
