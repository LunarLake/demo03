package com.wyc.demo03.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wyc.demo03.entity.AttendanceRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AttendanceRecordMapper extends BaseMapper<AttendanceRecord> {
    // 根据预约 ID 查找对应的签到记录（一个预约对应一条签到记录）
    @Select("SELECT * FROM t_attendance_record WHERE reservation_id = #{reservationId}")
    AttendanceRecord findByReservationId(Long reservationId);
    // 统计今日签到人数（签到时间在当天 00:00:00 到 23:59:59 之间的记录数）
    @Select("SELECT COUNT(*) FROM t_attendance_record WHERE attend_status = 1 AND DATE(check_in_time) = CURDATE()")
    int countTodayCheckIn();
    // 统计每个预约的出勤率（签到人数 / 预约人数），返回列表包含预约 ID、预约名称、出勤率等信息
    @Select("SELECT '已签到' AS label, COUNT(*) AS count FROM t_attendance_record WHERE attend_status = 1 UNION ALL SELECT '未签到/缺席' AS label, COUNT(*) AS count FROM t_attendance_record ar JOIN t_reservation r ON ar.reservation_id = r.id WHERE ar.attend_status = 0 AND r.reservation_status = 1 AND r.end_time < NOW()")
    List<Map<String, Object>> countAttendanceRate();
}
