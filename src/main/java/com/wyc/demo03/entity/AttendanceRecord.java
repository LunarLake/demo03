package com.wyc.demo03.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 首次签到记录（{@code t_attendance_record}）。
 *
 * <p>v2 起 {@code reservationId} 为唯一键：一笔预约最多一条记录。
 * 因此"未签到"用**没有记录**表达（而不是 {@code attend_status = 0} 的行），
 * 签到成功判定交给数据库的唯一约束，不再依赖"先查再条件更新"。
 */
@Data
@TableName("t_attendance_record")
public class AttendanceRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对应的预约单ID（唯一） */
    private Long reservationId;

    /** 签到用户ID */
    private Long userId;

    /** 首次签到成功时刻 */
    private LocalDateTime checkInTime;

    /** 签到状态：0-未签到，1-已签到 */
    private Integer attendStatus;

    /** 签到方式：SCREEN_CODE（屏幕码） */
    private String method;

    /** 签到客户端 IP */
    private String ip;
}
