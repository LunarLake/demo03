package com.wyc.demo03.domain;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 15 分钟单元的时间规则（可行性报告 3.2.1）。
 *
 * <p>纯计算类：不依赖 Spring、不查库、不读时钟。所有"对齐 / 时长 / 开放时段 / 提前天数"
 * 的判断都收敛到这里，Service 与前端 API 共用同一套规则，避免前后端各算一遍算出两个结果。
 *
 * <p>默认口径：每日 08:00–22:00 共 56 个单元，单次 1–16 个单元（15 分钟–4 小时），
 * 最多提前 7 天。数值可由 {@code app.slot.*} 配置覆盖，本类只做校验与换算。
 */
public final class SlotSchema {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final LocalTime openTime;
    private final LocalTime closeTime;
    private final int unitMinutes;
    private final int minUnits;
    private final int maxUnits;
    private final int maxAdvanceDays;
    private final int slotsPerDay;

    private SlotSchema(LocalTime openTime, LocalTime closeTime, int unitMinutes,
                       int minUnits, int maxUnits, int maxAdvanceDays) {
        if (openTime == null || closeTime == null) {
            throw new IllegalArgumentException("开放时段不能为空");
        }
        if (!closeTime.isAfter(openTime)) {
            throw new IllegalArgumentException("关闭时刻必须晚于开放时刻：" + openTime + "–" + closeTime);
        }
        if (unitMinutes <= 0) {
            throw new IllegalArgumentException("单元分钟数必须为正：" + unitMinutes);
        }
        long perDay = Duration.between(openTime, closeTime).toMinutes();
        if (perDay % unitMinutes != 0) {
            throw new IllegalArgumentException(
                    "开放时长 " + perDay + " 分钟不能被单元 " + unitMinutes + " 分钟整除，单元边界会漂移");
        }
        if (minUnits < 1) {
            throw new IllegalArgumentException("最短单元数必须 ≥ 1：" + minUnits);
        }
        if (maxUnits < minUnits) {
            throw new IllegalArgumentException("最长单元数不能小于最短单元数：" + maxUnits + " < " + minUnits);
        }
        if (maxAdvanceDays < 0) {
            throw new IllegalArgumentException("提前预约天数不能为负：" + maxAdvanceDays);
        }
        this.openTime = openTime;
        this.closeTime = closeTime;
        this.unitMinutes = unitMinutes;
        this.minUnits = minUnits;
        this.maxUnits = maxUnits;
        this.maxAdvanceDays = maxAdvanceDays;
        this.slotsPerDay = (int) (perDay / unitMinutes);
    }

    /**
     * 按"自 00:00 起的分钟数"构造（配置绑定用，避免 yml 里写时间格式）。
     *
     * @param openMinuteOfDay  开放时刻，如 8*60 = 480
     * @param closeMinuteOfDay 关闭时刻，如 22*60 = 1320
     */
    public static SlotSchema of(int openMinuteOfDay, int closeMinuteOfDay, int unitMinutes,
                                int minUnits, int maxUnits, int maxAdvanceDays) {
        return new SlotSchema(minuteOfDay(openMinuteOfDay), minuteOfDay(closeMinuteOfDay),
                unitMinutes, minUnits, maxUnits, maxAdvanceDays);
    }

    private static LocalTime minuteOfDay(int minuteOfDay) {
        if (minuteOfDay < 0 || minuteOfDay >= 24 * 60) {
            throw new IllegalArgumentException("分钟数必须在 0..1439 之间：" + minuteOfDay);
        }
        return LocalTime.of(minuteOfDay / 60, minuteOfDay % 60);
    }

    // ==================== 基础属性 ====================

    public LocalTime openTime() {
        return openTime;
    }

    public LocalTime closeTime() {
        return closeTime;
    }

    public int unitMinutes() {
        return unitMinutes;
    }

    public int minUnits() {
        return minUnits;
    }

    public int maxUnits() {
        return maxUnits;
    }

    public int maxAdvanceDays() {
        return maxAdvanceDays;
    }

    /** 每日单元总数，默认 56。 */
    public int slotsPerDay() {
        return slotsPerDay;
    }

    /** 最短时长（分钟）。 */
    public int minMinutes() {
        return minUnits * unitMinutes;
    }

    /** 最长时长（分钟）。 */
    public int maxMinutes() {
        return maxUnits * unitMinutes;
    }

    public String openLabel() {
        return openTime.format(HH_MM);
    }

    public String closeLabel() {
        return closeTime.format(HH_MM);
    }

    // ==================== 单元换算 ====================

    /**
     * 是否对齐到单元边界。
     *
     * <p>要求：秒与纳秒为 0，且相对开放时刻的分钟偏移能被单元长度整除。
     * 这样 08:07、08:00:30、08:20 都会被判为未对齐。
     */
    public boolean isAligned(LocalDateTime time) {
        if (time == null) {
            return false;
        }
        if (time.getSecond() != 0 || time.getNano() != 0) {
            return false;
        }
        long offset = Duration.between(openTime, time.toLocalTime()).toMinutes();
        return offset % unitMinutes == 0;
    }

    /** 时刻对应的单元序号；未对齐或越界返回 -1。 */
    public int indexOf(LocalDateTime time) {
        if (!isAligned(time)) {
            return -1;
        }
        long offset = Duration.between(openTime, time.toLocalTime()).toMinutes();
        int index = (int) (offset / unitMinutes);
        if (index < 0 || index > slotsPerDay) {
            return -1;
        }
        return index;
    }

    /** 单元的开始时刻。 */
    public LocalDateTime startOf(LocalDate date, int index) {
        return LocalDateTime.of(date, openTime).plusMinutes((long) index * unitMinutes);
    }

    /** 单元的结束时刻。 */
    public LocalDateTime endOf(LocalDate date, int index) {
        return startOf(date, index).plusMinutes(unitMinutes);
    }

    /** 起止之间跨越的单元数；调用方应先确保已通过 {@link #check}。 */
    public int unitsBetween(LocalDateTime start, LocalDateTime end) {
        return (int) (Duration.between(start, end).toMinutes() / unitMinutes);
    }

    /** 把一段合法的起止展开为单元列表，顺序即时间顺序。 */
    public List<SlotUnit> split(LocalDateTime start, LocalDateTime end) {
        LocalDate date = start.toLocalDate();
        int from = indexOf(start);
        int count = unitsBetween(start, end);
        List<SlotUnit> units = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            units.add(new SlotUnit(date, from + i));
        }
        return units;
    }

    /** 某一日的全部单元序号。 */
    public List<Integer> allIndexes() {
        List<Integer> indexes = new ArrayList<>(slotsPerDay);
        for (int i = 0; i < slotsPerDay; i++) {
            indexes.add(i);
        }
        return indexes;
    }

    // ==================== 校验 ====================

    /**
     * 校验一段预约是否满足 15 分钟单元规则。
     *
     * <p>判定顺序刻意固定下来，保证同一个错误输入永远返回同一个错误码（便于前端与测试断言）：
     * 空值 → 区间 → 跨天 → 对齐 → 开放时段 → 时长 → 过去 → 提前天数。
     *
     * @param start 预约开始
     * @param end   预约结束
     * @param now   服务端当前时刻（由注入的 {@link java.time.Clock} 提供，便于测试快进）
     */
    public SlotCheck check(LocalDateTime start, LocalDateTime end, LocalDateTime now) {
        if (start == null || end == null) {
            return SlotCheck.fail(SlotCheck.INVALID_RANGE, "请选择开始与结束时间");
        }
        if (!end.isAfter(start)) {
            return SlotCheck.fail(SlotCheck.INVALID_RANGE, "结束时间必须晚于开始时间");
        }
        if (!start.toLocalDate().equals(end.toLocalDate())) {
            return SlotCheck.fail(SlotCheck.CROSS_DAY, "预约不能跨天，请分开提交");
        }
        if (!isAligned(start)) {
            return SlotCheck.fail(SlotCheck.NOT_ALIGNED,
                    "开始时间必须对齐到 " + unitMinutes + " 分钟整点");
        }
        if (!isAligned(end)) {
            return SlotCheck.fail(SlotCheck.NOT_ALIGNED,
                    "结束时间必须对齐到 " + unitMinutes + " 分钟整点");
        }

        LocalTime startTime = start.toLocalTime();
        LocalTime endTime = end.toLocalTime();
        if (startTime.isBefore(openTime) || endTime.isAfter(closeTime)) {
            return SlotCheck.fail(SlotCheck.OUT_OF_OPEN_HOURS,
                    "预约须在开放时段 " + openLabel() + "–" + closeLabel() + " 之内");
        }

        int units = unitsBetween(start, end);
        if (units < minUnits) {
            return SlotCheck.fail(SlotCheck.DURATION_TOO_SHORT,
                    "预约时长不能少于 " + minMinutes() + " 分钟");
        }
        if (units > maxUnits) {
            return SlotCheck.fail(SlotCheck.DURATION_TOO_LONG,
                    "预约时长不能超过 " + maxMinutes() + " 分钟（" + maxUnits + " 个单元）");
        }
        if (units * unitMinutes != Duration.between(start, end).toMinutes()) {
            // 理论上被"结束对齐"已排除，留作兜底：宁可拒绝，也不要生成一个不整的单元数
            return SlotCheck.fail(SlotCheck.UNIT_COUNT_INVALID, "预约时长不是单元时长的整数倍");
        }

        if (start.isBefore(now)) {
            return SlotCheck.fail(SlotCheck.PAST_TIME, "不能预约已经开始或过去的时间");
        }
        // "最多提前 N 天"按日期口径：今天起第 N 天（含）之内都可以约
        if (start.toLocalDate().isAfter(now.toLocalDate().plusDays(maxAdvanceDays))) {
            return SlotCheck.fail(SlotCheck.TOO_FAR_AHEAD,
                    "最多只能提前 " + maxAdvanceDays + " 天预约");
        }
        return SlotCheck.ok();
    }

    @Override
    public String toString() {
        return "SlotSchema{" + openLabel() + "–" + closeLabel()
                + ", unit=" + unitMinutes + "min, slotsPerDay=" + slotsPerDay
                + ", units=" + minUnits + ".." + maxUnits
                + ", advanceDays=" + maxAdvanceDays + '}';
    }
}
