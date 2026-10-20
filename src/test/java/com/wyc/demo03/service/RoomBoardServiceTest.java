package com.wyc.demo03.service;

import com.wyc.demo03.domain.ReservationStatus;
import com.wyc.demo03.domain.SlotSchema;
import com.wyc.demo03.entity.MeetingRoom;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.entity.ReservationSlot;
import com.wyc.demo03.mapper.MeetingRoomMapper;
import com.wyc.demo03.mapper.ReservationMapper;
import com.wyc.demo03.mapper.ReservationSlotMapper;
import com.wyc.demo03.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 排期板状态聚合。
 *
 * <p>这块逻辑的价值全在"优先级"上：同一格可能同时"被占着"和"超出可预约天数"，
 * 先说哪个决定了用户看到什么。判错顺序就会出现"我的预约显示成别人的"、
 * "维护中的房间还能点"这类直接误导操作的问题。
 */
@ExtendWith(MockitoExtension.class)
class RoomBoardServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private static final LocalDateTime NOW = TODAY.atTime(9, 0);
    private static final Long ME = 7L;

    @Mock private MeetingRoomMapper meetingRoomMapper;
    @Mock private ReservationMapper reservationMapper;
    @Mock private ReservationSlotMapper reservationSlotMapper;

    private final SlotSchema schema = SlotSchema.of(8 * 60, 22 * 60, 15, 1, 16, 7);
    private final MutableClock clock = MutableClock.at(NOW);
    private RoomBoardService service;

    private MeetingRoom roomA;
    private MeetingRoom roomB;

    @BeforeEach
    void setUp() {
        service = new RoomBoardService(meetingRoomMapper, reservationMapper,
                reservationSlotMapper, schema, clock);

        roomA = new MeetingRoom();
        roomA.setId(1L);
        roomA.setRoomName("研讨室A");
        roomA.setCapacity(6);
        roomA.setRoomStatus(0);

        roomB = new MeetingRoom();
        roomB.setId(2L);
        roomB.setRoomName("研讨室B");
        roomB.setCapacity(6);
        roomB.setRoomStatus(1); // 维护中

        lenient().when(meetingRoomMapper.selectList(any())).thenReturn(List.of(roomA, roomB));
        lenient().when(reservationSlotMapper.findByDate(any())).thenReturn(List.of());
        lenient().when(reservationMapper.findActiveOnDate(any(), any())).thenReturn(List.of());
    }

    private Reservation reservation(long id, long userId, int status,
                                    LocalDateTime start, LocalDateTime end) {
        Reservation r = new Reservation();
        r.setId(id);
        r.setUserId(userId);
        r.setRoomId(1L);
        r.setReservationStatus(status);
        r.setStartTime(start);
        r.setEndTime(end);
        return r;
    }

    private ReservationSlot slot(long reservationId, int index) {
        return ReservationSlot.of(reservationId, 1L, TODAY.plusDays(1), index);
    }

    private RoomBoardService.Slot slotAt(RoomBoardService.Board board, int roomIndex, int index) {
        return board.rooms().get(roomIndex).slots().get(index);
    }

    // ====================================================================

    @Test
    @DisplayName("基本结构：每间房 56 格，时间刻度 08:00–22:00")
    void boardShape() {
        RoomBoardService.Board board = service.build(TODAY.plusDays(1), ME);

        assertEquals(56, board.slotsPerDay());
        assertEquals(2, board.rooms().size());
        assertEquals(56, board.rooms().get(0).slots().size());
        assertEquals("08:00", board.openTime());
        assertEquals("22:00", board.closeTime());
        assertEquals(15, board.unitMinutes());
        assertEquals(15, board.hourMarks().size(), "08:00 到 22:00 共 15 个整点刻度");
        assertEquals("08:00", board.hourMarks().get(0));
        assertEquals("22:00", board.hourMarks().get(14));
        assertEquals("08:15", slotAt(board, 0, 1).label());
    }

    @Test
    @DisplayName("区分我的/别人的占用与待审批")
    void distinguishesMineAndOthers() {
        // 明天 10:00 起：我的一笔已通过（index 8），别人一笔已通过（index 9）
        when(reservationSlotMapper.findByDate(TODAY.plusDays(1))).thenReturn(List.of(
                slot(100L, 8), slot(101L, 9)));
        when(reservationMapper.findActiveOnDate(any(), any())).thenReturn(List.of(
                reservation(100L, ME, ReservationStatus.APPROVED.code(),
                        TODAY.plusDays(1).atTime(10, 0), TODAY.plusDays(1).atTime(10, 15)),
                reservation(101L, 8L, ReservationStatus.APPROVED.code(),
                        TODAY.plusDays(1).atTime(10, 15), TODAY.plusDays(1).atTime(10, 30)),
                // 我的待审批盖住 index 10
                reservation(102L, ME, ReservationStatus.PENDING.code(),
                        TODAY.plusDays(1).atTime(10, 30), TODAY.plusDays(1).atTime(10, 45)),
                // 别人的待审批盖住 index 11
                reservation(103L, 9L, ReservationStatus.PENDING.code(),
                        TODAY.plusDays(1).atTime(10, 45), TODAY.plusDays(1).atTime(11, 0))
        ));

        RoomBoardService.Board board = service.build(TODAY.plusDays(1), ME);

        assertEquals("MINE", slotAt(board, 0, 8).state());
        assertFalse(slotAt(board, 0, 8).actionable(), "自己的预约不能再点");

        assertEquals("OCCUPIED", slotAt(board, 0, 9).state());
        assertFalse(slotAt(board, 0, 9).actionable());

        assertEquals("MINE_PENDING", slotAt(board, 0, 10).state());
        assertTrue(slotAt(board, 0, 10).actionable(), "待审批不占单元，仍可选");

        assertEquals("PENDING_ONLY", slotAt(board, 0, 11).state());
        assertTrue(slotAt(board, 0, 11).actionable(), "别人待审批不阻止我再申请");

        assertEquals("FREE", slotAt(board, 0, 12).state());
        assertTrue(slotAt(board, 0, 12).actionable());
    }

    @Test
    @DisplayName("维护中的房间整行不可选")
    void closedRoomIsNotSelectable() {
        RoomBoardService.Board board = service.build(TODAY.plusDays(1), ME);

        RoomBoardService.RoomRow rowB = board.rooms().get(1);
        assertEquals(2L, rowB.roomId());
        for (int i = 0; i < rowB.slots().size(); i++) {
            assertEquals("CLOSED", rowB.slots().get(i).state(), "第 " + i + " 格应为维护中");
            assertFalse(rowB.slots().get(i).actionable());
        }
    }

    @Test
    @DisplayName("今天的已过去单元不可选，正在进行的仍可选")
    void pastSlotsToday() {
        RoomBoardService.Board board = service.build(TODAY, ME);

        // 08:00–08:15 已结束
        assertEquals("PAST", slotAt(board, 0, 0).state());
        assertFalse(slotAt(board, 0, 0).actionable());
        // 08:45–09:00 恰好结束于当前时刻
        assertEquals("PAST", slotAt(board, 0, 3).state());
        // 09:00–09:15 正在进行（结束时刻 > now）
        assertEquals("FREE", slotAt(board, 0, 4).state());
        assertTrue(slotAt(board, 0, 4).actionable());
    }

    @Test
    @DisplayName("超出可预约天数：整板不可约，但仍返回数据供查看")
    void tooFarAhead() {
        LocalDate far = TODAY.plusDays(8);

        RoomBoardService.Board board = service.build(far, ME);

        assertFalse(board.bookable());
        assertEquals("TOO_FAR", slotAt(board, 0, 8).state());
        assertFalse(slotAt(board, 0, 8).actionable());
        assertEquals(56, board.rooms().get(0).slots().size(), "超期也要能看，不能空白页");
    }

    @Test
    @DisplayName("未登录（userId 为空）时别人的占用不会被误判成我的")
    void anonymousSeesOthersAsOccupied() {
        when(reservationSlotMapper.findByDate(TODAY.plusDays(1))).thenReturn(List.of(slot(100L, 8)));
        when(reservationMapper.findActiveOnDate(any(), any())).thenReturn(List.of(
                reservation(100L, ME, ReservationStatus.APPROVED.code(),
                        TODAY.plusDays(1).atTime(10, 0), TODAY.plusDays(1).atTime(10, 15))));

        RoomBoardService.Board board = service.build(TODAY.plusDays(1), null);

        assertEquals("OCCUPIED", slotAt(board, 0, 8).state());
    }

    @Test
    @DisplayName("当天的待审批也按单元展开，跨多个单元都要标出来")
    void pendingSpansMultipleUnits() {
        when(reservationMapper.findActiveOnDate(any(), any())).thenReturn(List.of(
                reservation(200L, ME, ReservationStatus.PENDING.code(),
                        TODAY.plusDays(1).atTime(14, 0), TODAY.plusDays(1).atTime(14, 45))));

        RoomBoardService.Board board = service.build(TODAY.plusDays(1), ME);

        for (int i = 24; i <= 26; i++) {
            assertEquals("MINE_PENDING", slotAt(board, 0, i).state(),
                    "14:00–14:45 应覆盖第 " + i + " 格");
        }
        assertEquals("FREE", slotAt(board, 0, 27).state());
    }
}
