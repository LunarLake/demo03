package com.wyc.demo03.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wyc.demo03.entity.AttendanceRecord;

import java.util.List;
import java.util.Map;

public interface AttendanceRecordService extends IService<AttendanceRecord> {
    // 根据预约 ID 查找对应的签到记录（一个预约对应一条签到记录）
    AttendanceRecord findByReservationId(Long reservationId);
    // 统计今日签到人数（签到时间在当天 00:00:00 到 23:59:59 之间的记录数）
    int countTodayCheckIn();
    /**
     * 预约出勤分布统计。
     *
     * <p>返回 {@code [{label, count}, ...]}，label 取值见
     * {@link com.wyc.demo03.mapper.AttendanceRecordMapper#countAttendanceRate()}。
     * 只有「已签到」与「缺席（爽约）」进入出勤率的分子分母，
     * 主动取消 / 已拒绝 / 待审超时 / 未到期都不算缺席。
     */
    List<Map<String, Object>> countAttendanceRate();
}
