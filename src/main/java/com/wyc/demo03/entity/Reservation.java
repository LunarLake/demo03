package com.wyc.demo03.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDateTime;

@Data
@TableName("t_reservation")
public class Reservation {
    @TableId(type = IdType.AUTO)
    //预约单主键ID
    private Long id;
    //申请人ID(关联t_user.id)
    private Long userId;
    //申请会议室ID(关联t_meeting_room.id)
    @NotNull
    private Long roomId;
    //预约使用起始时间
    @NotNull
    private LocalDateTime startTime;
    //预约使用结束时间
    @NotNull
    private LocalDateTime endTime;
    //审核状态：0-待审批，1-已批准，2-已拒绝，3-教师覆盖取消，4-用户取消，5-超时释放
    private Integer reservationStatus;
    //教师批准后系统生成的4位随机签到码
    private String checkInCode;
    //参会人数
    @Positive
    private Integer attendeeCount;
}
