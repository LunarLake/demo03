package com.wyc.demo03.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wyc.demo03.entity.ReservationSlot;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 占用单元 Mapper。
 *
 * <p>写入只有两种：逐行 {@code insert}（撞唯一键即抛 {@code DuplicateKeyException}，
 * 由上层事务回滚）与按预约整批 {@code delete}。**没有** update —— 占用行不存在"部分修改"语义。
 */
@Mapper
public interface ReservationSlotMapper extends BaseMapper<ReservationSlot> {

    /** 某一天全部房间的占用单元，用于排期板一次取全。 */
    @Select("SELECT * FROM t_reservation_slot WHERE slot_date = #{date} ORDER BY room_id, slot_index")
    List<ReservationSlot> findByDate(@Param("date") LocalDate date);

    /** 某房间某天的占用单元。 */
    @Select("SELECT * FROM t_reservation_slot WHERE room_id = #{roomId} AND slot_date = #{date} ORDER BY slot_index")
    List<ReservationSlot> findByRoomAndDate(@Param("roomId") Long roomId, @Param("date") LocalDate date);

    /** 某预约占用的全部单元。 */
    @Select("SELECT * FROM t_reservation_slot WHERE reservation_id = #{reservationId} ORDER BY slot_index")
    List<ReservationSlot> findByReservationId(@Param("reservationId") Long reservationId);

    /** 预约终止时清除其全部占用（拒绝/取消/超时释放/已结束）。 */
    @Delete("DELETE FROM t_reservation_slot WHERE reservation_id = #{reservationId}")
    int deleteByReservationId(@Param("reservationId") Long reservationId);

    /** 指定单元是否已被有效占用。 */
    @Select("SELECT COUNT(*) FROM t_reservation_slot "
            + "WHERE room_id = #{roomId} AND slot_date = #{date} AND slot_index = #{slotIndex}")
    int countOccupied(@Param("roomId") Long roomId,
                      @Param("date") LocalDate date,
                      @Param("slotIndex") int slotIndex);
}
