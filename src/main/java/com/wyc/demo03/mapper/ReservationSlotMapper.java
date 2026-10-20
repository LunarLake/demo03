package com.wyc.demo03.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wyc.demo03.entity.ReservationSlot;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 占用单元 Mapper。
 *
 * <p>写入只有两种：{@link #insertUnits}（单条多值 INSERT，语句级原子）与
 * {@link #deleteByReservationId}。**没有** update —— 占用行不存在"部分修改"语义。
 */
@Mapper
public interface ReservationSlotMapper extends BaseMapper<ReservationSlot> {

    /**
     * 一次性插入多行占用。
     *
     * <p><b>必须是一条 SQL</b>：MySQL 对单条 INSERT 做语句级原子，任意一行撞
     * {@code uk_room_date_slot} 时整条语句回滚，不会留下"插了一半"的脏占用。
     * 如果拆成循环单行插入，前面的行会留在事务里，异常一抛就可能半新半旧。
     *
     * @return 插入行数；与 {@code slots.size()} 不等时说明有异常，调用方应视为失败
     */
    @Insert("<script>"
            + "INSERT INTO t_reservation_slot (reservation_id, room_id, slot_date, slot_index) VALUES "
            + "<foreach collection='slots' item='s' separator=','>"
            + "(#{s.reservationId}, #{s.roomId}, #{s.slotDate}, #{s.slotIndex})"
            + "</foreach>"
            + "</script>")
    int insertUnits(@Param("slots") List<ReservationSlot> slots);

    /** 某一天全部房间的占用单元，用于排期板一次取全。 */
    @Select("SELECT * FROM t_reservation_slot WHERE slot_date = #{date} ORDER BY room_id, slot_index")
    List<ReservationSlot> findByDate(@Param("date") LocalDate date);

    /** 某房间某天的占用单元。 */
    @Select("SELECT * FROM t_reservation_slot WHERE room_id = #{roomId} AND slot_date = #{date} ORDER BY slot_index")
    List<ReservationSlot> findByRoomAndDate(@Param("roomId") Long roomId, @Param("date") LocalDate date);

    /**
     * 某房间某天、指定序号区间内的占用单元（左闭右开）。
     * 用于"我要约这几格，哪些被别人占了"的精确冲突定位。
     */
    @Select("SELECT * FROM t_reservation_slot "
            + "WHERE room_id = #{roomId} AND slot_date = #{date} "
            + "  AND slot_index >= #{fromIndex} AND slot_index < #{toIndex} "
            + "ORDER BY slot_index")
    List<ReservationSlot> findInRange(@Param("roomId") Long roomId,
                                      @Param("date") LocalDate date,
                                      @Param("fromIndex") int fromIndex,
                                      @Param("toIndex") int toIndex);

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
