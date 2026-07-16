package com.wyc.demo03.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_attendance_record")
public class AttendanceRecord {
    @TableId(type = IdType.AUTO)
    //签到记录主键ID
    private Long id;
    //对应的预约单ID(关联t_reservation.id)
    private Long reservationId;
    //应当签到的用户ID(关联t_user.id)
    private Long userId;
    //实际扫码签到时间戳
    private LocalDateTime checkInTime;
    //签到状态：0-未签到/旷到，1-已到/正常签到
    private Integer attendStatus;
    //签到客户端的远程IP地址
    private String ip;
}
