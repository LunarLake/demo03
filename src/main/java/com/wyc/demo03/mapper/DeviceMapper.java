package com.wyc.demo03.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wyc.demo03.entity.Device;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface DeviceMapper extends BaseMapper<Device> {

    @Select("SELECT * FROM t_device WHERE device_code = #{deviceCode}")
    Device findByCode(@Param("deviceCode") String deviceCode);

    /** 某房间的在线设备（重连/开机后由服务端补发快照时用）。 */
    @Select("SELECT * FROM t_device WHERE room_id = #{roomId} LIMIT 1")
    Device findByRoomId(@Param("roomId") Long roomId);

    /** 心跳：更新时刻并置在线。 */
    @Update("UPDATE t_device SET last_heartbeat_at = #{at}, online = 1 WHERE device_code = #{deviceCode}")
    int touchHeartbeat(@Param("deviceCode") String deviceCode, @Param("at") LocalDateTime at);

    /** 心跳看门狗：超时未心跳的置离线，返回受影响行数。 */
    @Update("UPDATE t_device SET online = 0 WHERE online = 1 AND last_heartbeat_at < #{deadline}")
    int markOffline(@Param("deadline") LocalDateTime deadline);
}
