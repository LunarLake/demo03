package com.wyc.demo03.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wyc.demo03.entity.Reservation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface ReservationMapper extends BaseMapper<Reservation> {
    // 查找指定会议室在指定时间段内的冲突预约（预约状态为 0 或 1，且预约时间与给定时间段有重叠的预约）
    @Select("SELECT r.* FROM t_reservation r WHERE r.room_id = #{roomId} AND r.reservation_status IN (0, 1) AND r.start_time < #{endTime} AND r.end_time > #{startTime}")
    List<Reservation> findConflicting(@Param("roomId") Long roomId, @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);
    // 查找指定用户的预约列表，返回列表包含预约信息、会议室名称和签到状态（如果有签到记录的话），按预约开始时间降序排序
    @Select("SELECT r.*, m.room_name AS roomName, ar.attend_status FROM t_reservation r JOIN t_meeting_room m ON r.room_id = m.id LEFT JOIN t_attendance_record ar ON r.id = ar.reservation_id WHERE r.user_id = #{userId} ORDER BY r.start_time DESC")
    List<Map<String, Object>> findByUserIdWithRoom(@Param("userId") Long userId);
    // 查找所有待审批的预约列表，返回列表包含预约信息、预约用户名称、会议室名称和容量，按预约开始时间升序排序
    @Select("SELECT r.*, u.name AS userName, m.room_name AS roomName, m.capacity AS capacity FROM t_reservation r JOIN t_user u ON r.user_id = u.id JOIN t_meeting_room m ON r.room_id = m.id WHERE r.reservation_status = 0 ORDER BY r.start_time ASC")
    List<Map<String, Object>> findApprovals();
    // 统计每个会议室的预约次数，返回列表包含会议室名称和对应的预约次数，按预约次数降序排序
    @Select("SELECT m.room_name AS label, COUNT(*) AS count FROM t_reservation r JOIN t_meeting_room m ON r.room_id = m.id GROUP BY m.room_name ORDER BY count DESC")
    List<Map<String, Object>> countByRoom();
    // 统计每天的预约次数，返回列表包含预约日期（格式为 "YYYY-MM-DD"）和对应的预约次数，按预约日期升序排序
    @Select("SELECT HOUR(start_time) AS label, COUNT(*) AS count FROM t_reservation WHERE reservation_status = 1 GROUP BY HOUR(start_time) ORDER BY label ASC")
    List<Map<String, Object>> countByHour();
    // 查找指定预约 ID 的预约详情，返回包含预约信息、预约用户名称、会议室名称、签到状态、签到时间和签到 IP 等信息的 Map
    @Select("SELECT r.*, u.name AS userName, m.room_name AS roomName, ar.attend_status, ar.check_in_time AS checkInTime, ar.ip AS checkInIp FROM t_reservation r JOIN t_user u ON r.user_id = u.id JOIN t_meeting_room m ON r.room_id = m.id LEFT JOIN t_attendance_record ar ON r.id = ar.reservation_id WHERE r.id = #{id}")
    Map<String, Object> findDetailById(@Param("id") Long id);
    // 查找所有已过期但未签到的预约列表，返回列表包含预约信息、预约用户名称和会议室名称，按预约开始时间升序排序
    @Select("SELECT r.* FROM t_reservation r LEFT JOIN t_attendance_record ar ON r.id = ar.reservation_id WHERE r.reservation_status = 1 AND r.start_time < #{cutoffTime} AND (ar.id IS NULL OR ar.attend_status = 0)")
    List<Reservation> findExpiredUnchecked(@Param("cutoffTime") LocalDateTime cutoffTime);
    // 查找指定会议室在指定日期的预约列表，返回列表包含预约开始时间、结束时间、预约用户名称和角色等信息，按预约开始时间升序排序
    @Select("SELECT r.start_time, r.end_time, u.name AS userName, u.role AS role FROM t_reservation r JOIN t_user u ON r.user_id = u.id WHERE r.room_id = #{roomId} AND r.reservation_status = 1 AND DATE(r.start_time) = #{date} ORDER BY r.start_time")
    List<Map<String, Object>> findByRoomAndDate(@Param("roomId") Long roomId, @Param("date") String date);
}
