package com.wyc.demo03.config;

import com.wyc.demo03.domain.SlotSchema;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 时段与门禁规则参数（{@code app.slot.*}）。
 *
 * <p>文档要求"现场演示时管理员可临时调整阈值"，因此这些值不写死在代码里。
 * 默认值与可行性报告 3.2.1 / 3.2.3 / 3.2.4 一致。
 */
@Data
@ConfigurationProperties(prefix = "app.slot")
public class SlotProperties {

    /** 每日开放起始小时，默认 8。 */
    private int openHour = 8;

    /** 开放起始分钟，默认 0。 */
    private int openMinute = 0;

    /** 每日关闭小时，默认 22。 */
    private int closeHour = 22;

    /** 关闭分钟，默认 0。 */
    private int closeMinute = 0;

    /** 单元长度（分钟），默认 15。 */
    private int unitMinutes = 15;

    /** 单次最少单元数，默认 1。 */
    private int minUnits = 1;

    /** 单次最多单元数，默认 16（4 小时）。 */
    private int maxUnits = 16;

    /** 最多提前预约天数，默认 7。 */
    private int maxAdvanceDays = 7;

    /** 签到窗口：开始后多少分钟内必须完成首次签到，默认 5。 */
    private int checkinWindowMinutes = 5;

    /** 结束前多少分钟提醒，默认 5。 */
    private int remindBeforeMinutes = 5;

    /** 自 00:00 起的开放分钟数。 */
    public int openMinuteOfDay() {
        return openHour * 60 + openMinute;
    }

    /** 自 00:00 起的关闭分钟数。 */
    public int closeMinuteOfDay() {
        return closeHour * 60 + closeMinute;
    }

    /** 换算为纯计算用的 {@link SlotSchema}。 */
    public SlotSchema toSchema() {
        return SlotSchema.of(openMinuteOfDay(), closeMinuteOfDay(),
                unitMinutes, minUnits, maxUnits, maxAdvanceDays);
    }
}
