package com.wyc.demo03.service;

import com.wyc.demo03.domain.SlotUnit;
import com.wyc.demo03.entity.ReservationSlot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 房间单元占用。
 *
 * <p>这是整个并发模型的唯一入口：外部只能通过 {@link #occupy} 占位、{@link #release} 释放，
 * 不允许直接操作 {@code t_reservation_slot}。
 */
public interface SlotService {

    /** 把一段起止展开为单元（调用方应已确认时间合法）。 */
    List<SlotUnit> unitsOf(LocalDateTime start, LocalDateTime end);

    /**
     * 只读预检：给定单元中已被有效占用的部分。
     *
     * <p>只读检查与真正写入之间存在时间窗口，因此**不能**把它当作授权依据 ——
     * 真正的仲裁由 {@link #occupy} 的唯一键完成。
     */
    List<ReservationSlot> findOccupied(Long roomId, List<SlotUnit> units);

    /**
     * 占用这些单元。
     *
     * @throws com.wyc.demo03.common.SlotConflictException 任一单元已被占用（调用方事务必须回滚）
     */
    void occupy(Long reservationId, Long roomId, List<SlotUnit> units);

    /** 释放某预约的全部占用；无占用时静默返回（幂等）。 */
    void release(Long reservationId);

    /** 某房间某日已占用的单元序号。 */
    Set<Integer> occupiedIndexes(Long roomId, LocalDate date);

    /** 某日全部房间的占用情况：roomId → 单元序号集合。 */
    Map<Long, Set<Integer>> occupiedByRoom(LocalDate date);
}
