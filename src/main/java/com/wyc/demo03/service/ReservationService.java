package com.wyc.demo03.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.wyc.demo03.domain.ReservationStatus;
import com.wyc.demo03.entity.Reservation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 预约业务。
 *
 * <p>v2 的核心变化：
 * <ul>
 *   <li>提交申请<b>不再</b>直接占用时段，改为"基础规则 + 附加规则 + 人工"三层判定</li>
 *   <li>只有「已通过 / 已签到」占用单元；待审批允许同一时段多笔并存</li>
 *   <li>所有状态变更走守卫式条件更新，并发下由数据库判定赢家</li>
 * </ul>
 */
public interface ReservationService extends IService<Reservation> {

    /** 提交申请的结果。 */
    record SubmitResult(boolean accepted,
                        Long reservationId,
                        ReservationStatus status,
                        String errorCode,
                        String message,
                        String checkInCode) {

        public static SubmitResult fail(String errorCode, String message) {
            return new SubmitResult(false, null, null, errorCode, message, null);
        }

        public static SubmitResult ok(Long id, ReservationStatus status, String message, String code) {
            return new SubmitResult(true, id, status, null, message, code);
        }

        /** 是否直接进入「已通过」。 */
        public boolean approvedImmediately() {
            return accepted && status == ReservationStatus.APPROVED;
        }
    }

    /** 人工批准的结果。 */
    record ApproveResult(boolean ok, String errorCode, String checkInCode, String message) {

        public static ApproveResult fail(String errorCode, String message) {
            return new ApproveResult(false, errorCode, null, message);
        }

        public static ApproveResult ok(String checkInCode) {
            return new ApproveResult(true, null, checkInCode, "审批通过");
        }
    }

    // ==================== 写操作 ====================

    /**
     * 提交预约申请。
     *
     * @param form   表单绑定的预约（roomId / startTime / endTime / attendeeCount / purpose）
     * @param userId 申请人（来自 Session，不可由前端指定）
     * @param role   申请人角色
     */
    SubmitResult submit(Reservation form, Long userId, String role);

    /**
     * 人工批准一笔待审批申请。
     *
     * <p>会先重检单元占用；冲突时抛
     * {@link com.wyc.demo03.common.SlotConflictException} 让整个事务回滚，
     * 因此不存在"状态改了但没占上单元"的中间态。
     */
    ApproveResult approve(Long id, Long adminId);

    /** 人工拒绝。调用方负责把异常翻译成页面提示。 */
    void reject(Long id, String reason, Long adminId);

    /**
     * 用户取消自己的预约。
     *
     * @return success / not_owner / invalid_status / already_started / failed
     */
    String cancel(Long reservationId, Long userId);

    /**
     * 签到。
     *
     * @return success / error / too_early / expired / too_many_attempts / wrong_code / already_checked_in
     */
    String checkIn(Long reservationId, Long userId, String code, String ip);

    // ==================== 定时任务 ====================

    /** 待审批到了开始时刻仍未处理 → 超时释放。返回处理笔数。 */
    int releasePendingStarted(LocalDateTime now);

    /** 已通过但签到截止仍未签到 → 超时释放（P3 会在此扣信用分）。 */
    int releaseNoShow(LocalDateTime now);

    /** 到达结束时刻的有效预约 → 已结束（P3 会在此加信用分）。 */
    int finishExpired(LocalDateTime now);

    /** 结束前提醒：登记提醒时刻并返回需要提醒的预约（P5 会下发到设备）。 */
    List<Reservation> remindEnding(LocalDateTime now);

    /**
     * 管理员手工认定"超时占用"（−5 分）。
     *
     * <p>一期没有人体感应也不做签退，"结束超时占用累计超过 15 分钟"无法自动判定，
     * 只能由管理员在预约详情页认定（见计划 Q1）。认定记录写入信用流水并标注操作者。
     */
    void markOvertimeOccupancy(Long reservationId, Long adminId, String note);

    /** 管理员认定"违规使用"（−20 分）。 */
    void markViolation(Long reservationId, Long adminId, String note);

    // ==================== 查询 ====================

    List<Map<String, Object>> findApprovals();

    IPage<Map<String, Object>> findByUserIdWithRoomPage(Page<?> page, Long userId, Integer statusGroup);

    Map<String, Object> findDetailById(Long id);

    /** 某房间某日的有效预约（已通过 / 已签到）。 */
    List<Map<String, Object>> findScheduleByRoomAndDate(Long roomId, LocalDate date);

    List<Map<String, Object>> countByRoom();

    List<Map<String, Object>> countByHour();
}
