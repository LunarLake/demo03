package com.wyc.demo03.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wyc.demo03.entity.MeetingRoom;

public interface MeetingRoomService extends IService<MeetingRoom> {
    String deleteRoom(Long roomId);
    String updateRoom(MeetingRoom room);
}
