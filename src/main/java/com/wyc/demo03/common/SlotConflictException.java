package com.wyc.demo03.common;

import com.wyc.demo03.entity.ReservationSlot;

import java.util.List;

/**
 * 占用冲突：目标单元已被别的有效预约占住。
 *
 * <p>这是**正常业务结果**而不是程序错误 —— 两个管理员同时批准同一时段、或教师覆盖时
 * 撞上别的预约，都会走到这里。用异常是为了触发事务回滚（已经写入的状态与占用必须一起撤销），
 * 控制器负责把它翻译成可读提示。
 */
public class SlotConflictException extends RuntimeException {

    /** 冲突的占用行（含占用方 reservationId），用于提示"被谁占了"。 */
    private final transient List<ReservationSlot> conflicts;

    public SlotConflictException(List<ReservationSlot> conflicts) {
        super("时段已被占用：" + conflicts.size() + " 个单元冲突");
        this.conflicts = List.copyOf(conflicts);
    }

    public List<ReservationSlot> getConflicts() {
        return conflicts;
    }
}
