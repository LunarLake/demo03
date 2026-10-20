package com.wyc.demo03.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wyc.demo03.entity.DeviceCommand;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DeviceCommandMapper extends BaseMapper<DeviceCommand> {

    @Select("SELECT * FROM t_device_command WHERE msg_id = #{msgId}")
    DeviceCommand findByMsgId(@Param("msgId") String msgId);

    /** 设备回执：只有仍处于 SENT 的指令才接受回执，避免迟到回执覆盖已结算的状态。 */
    @Update("UPDATE t_device_command SET status = #{status}, result_code = #{resultCode}, acked_at = #{ackedAt} "
            + "WHERE msg_id = #{msgId} AND status = 'SENT'")
    int ack(@Param("msgId") String msgId,
            @Param("status") String status,
            @Param("resultCode") String resultCode,
            @Param("ackedAt") LocalDateTime ackedAt);

    /** 超时未回执的指令置为 EXPIRED。 */
    @Update("UPDATE t_device_command SET status = 'EXPIRED' "
            + "WHERE status = 'SENT' AND expires_at < #{now}")
    int expireOverdue(@Param("now") LocalDateTime now);

    /** 某预约最近的下发记录，用于详情页时间线与"重复开锁"排查。 */
    @Select("SELECT * FROM t_device_command WHERE reservation_id = #{reservationId} ORDER BY issued_at DESC")
    List<DeviceCommand> findByReservationId(@Param("reservationId") Long reservationId);

    /** 某设备最近的指令，用于设备管理页。 */
    @Select("SELECT * FROM t_device_command WHERE device_id = #{deviceId} ORDER BY issued_at DESC LIMIT #{limit}")
    List<DeviceCommand> findRecentByDevice(@Param("deviceId") Long deviceId, @Param("limit") int limit);
}
