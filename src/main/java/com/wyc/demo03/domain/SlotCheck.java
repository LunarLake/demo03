package com.wyc.demo03.domain;

/**
 * 时段校验结果。
 *
 * <p>用结果对象而不是抛异常：时段不合法是**常规业务分支**（用户点错格子、手动改 URL），
 * 不是程序错误，控制器需要把 {@link #code()} 映射成页面提示，不想依赖异常做流程控制。
 *
 * @param valid   是否通过
 * @param code    失败码；通过时为 {@code null}
 * @param message 面向用户的中文说明；通过时为 {@code null}
 */
public record SlotCheck(boolean valid, String code, String message) {

    /** 起止为空或结束不晚于开始。 */
    public static final String INVALID_RANGE = "invalid_range";

    /** 起止没有对齐到 15 分钟整点（08:07 这种）。 */
    public static final String NOT_ALIGNED = "not_aligned";

    /** 落在一日开放时段 08:00–22:00 之外。 */
    public static final String OUT_OF_OPEN_HOURS = "out_of_open_hours";

    /** 起止跨自然日。 */
    public static final String CROSS_DAY = "cross_day";

    /** 不足最短时长（1 个单元 = 15 分钟）。 */
    public static final String DURATION_TOO_SHORT = "duration_too_short";

    /** 超过最长时长（16 个单元 = 4 小时）。 */
    public static final String DURATION_TOO_LONG = "duration_too_long";

    /** 开始时刻已经过去。 */
    public static final String PAST_TIME = "past_time";

    /** 超出可提前预约的天数（默认 7 天）。 */
    public static final String TOO_FAR_AHEAD = "too_far_ahead";

    /** 起止不在同一天之外的兜底：单元数计算结果异常。 */
    public static final String UNIT_COUNT_INVALID = "unit_count_invalid";

    private static final SlotCheck OK = new SlotCheck(true, null, null);

    public static SlotCheck ok() {
        return OK;
    }

    public static SlotCheck fail(String code, String message) {
        return new SlotCheck(false, code, message);
    }

    /** 反向判断，便于在 if 里读起来顺一点。 */
    public boolean invalid() {
        return !valid;
    }
}
