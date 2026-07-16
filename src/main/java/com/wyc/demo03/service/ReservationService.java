package com.wyc.demo03.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wyc.demo03.entity.Reservation;
import jakarta.servlet.http.HttpSession;

import java.util.List;
import java.util.Map;

public interface ReservationService extends IService<Reservation> {
    // 预约申请，返回结果字符串（成功或失败原因）
    String apply(Reservation reservation, HttpSession session);
    // 管理员审批通过预约，返回结果字符串（成功或失败原因）
    String approve(Long id);
    // 管理员审批拒绝预约，返回结果字符串（成功或失败原因）
    void reject(Long id);
    // 用户签到，返回结果字符串（成功或失败原因）
    String checkIn(Long reservationId, Long userId, String code, String ip);
    // 用户取消预约，返回结果字符串（成功或失败原因）
    String cancel(Long reservationId, Long userId);

    List<Map<String, Object>> findApprovals();
    // 按房间统计预约数量
    List<Map<String, Object>> countByRoom();
    // 按小时统计预约数量
    List<Map<String, Object>> countByHour();
    // 根据用户ID查询预约及房间信息
    List<Map<String, Object>> findByUserIdWithRoom(Long userId);
    // 根据预约ID查询详细信息
    Map<String, Object> findDetailById(Long id);
    // 根据房间ID和日期查询预约安排
    List<Map<String, Object>> findScheduleByRoomAndDate(Long roomId, String date);
}
