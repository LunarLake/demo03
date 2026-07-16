package com.wyc.demo03.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import java.util.List;
import java.util.Map;
import com.wyc.demo03.entity.AttendanceRecord;
import com.wyc.demo03.mapper.AttendanceRecordMapper;
import com.wyc.demo03.service.AttendanceRecordService;
import org.springframework.stereotype.Service;

@Service
public class AttendanceRecordServiceImpl extends ServiceImpl<AttendanceRecordMapper, AttendanceRecord> implements AttendanceRecordService {

    @Override
    public AttendanceRecord findByReservationId(Long reservationId) {
        return baseMapper.findByReservationId(reservationId);
    }

    @Override
    public int countTodayCheckIn() {
        return baseMapper.countTodayCheckIn();
    }

    @Override
    public List<Map<String, Object>> countAttendanceRate() {
        return baseMapper.countAttendanceRate();
    }
}
