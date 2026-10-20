package com.wyc.demo03.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wyc.demo03.entity.CheckinCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface CheckinCodeMapper extends BaseMapper<CheckinCode> {

    /** 取某预约的签到码（含已撤销的，便于区分"没有码"与"码已撤销"）。 */
    @Select("SELECT * FROM t_checkin_code WHERE reservation_id = #{reservationId}")
    CheckinCode findByReservationId(@Param("reservationId") Long reservationId);

    /**
     * 撤销签到码。已撤销的不重复写（{@code revoked_at IS NULL} 条件更新）。
     * 返回 0 表示本来就没有码或已撤销，调用方无需当作错误。
     */
    @Update("UPDATE t_checkin_code SET revoked_at = #{revokedAt} "
            + "WHERE reservation_id = #{reservationId} AND revoked_at IS NULL")
    int revoke(@Param("reservationId") Long reservationId, @Param("revokedAt") LocalDateTime revokedAt);
}
