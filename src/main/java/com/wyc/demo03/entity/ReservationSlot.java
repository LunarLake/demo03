package com.wyc.demo03.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 有效占用单元（{@code t_reservation_slot}）。
 *
 * <p>一行 = 该「房间 + 日期 + 单元序号」被有效占用。只有「已通过/已签到」的预约会写入，
 * 预约进入终止态时整批删除。
 *
 * <p>并发正确性完全依赖唯一键 {@code uk_room_date_slot}：批准时逐行 insert，
 * 任意一行撞键即抛 {@code DuplicateKeyException}，事务整体回滚。
 * 因此这里**不要**加任何"先查再插"的自定义逻辑。
 */
@Data
@TableName("t_reservation_slot")
public class ReservationSlot {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 t_reservation.id */
    private Long reservationId;

    /** 冗余房间 ID，供唯一键使用 */
    private Long roomId;

    /** 占用日期 */
    private LocalDate slotDate;

    /** 当日单元序号：0..55，对应 08:00 + index*15min */
    private Integer slotIndex;

    public static ReservationSlot of(Long reservationId, Long roomId, LocalDate slotDate, int slotIndex) {
        ReservationSlot slot = new ReservationSlot();
        slot.setReservationId(reservationId);
        slot.setRoomId(roomId);
        slot.setSlotDate(slotDate);
        slot.setSlotIndex(slotIndex);
        return slot;
    }
}
