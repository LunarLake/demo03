package com.wyc.demo03.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 15 分钟单元规则测试（可行性报告 3.2.1）。
 *
 * <p>覆盖验收用例 AC-01 / AC-02 / AC-03，以及签到截止依赖的边界换算。
 */
class SlotSchemaTest {

    /** 默认口径：08:00–22:00，15 分钟一格，1–16 格，最多提前 7 天。 */
    private final SlotSchema defaultSchema = SlotSchema.of(8 * 60, 22 * 60, 15, 1, 16, 7);

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 9, 0);

    private static LocalDateTime at(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 10, day, hour, minute);
    }

    // ==================== 基础换算 ====================

    @Test
    @DisplayName("每日单元数为 56，起止边界正确")
    void slotsPerDayIs56() {
        assertEquals(56, defaultSchema.slotsPerDay());
        assertEquals("08:00", defaultSchema.openLabel());
        assertEquals("22:00", defaultSchema.closeLabel());
        assertEquals(15, defaultSchema.unitMinutes());
        assertEquals(15, defaultSchema.minMinutes());
        assertEquals(240, defaultSchema.maxMinutes());
    }

    @Test
    @DisplayName("对齐判定：整点对、07 分不对、带秒不对")
    void alignment() {
        assertTrue(defaultSchema.isAligned(at(21, 8, 0)));
        assertTrue(defaultSchema.isAligned(at(21, 8, 15)));
        assertTrue(defaultSchema.isAligned(at(21, 21, 45)));
        assertTrue(defaultSchema.isAligned(at(21, 22, 0)));

        assertFalse(defaultSchema.isAligned(at(21, 8, 7)), "08:07 未对齐（AC-01）");
        assertFalse(defaultSchema.isAligned(at(21, 8, 20)));
        assertFalse(defaultSchema.isAligned(LocalDateTime.of(2026, 10, 21, 8, 0, 30)));
        assertFalse(defaultSchema.isAligned(null));
    }

    @Test
    @DisplayName("序号换算：08:00=0，08:15=1，22:00=56")
    void indexMapping() {
        assertEquals(0, defaultSchema.indexOf(at(21, 8, 0)));
        assertEquals(1, defaultSchema.indexOf(at(21, 8, 15)));
        assertEquals(5, defaultSchema.indexOf(at(21, 9, 15)));
        assertEquals(56, defaultSchema.indexOf(at(21, 22, 0)));

        assertEquals(-1, defaultSchema.indexOf(at(21, 8, 7)), "未对齐应返回 -1");
        assertEquals(-1, defaultSchema.indexOf(at(21, 23, 0)), "越界应返回 -1");
        assertEquals(-1, defaultSchema.indexOf(at(21, 7, 0)), "早于开放时刻应返回 -1");
    }

    @Test
    @DisplayName("单元起止时刻可以往返换算")
    void startEndRoundTrip() {
        LocalDate date = LocalDate.of(2026, 10, 21);
        assertEquals(at(21, 8, 0), defaultSchema.startOf(date, 0));
        assertEquals(at(21, 8, 15), defaultSchema.endOf(date, 0));
        assertEquals(at(21, 22, 0), defaultSchema.endOf(date, 55));

        for (int i = 0; i < defaultSchema.slotsPerDay(); i++) {
            LocalDateTime start = defaultSchema.startOf(date, i);
            assertEquals(i, defaultSchema.indexOf(start), "第 " + i + " 格的起始时刻应换算回同一序号");
        }
    }

    @Test
    @DisplayName("split 展开连续单元，序号连续且以起始格为第一格")
    void splitProducesContiguousUnits() {
        LocalDate date = LocalDate.of(2026, 10, 21);
        List<SlotUnit> units = defaultSchema.split(at(21, 9, 15), at(21, 10, 15));

        assertEquals(4, units.size(), "09:15–10:15 是 4 个单元");
        assertEquals(List.of(
                new SlotUnit(date, 5),
                new SlotUnit(date, 6),
                new SlotUnit(date, 7),
                new SlotUnit(date, 8)
        ), units);
        assertEquals("2026-10-21#5", units.get(0).key());
    }

    @Test
    @DisplayName("allIndexes 覆盖 0..55")
    void allIndexes() {
        List<Integer> all = defaultSchema.allIndexes();
        assertEquals(56, all.size());
        assertEquals(0, all.get(0));
        assertEquals(55, all.get(55));
    }

    // ==================== 校验规则 ====================

    @Test
    @DisplayName("合法预约通过校验")
    void validReservationPasses() {
        SlotCheck check = defaultSchema.check(at(21, 10, 0), at(21, 11, 0), NOW);
        assertTrue(check.valid(), "10:00–11:00 应当合法，实际：" + check.code());
    }

    @Test
    @DisplayName("AC-01：未对齐 15 分钟整点被拒绝")
    void notAlignedRejected() {
        SlotCheck check = defaultSchema.check(at(21, 8, 7), at(21, 9, 0), NOW);
        assertFalse(check.valid());
        assertEquals(SlotCheck.NOT_ALIGNED, check.code());

        SlotCheck endNotAligned = defaultSchema.check(at(21, 8, 0), at(21, 8, 7), NOW);
        assertEquals(SlotCheck.NOT_ALIGNED, endNotAligned.code());
    }

    @Test
    @DisplayName("AC-02：超过 4 小时（16 个单元）被拒绝")
    void tooLongRejected() {
        SlotCheck check = defaultSchema.check(at(21, 8, 0), at(21, 12, 15), NOW);
        assertFalse(check.valid());
        assertEquals(SlotCheck.DURATION_TOO_LONG, check.code(), "17 个单元应被拒绝");

        // 恰好 16 个单元应当通过
        assertTrue(defaultSchema.check(at(21, 8, 0), at(21, 12, 0), NOW).valid(), "16 个单元是上限内");
    }

    @Test
    @DisplayName("最短时长不可达时（最短单元数 > 1）返回 DURATION_TOO_SHORT")
    void tooShortRejected() {
        SlotSchema twoUnitsMin = SlotSchema.of(8 * 60, 22 * 60, 15, 2, 16, 7);
        SlotCheck check = twoUnitsMin.check(at(21, 10, 0), at(21, 10, 15), NOW);
        assertFalse(check.valid());
        assertEquals(SlotCheck.DURATION_TOO_SHORT, check.code());
    }

    @Test
    @DisplayName("AC-03：超过 7 天被拒绝，第 7 天仍可预约")
    void advanceDays() {
        // NOW = 10-20，第 7 天是 10-27
        assertTrue(defaultSchema.check(at(27, 10, 0), at(27, 11, 0), NOW).valid(),
                "第 7 天（10-27）应当可以预约");

        SlotCheck check = defaultSchema.check(at(28, 10, 0), at(28, 11, 0), NOW);
        assertFalse(check.valid());
        assertEquals(SlotCheck.TOO_FAR_AHEAD, check.code(), "第 8 天（10-28）应被拒绝");
    }

    @Test
    @DisplayName("过去时刻被拒绝")
    void pastTimeRejected() {
        SlotCheck check = defaultSchema.check(at(20, 8, 0), at(20, 9, 0), NOW);
        assertFalse(check.valid());
        assertEquals(SlotCheck.PAST_TIME, check.code());
    }

    @Test
    @DisplayName("跨天被拒绝")
    void crossDayRejected() {
        SlotCheck check = defaultSchema.check(at(21, 21, 45), at(22, 0, 15), NOW);
        assertFalse(check.valid());
        assertEquals(SlotCheck.CROSS_DAY, check.code());
    }

    @Test
    @DisplayName("开放时段之外被拒绝")
    void outsideOpenHoursRejected() {
        SlotCheck before = defaultSchema.check(at(21, 7, 45), at(21, 8, 15), NOW);
        assertEquals(SlotCheck.OUT_OF_OPEN_HOURS, before.code());

        SlotCheck after = defaultSchema.check(at(21, 21, 45), at(21, 22, 15), NOW);
        assertEquals(SlotCheck.OUT_OF_OPEN_HOURS, after.code());

        // 恰好顶到关闭时刻是允许的
        assertTrue(defaultSchema.check(at(21, 21, 45), at(21, 22, 0), NOW).valid());
    }

    @Test
    @DisplayName("空值与倒置区间被拒绝")
    void invalidRangeRejected() {
        assertEquals(SlotCheck.INVALID_RANGE, defaultSchema.check(null, at(21, 9, 0), NOW).code());
        assertEquals(SlotCheck.INVALID_RANGE, defaultSchema.check(at(21, 9, 0), null, NOW).code());
        assertEquals(SlotCheck.INVALID_RANGE, defaultSchema.check(at(21, 9, 0), at(21, 9, 0), NOW).code());
        assertEquals(SlotCheck.INVALID_RANGE, defaultSchema.check(at(21, 10, 0), at(21, 9, 0), NOW).code());
    }

    // ==================== 构造校验 ====================

    @Test
    @DisplayName("非法配置在启动时就报错，不留到运行期")
    void invalidConfigurationFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> SlotSchema.of(22 * 60, 8 * 60, 15, 1, 16, 7), "关闭早于开放");
        assertThrows(IllegalArgumentException.class,
                () -> SlotSchema.of(8 * 60, 22 * 60, 0, 1, 16, 7), "单元长度不能为 0");
        assertThrows(IllegalArgumentException.class,
                () -> SlotSchema.of(8 * 60, 22 * 60, 9, 1, 16, 7), "840 分钟不能被 9 整除");
        assertThrows(IllegalArgumentException.class,
                () -> SlotSchema.of(8 * 60, 22 * 60, 15, 0, 16, 7), "最短单元数必须 ≥1");
        assertThrows(IllegalArgumentException.class,
                () -> SlotSchema.of(8 * 60, 22 * 60, 15, 5, 4, 7), "最长不能小于最短");
        assertThrows(IllegalArgumentException.class,
                () -> SlotSchema.of(-1, 22 * 60, 15, 1, 16, 7), "分钟数越界");
        assertThrows(IllegalArgumentException.class,
                () -> SlotSchema.of(8 * 60, 24 * 60, 15, 1, 16, 7), "分钟数越界");
    }
}
