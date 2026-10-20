package com.wyc.demo03.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机测试。
 *
 * <p>迁移表在测试里**独立重写一遍**，不复用 {@link ReservationStatus#canTransitTo} 的内部数据，
 * 否则只是"自己证明自己"。这里写的是可行性报告表 3-2 的业务意图。
 */
class ReservationStatusTest {

    /** 期望的迁移表：当前态 → 允许的后继。刻意与实现分开维护。 */
    private static final Map<ReservationStatus, Set<ReservationStatus>> EXPECTED = expected();

    private static Map<ReservationStatus, Set<ReservationStatus>> expected() {
        Map<ReservationStatus, Set<ReservationStatus>> m = new EnumMap<>(ReservationStatus.class);
        // 待审批：可被规则/人工通过、拒绝，可被用户或教师取消，也可因到了开始时刻仍未处理而释放
        m.put(ReservationStatus.PENDING, EnumSet.of(
                ReservationStatus.APPROVED, ReservationStatus.REJECTED,
                ReservationStatus.CANCELED, ReservationStatus.TIMEOUT_RELEASED));
        // 已通过：可签到、可因未签到释放、可取消、到期结束
        m.put(ReservationStatus.APPROVED, EnumSet.of(
                ReservationStatus.CHECKED_IN, ReservationStatus.TIMEOUT_RELEASED,
                ReservationStatus.CANCELED, ReservationStatus.FINISHED));
        // 已签到：只能到期结束（已在现场，不允许取消，也不允许被覆盖）
        m.put(ReservationStatus.CHECKED_IN, EnumSet.of(ReservationStatus.FINISHED));
        // 四个终止态：不接受任何迁移
        m.put(ReservationStatus.REJECTED, EnumSet.noneOf(ReservationStatus.class));
        m.put(ReservationStatus.CANCELED, EnumSet.noneOf(ReservationStatus.class));
        m.put(ReservationStatus.TIMEOUT_RELEASED, EnumSet.noneOf(ReservationStatus.class));
        m.put(ReservationStatus.FINISHED, EnumSet.noneOf(ReservationStatus.class));
        return m;
    }

    @Test
    @DisplayName("7×7 迁移矩阵逐格比对")
    void transitionMatrixMatchesSpec() {
        assertEquals(7, ReservationStatus.values().length, "v2 必须是七态");
        for (ReservationStatus from : ReservationStatus.values()) {
            for (ReservationStatus to : ReservationStatus.values()) {
                boolean expectedAllowed = EXPECTED.get(from).contains(to);
                assertEquals(expectedAllowed, from.canTransitTo(to),
                        "迁移判定不符：" + from + " -> " + to);
            }
        }
    }

    @Test
    @DisplayName("状态码与枚举一一对应，废弃码 4 必须报错")
    void codesAreMapped() {
        assertEquals(0, ReservationStatus.PENDING.code());
        assertEquals(1, ReservationStatus.APPROVED.code());
        assertEquals(2, ReservationStatus.REJECTED.code());
        assertEquals(3, ReservationStatus.CANCELED.code());
        assertEquals(5, ReservationStatus.TIMEOUT_RELEASED.code());
        assertEquals(6, ReservationStatus.CHECKED_IN.code());
        assertEquals(7, ReservationStatus.FINISHED.code());

        for (ReservationStatus s : ReservationStatus.values()) {
            assertEquals(s, ReservationStatus.of(s.code()));
        }
    }

    @Test
    @DisplayName("读到废弃码 4 必须抛异常并给出可执行的修复提示")
    void retiredCodeIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ReservationStatus.of(ReservationStatus.RETIRED_USER_CANCEL_CODE));
        assertTrue(ex.getMessage().contains("已废弃"), "错误信息必须说明 4 已废弃");
        assertTrue(ex.getMessage().contains("migrate-v1-to-v2"), "错误信息必须指向迁移脚本");

        // 宽松版本不抛异常，返回 null，供只读展示路径使用
        assertNull(ReservationStatus.ofOrNull(4));
        assertNull(ReservationStatus.ofOrNull(99));
        assertNull(ReservationStatus.ofOrNull(null));
        assertNotNull(ReservationStatus.ofOrNull(6));
    }

    @Test
    @DisplayName("未知状态码必须报错")
    void unknownCodeThrows() {
        assertThrows(IllegalArgumentException.class, () -> ReservationStatus.of(8));
        assertThrows(IllegalArgumentException.class, () -> ReservationStatus.of(-1));
    }

    @Test
    @DisplayName("只有已通过/已签到占单元，待审批绝不占")
    void onlyApprovedAndCheckedInOccupySlots() {
        for (ReservationStatus s : ReservationStatus.values()) {
            boolean expected = s == ReservationStatus.APPROVED || s == ReservationStatus.CHECKED_IN;
            assertEquals(expected, s.occupiesSlot(), "占单元判定不符：" + s);
        }
        // 这条是 v2 与 v1 最本质的差异，单独断言一次
        assertFalse(ReservationStatus.PENDING.occupiesSlot(),
                "待审批不占单元（文档 3.2.1）——若这里变成 true，就退回 v1 的区间重叠模型了");
    }

    @Test
    @DisplayName("终止态集合正确，且没有任何状态能迁回待审批")
    void terminalAndNoReturnToPending() {
        assertTrue(ReservationStatus.REJECTED.isTerminal());
        assertTrue(ReservationStatus.CANCELED.isTerminal());
        assertTrue(ReservationStatus.TIMEOUT_RELEASED.isTerminal());
        assertTrue(ReservationStatus.FINISHED.isTerminal());

        assertFalse(ReservationStatus.PENDING.isTerminal());
        assertFalse(ReservationStatus.APPROVED.isTerminal());
        assertFalse(ReservationStatus.CHECKED_IN.isTerminal());

        for (ReservationStatus s : ReservationStatus.values()) {
            assertFalse(s.canTransitTo(ReservationStatus.PENDING),
                    s + " 不应能迁回待审批：待审批是初始态，回退会破坏判定路径的可追溯性");
        }
    }

    @Test
    @DisplayName("终止态的后继集合必须为空")
    void terminalStatesHaveNoSuccessors() {
        for (ReservationStatus s : ReservationStatus.values()) {
            if (s.isTerminal()) {
                assertTrue(s.allowedTargets().isEmpty(), s + " 是终止态，不应有后继");
            }
        }
    }

    @Test
    @DisplayName("canTransitTo(null) 安全返回 false")
    void nullTargetIsRejected() {
        assertFalse(ReservationStatus.PENDING.canTransitTo(null));
    }
}
