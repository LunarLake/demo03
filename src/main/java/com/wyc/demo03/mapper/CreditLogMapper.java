package com.wyc.demo03.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wyc.demo03.entity.CreditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface CreditLogMapper extends BaseMapper<CreditLog> {

    /** 某个申诉是否已经产生过对冲流水（保证对冲幂等）。 */
    @Select("SELECT COUNT(*) FROM t_credit_log WHERE appeal_id = #{appealId}")
    int countByAppealId(@Param("appealId") Long appealId);

    /**
     * 流水合计。信用分 = clamp(100 + SUM(delta), 0, 100)。
     * 用账本重算而不是累加缓存，保证任何一次写入失败都能自愈。
     */
    @Select("SELECT COALESCE(SUM(delta), 0) FROM t_credit_log WHERE user_id = #{userId}")
    int sumDelta(@Param("userId") Long userId);

    /** 某用户在指定时间之后是否已有指定原因的流水（用于"30 日内再犯"判定与防重复）。 */
    @Select("SELECT COUNT(*) FROM t_credit_log "
            + "WHERE user_id = #{userId} AND reason_code = #{reasonCode} AND created_at >= #{since}")
    int countSince(@Param("userId") Long userId,
                   @Param("reasonCode") String reasonCode,
                   @Param("since") LocalDateTime since);

    /** 某用户是否在指定时间之后有过负向流水（用于"30 日无爽约"加分）。 */
    @Select("SELECT COUNT(*) FROM t_credit_log "
            + "WHERE user_id = #{userId} AND delta < 0 AND created_at >= #{since}")
    int countNegativeSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    /** 分页用的流水明细，附带预约与房间信息，便于页面直接展示。 */
    @Select("SELECT c.*, r.start_time, r.end_time, m.room_name AS roomName "
            + "FROM t_credit_log c "
            + "LEFT JOIN t_reservation r ON r.id = c.reservation_id "
            + "LEFT JOIN t_meeting_room m ON m.id = r.room_id "
            + "WHERE c.user_id = #{userId} ORDER BY c.created_at DESC LIMIT #{offset}, #{size}")
    List<Map<String, Object>> findPage(@Param("userId") Long userId,
                                       @Param("offset") int offset,
                                       @Param("size") int size);
}
