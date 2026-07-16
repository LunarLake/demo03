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
    // 统计每个预约的出勤率（签到人数 / 预约人数），返回列表包含预约 ID、预约名称、出勤率等信息
    List<Map<String, Object>> countAttendanceRate();
}
