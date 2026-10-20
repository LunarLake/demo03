package com.wyc.demo03.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wyc.demo03.entity.Appeal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface AppealMapper extends BaseMapper<Appeal> {

    /** 某用户已提交的工单（含原预约/扣分摘要）。 */
    @Select("SELECT a.*, r.start_time, r.end_time, m.room_name AS roomName, u.name AS reviewerName "
            + "FROM t_appeal a "
            + "LEFT JOIN t_reservation r ON a.target_type = 'RESERVATION' AND r.id = a.target_id "
            + "LEFT JOIN t_meeting_room m ON m.id = r.room_id "
            + "LEFT JOIN t_user u ON u.id = a.reviewer_id "
            + "WHERE a.user_id = #{userId} ORDER BY a.created_at DESC")
    List<Map<String, Object>> findByUser(@Param("userId") Long userId);

    /** 复核台列表：待复核的按应办结时间升序，超期的排前面。 */
    @Select("SELECT a.*, u.name AS applicantName, r.start_time, r.end_time, m.room_name AS roomName "
            + "FROM t_appeal a "
            + "JOIN t_user u ON u.id = a.user_id "
            + "LEFT JOIN t_reservation r ON a.target_type = 'RESERVATION' AND r.id = a.target_id "
            + "LEFT JOIN t_meeting_room m ON m.id = r.room_id "
            + "WHERE a.status = #{status} ORDER BY a.due_at ASC")
    List<Map<String, Object>> findByStatus(@Param("status") String status);

    /**
     * 抢占式复核：只有仍处于 SUBMITTED 的工单能被改。
     *
     * <p>两个并发复核请求各自读到"未处理"，如果直接 updateById 会双双成功，
     * 信用对冲就可能执行两次。用带前置状态的条件更新，受影响行数只有 1 个赢家（BUG-011）。
     */
    @Update("UPDATE t_appeal SET status = #{status}, reviewer_id = #{reviewerId}, "
            + "review_note = #{reviewNote}, decided_at = #{decidedAt} "
            + "WHERE id = #{id} AND status = 'SUBMITTED'")
    int decideIfSubmitted(@Param("id") Long id,
                          @Param("status") String status,
                          @Param("reviewerId") Long reviewerId,
                          @Param("reviewNote") String reviewNote,
                          @Param("decidedAt") LocalDateTime decidedAt);

    /** 超过应办结时间仍未处理的数量，用于后台告警。 */
    @Select("SELECT COUNT(*) FROM t_appeal WHERE status = 'SUBMITTED' AND due_at < #{now}")
    int countOverdue(@Param("now") LocalDateTime now);

    /** 已办结工单，按办结时间倒序。 */
    @Select("SELECT a.*, u.name AS applicantName, " +
            "       r.start_time, r.end_time, m.room_name AS roomName, rv.name AS reviewerName " +
            "FROM t_appeal a " +
            "JOIN t_user u ON u.id = a.user_id " +
            "LEFT JOIN t_reservation r ON a.target_type = 'RESERVATION' AND r.id = a.target_id " +
            "LEFT JOIN t_meeting_room m ON m.id = r.room_id " +
            "LEFT JOIN t_user rv ON rv.id = a.reviewer_id " +
            "WHERE a.status <> 'SUBMITTED' ORDER BY a.decided_at DESC LIMIT 100")
    List<Map<String, Object>> findDecided();
}
