package com.wyc.demo03.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wyc.demo03.domain.ReservationStatus;
import lombok.Data;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDateTime;

/**
 * 预约单（{@code t_reservation}）。
 *
 * <p>v2 相比 v1 的变化：
 * <ul>
 *   <li>状态码重定义为七态，见 {@link ReservationStatus}；码 4 已废弃</li>
 *   <li>{@code rejectReason} 改名 {@code decisionReason}（拒绝/取消/释放共用）</li>
 *   <li>{@code checkInCode} 迁出到 {@code t_checkin_code}（需要独立撤销位）</li>
 *   <li>新增判定路径、规则版本、命中规则、取消来源、审核人、提醒与终止时刻</li>
 * </ul>
 */
@Data
@TableName("t_reservation")
public class Reservation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 申请人ID（关联 t_user.id） */
    private Long userId;

    /** 房间ID */
    @NotNull
    private Long roomId;

    /** 预约开始时刻，必然对齐 15 分钟整点 */
    @NotNull
    private LocalDateTime startTime;

    /** 预约结束时刻，必然对齐 15 分钟整点 */
    @NotNull
    private LocalDateTime endTime;

    /** 状态码，见 {@link ReservationStatus} */
    private Integer reservationStatus;

    /** 参会人数（1..房间容量） */
    @Positive
    private Integer attendeeCount;

    /** 用途说明（信用分 <70 转人工时必填） */
    private String purpose;

    /** 拒绝/取消/释放的原因 */
    private String decisionReason;

    /** 判定所依据的规则版本 */
    private Long ruleVersionId;

    /** 判定路径，见 {@link com.wyc.demo03.domain.DecisionPath} */
    private String decisionPath;

    /** 命中的规则编号，逗号分隔 */
    private String hitRuleCodes;

    /** 取消来源：USER / TEACHER_OVERRIDE / ADMIN */
    private String cancelSource;

    /** 人工审核人 */
    private Long approvedBy;

    /** 人工审核时刻 */
    private LocalDateTime approvedAt;

    /** 结束前提醒的登记时刻，保证只提醒一次 */
    private LocalDateTime remindedAt;

    /** 进入终止态的时刻 */
    private LocalDateTime closedAt;

    /** 乐观锁版本号 */
    private Integer version;

    // ==================== 便捷方法（只读，不改库） ====================

    /** 解析状态枚举；脏数据返回 null 而不是抛异常，供只读展示使用。 */
    public ReservationStatus statusOrNull() {
        return ReservationStatus.ofOrNull(reservationStatus);
    }

    /** 是否处于有效占用（已通过 / 已签到）。 */
    public boolean occupiesSlot() {
        ReservationStatus s = statusOrNull();
        return s != null && s.occupiesSlot();
    }

    /** 是否已开始（含正在进行）。 */
    public boolean hasStarted(LocalDateTime now) {
        return startTime != null && !now.isBefore(startTime);
    }

    /** 是否已结束。 */
    public boolean hasEnded(LocalDateTime now) {
        return endTime != null && !now.isBefore(endTime);
    }
}
