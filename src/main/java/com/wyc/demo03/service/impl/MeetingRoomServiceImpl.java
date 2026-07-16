package com.wyc.demo03.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wyc.demo03.entity.MeetingRoom;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.mapper.MeetingRoomMapper;
import com.wyc.demo03.mapper.ReservationMapper;
import com.wyc.demo03.service.MeetingRoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MeetingRoomServiceImpl extends ServiceImpl<MeetingRoomMapper, MeetingRoom> implements MeetingRoomService {

    private final ReservationMapper reservationMapper;
    // 更新会议室前检查是否有未完成的预约
    // 如果有，返回 "has_active:数量"；如果没有，执行更新并返回 "success"
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String updateRoom(MeetingRoom room) {
        if (room.getRoomStatus() != null && room.getRoomStatus() == 1) {
            MeetingRoom existing = getById(room.getId());
            if (existing != null && existing.getRoomStatus() != 1) {
                LambdaQueryWrapper<Reservation> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(Reservation::getRoomId, room.getId())
                       .in(Reservation::getReservationStatus, 0, 1);
                long activeCount = reservationMapper.selectCount(wrapper);
                if (activeCount > 0) {
                    return "has_active:" + activeCount;
                }
            }
        }
        updateById(room);
        return "success";
    }
    // 删除会议室前检查是否有未完成的预约
    // 如果有，返回 "has_active:数量"；如果没有，执行删除并返回 "success"
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String deleteRoom(Long roomId) {
        LambdaQueryWrapper<Reservation> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Reservation::getRoomId, roomId)
               .in(Reservation::getReservationStatus, 0, 1);
        long activeCount = reservationMapper.selectCount(wrapper);
        if (activeCount > 0) {
            return "has_active:" + activeCount;
        }
        removeById(roomId);
        return "success";
    }
}
