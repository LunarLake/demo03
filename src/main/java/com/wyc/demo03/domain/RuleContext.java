package com.wyc.demo03.domain;

/**
 * 规则判定的输入快照。
 *
 * <p>刻意做成不可变的"事实集合"：规则只读它，不去查库。这样每条规则都能被纯单元测试覆盖，
 * 不需要为了测一条规则去搭数据库。
 *
 * @param role              申请人角色
 * @param roomAvailable     房间存在且非维护
 * @param slotCheck         单元规则校验结果（对齐/时长/开放时段/提前天数）
 * @param slotOccupied      目标单元是否已被别的有效预约占用
 * @param attendeeCount     参会人数
 * @param capacity          房间容量
 * @param purpose           用途说明（可能为 null）
 * @param creditScore       当前信用分
 * @param hasActiveViolation 是否存在未被申诉撤销的违规记录
 * @param autoPassToday     该用户当天已自动通过的笔数
 * @param units             本次申请的单元数
 */
public record RuleContext(
        String role,
        boolean roomAvailable,
        SlotCheck slotCheck,
        boolean slotOccupied,
        int attendeeCount,
        int capacity,
        String purpose,
        int creditScore,
        boolean hasActiveViolation,
        int autoPassToday,
        int units
) {

    /** 是否教师申请。 */
    public boolean teacher() {
        return "TEACHER".equals(role);
    }

    /** 本次时长（分钟）。 */
    public int durationMinutes(int unitMinutes) {
        return units * unitMinutes;
    }

    /** 用途是否已填写。 */
    public boolean hasPurpose() {
        return purpose != null && !purpose.isBlank();
    }
}
