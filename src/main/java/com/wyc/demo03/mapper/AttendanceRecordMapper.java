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
    /**
     * 预约出勤分布（不是"签到率"一个数 —— 见下方口径说明）。
     *
     * <p><b>为什么不能沿用 v1 的 {@code attend_status = 0}</b>：v2 把 {@code t_attendance_record}
     * 改成"签到成功才写一行"，未签到是<b>没有行</b>，不是"有一行状态为 0"。
     * 旧 SQL 查 {@code attend_status = 0} 的行，结果恒为 0，爽约一笔都算不出来。
     *
     * <p><b>口径（从预约出发，而不是从签到表出发）</b>：
     * <pre>
     *   已签到         —— 存在签到记录（不论当前是已签到/已结束）
     *   缺席（爽约）   —— 无签到记录，且状态为 超时释放(5) 且曾签发签到码，或 已结束(7)
     *   待审超时       —— 无签到记录，状态 5，但从未签发签到码（待审批到开始时刻被释放，不算缺席）
     *   主动取消       —— 状态 3
     *   已拒绝         —— 状态 2
     *   待定（未到期） —— 状态 0/1 且尚未签到（还没到判定时点，不能提前判缺席）
     * </pre>
     *
     * <p><b>"曾要求出席"用 {@code t_checkin_code} 是否有行来表达</b>：三条通过路径
     * （自动通过 / 教师预约 / 人工批准）都会签发签到码，待审批不会；且撤销只置
     * {@code revoked_at}、不删行 —— 因此"有码"等价于"曾经通过审批、期待出席"。
     * 这是把「爽约」与「待审超时」区分开的唯一可靠依据：两者终态都是 5。
     *
     * <p>两张副表的 {@code reservation_id} 都是唯一键，LEFT JOIN 不会放大行数，
     * {@code COUNT(*)} 计的就是预约笔数。
     */
    @Select("SELECT "
            + "  CASE "
            + "    WHEN ar.id IS NOT NULL                          THEN '已签到' "
            + "    WHEN r.reservation_status = 5 AND cc.id IS NULL THEN '待审超时' "
            + "    WHEN r.reservation_status IN (5, 7)             THEN '缺席（爽约）' "
            + "    WHEN r.reservation_status = 3                   THEN '主动取消' "
            + "    WHEN r.reservation_status = 2                   THEN '已拒绝' "
            + "    ELSE '待定（未到期）' "
            + "  END AS label, "
            + "  COUNT(*) AS count "
            + "FROM t_reservation r "
            + "LEFT JOIN t_attendance_record ar ON ar.reservation_id = r.id AND ar.attend_status = 1 "
            + "LEFT JOIN t_checkin_code cc ON cc.reservation_id = r.id "
            + "GROUP BY label "
            + "ORDER BY FIELD(label, '已签到', '缺席（爽约）', '主动取消', '待审超时', '已拒绝', '待定（未到期）')")
    List<Map<String, Object>> countAttendanceRate();
}
