package com.wyc.demo03.device;

import com.wyc.demo03.entity.MeetingRoom;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.mapper.MeetingRoomMapper;
import com.wyc.demo03.mapper.ReservationMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 无真机时的"模拟屏幕"。
 *
 * <p>这里守的是一条回归：{@code refreshAll()} 曾经是空实现，而每分钟只有它在跑，
 * 于是签到窗口内没有任何机会把码打印出来 —— 默认配置下整条"看屏输码"链路是断的。
 */
@ExtendWith(MockitoExtension.class)
class LogDeviceNotifierTest {

    @Mock private ReservationMapper reservationMapper;
    @Mock private MeetingRoomMapper meetingRoomMapper;
    @Mock private ScreenPayloadBuilder screenPayloadBuilder;

    /** 固定 2026-11-28 10:05（Asia/Shanghai）。 */
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-28T02:05:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    @DisplayName("refreshAll 必须逐房间算屏幕内容：签到窗口内靠它把码打出来")
    void refreshAllWalksEveryRoom() {
        when(meetingRoomMapper.selectList(null)).thenReturn(List.of(room(1L), room(2L)));

        Reservation current = new Reservation();
        current.setId(9L);
        current.setRoomId(1L);
        when(reservationMapper.findCurrentForRoom(eq(1L), any())).thenReturn(current);
        when(reservationMapper.findCurrentForRoom(eq(2L), any())).thenReturn(null);
        when(screenPayloadBuilder.build(eq(current), any())).thenReturn("reservation=9;status=APPROVED;code=1234");

        new LogDeviceNotifier(reservationMapper, meetingRoomMapper, screenPayloadBuilder, clock).refreshAll();

        verify(screenPayloadBuilder).build(eq(current), any());
        // 只有 1 号房有当前预约，2 号房空闲不应构建载荷
        verify(screenPayloadBuilder, times(1)).build(any(), any());
    }

    @Test
    @DisplayName("全部房间空闲时不构建任何载荷，避免每分钟刷屏")
    void refreshAllStaysQuietWhenIdle() {
        when(meetingRoomMapper.selectList(null)).thenReturn(List.of(room(1L)));
        when(reservationMapper.findCurrentForRoom(eq(1L), any())).thenReturn(null);

        new LogDeviceNotifier(reservationMapper, meetingRoomMapper, screenPayloadBuilder, clock).refreshAll();

        verify(screenPayloadBuilder, never()).build(any(), any());
    }

    private MeetingRoom room(Long id) {
        MeetingRoom room = new MeetingRoom();
        room.setId(id);
        return room;
    }
}
