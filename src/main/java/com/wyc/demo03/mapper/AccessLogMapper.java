package com.wyc.demo03.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wyc.demo03.entity.AccessLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AccessLogMapper extends BaseMapper<AccessLog> {

    /** 某预约的开门记录，最新在前。 */
    @Select("SELECT * FROM t_access_log WHERE reservation_id = #{reservationId} "
            + "ORDER BY created_at DESC, id DESC LIMIT 20")
    List<AccessLog> findByReservationId(@Param("reservationId") Long reservationId);
}
