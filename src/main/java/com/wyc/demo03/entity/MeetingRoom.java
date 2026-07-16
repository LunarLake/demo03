package com.wyc.demo03.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("t_meeting_room")
public class MeetingRoom {
    @TableId(type = IdType.AUTO)
    //会议室主键ID
    private Long id;
    //会议室名称/房间号(如：中得楼302)
    private String roomName;
    //会议室容量
    private Integer capacity;
    //会议室设备
    private String equipment;
    //会议室当前状态：0-正常可用，1-维护中
    private Integer roomStatus;
}
