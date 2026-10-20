package com.wyc.demo03.domain;

import java.time.LocalDate;

/**
 * 一个 15 分钟占用单元：给定房间下的「某天 + 第几格」。
 *
 * <p>这就是 {@code t_reservation_slot} 的唯一键 {@code (room_id, slot_date, slot_index)} 中的后两段。
 * 刻意**不携带时刻**：时刻由 {@link SlotSchema} 按当日开放边界换算，避免同一个单元在不同
 * 时区/不同配置下算出不同时间，导致唯一键与实际占用对不上。
 *
 * @param date  占用日期
 * @param index 当日单元序号，0 起；08:00–22:00 共 56 个单元（默认配置）
 */
public record SlotUnit(LocalDate date, int index) {

    public SlotUnit {
        if (date == null) {
            throw new IllegalArgumentException("占用日期不能为空");
        }
        if (index < 0) {
            throw new IllegalArgumentException("单元序号不能为负：" + index);
        }
    }

    public static SlotUnit of(LocalDate date, int index) {
        return new SlotUnit(date, index);
    }

    /** 日志/断言用的紧凑表示，如 {@code 2026-10-21#8}。 */
    public String key() {
        return date + "#" + index;
    }
}
