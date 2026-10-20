package com.wyc.demo03.service;

import com.wyc.demo03.domain.ReservationStatus;
import com.wyc.demo03.domain.SlotSchema;
import com.wyc.demo03.domain.SlotUnit;
import com.wyc.demo03.entity.MeetingRoom;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.entity.ReservationSlot;
import com.wyc.demo03.mapper.MeetingRoomMapper;
import com.wyc.demo03.mapper.ReservationMapper;
import com.wyc.demo03.mapper.ReservationSlotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 排期板聚合（计划 7.2 / P6-T01）。
 *
 * <p>一次请求返回"某一天所有房间 × 所有单元"的状态，前端只负责渲染，不再逐房间轮询。
 * 这样 2 个房间 112 格只需要一次查询，且各格状态在同一时刻快照下计算，不会出现
 * "左边房间显示空闲、右边显示占用"这种自相矛盾的画面。
 *
 * <h3>状态优先级（从高到低）</h3>
 * <pre>
 * CLOSED       房间维护中 —— 整行都不可选
 * PAST         该格的结束时刻已过 —— 不可选
 * MINE         我的有效预约占着
 * OCCUPIED     别人的有效预约占着
 * MINE_PENDING 我的待审批盖着（可以再约，但要提示）
 * PENDING_ONLY 别人的待审批盖着（可以约，但要提示可能被拒）
 * TOO_FAR      超出可提前预约天数
 * FREE         可选
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class RoomBoardService {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** 单元状态。前端按这个字符串选样式，不要在前后端各维护一套枚举。 */
    public enum SlotState {
        FREE, OCCUPIED, PENDING_ONLY, MINE, MINE_PENDING, PAST, CLOSED, TOO_FAR
    }

    public record Slot(int index, String label, String state, boolean actionable, String hint) {
    }

    public record RoomRow(Long roomId, String roomName, Integer capacity, int roomStatus,
                          List<Slot> slots) {
    }

    public record Board(String date, String openTime, String closeTime, int unitMinutes,
                        int slotsPerDay, boolean bookable, List<String> hourMarks,
                        List<RoomRow> rooms) {
    }

    private final MeetingRoomMapper meetingRoomMapper;
    private final ReservationMapper reservationMapper;
    private final ReservationSlotMapper reservationSlotMapper;
    private final SlotSchema slotSchema;
    private final Clock clock;

    /**
     * 构建某日的排期板。
     *
     * @param date 日期；null 表示今天
     * @param userId 当前用户，用于区分"我的"与"别人的"
     */
    public Board build(LocalDate date, Long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate day = date == null ? now.toLocalDate() : date;

        boolean tooFar = day.isAfter(now.toLocalDate().plusDays(slotSchema.maxAdvanceDays()));
        boolean bookable = !tooFar;

        // 1) 有效占用：roomId → (index → reservationId)
        Map<Long, Map<Integer, Long>> occupied = new HashMap<>();
        for (ReservationSlot slot : reservationSlotMapper.findByDate(day)) {
            occupied.computeIfAbsent(slot.getRoomId(), k -> new HashMap<>())
                    .put(slot.getSlotIndex(), slot.getReservationId());
        }

        // 2) 当天进行中的预约（含待审批），用于区分我的/别人的
        List<Reservation> active = reservationMapper.findActiveOnDate(
                day.atStartOfDay(), day.plusDays(1).atStartOfDay());
        Map<Long, Reservation> byId = new HashMap<>();
        Map<Long, Set<Integer>> minePending = new HashMap<>();
        Map<Long, Set<Integer>> othersPending = new HashMap<>();
        for (Reservation r : active) {
            byId.put(r.getId(), r);
            ReservationStatus status = r.statusOrNull();
            if (status != ReservationStatus.PENDING) {
                continue;
            }
            boolean mine = userId != null && userId.equals(r.getUserId());
            Map<Long, Set<Integer>> target = mine ? minePending : othersPending;
            for (SlotUnit unit : slotSchema.split(r.getStartTime(), r.getEndTime())) {
                target.computeIfAbsent(r.getRoomId(), k -> new HashSet<>()).add(unit.index());
            }
        }

        // 3) 逐房间逐格判定
        // 复制一份再排序：无论 mapper 返回的是可变 List 还是不可变 List，都不能原地改别人的集合
        List<MeetingRoom> rooms = new ArrayList<>(meetingRoomMapper.selectList(null));
        rooms.sort(Comparator.comparing(MeetingRoom::getId));

        List<RoomRow> rows = new ArrayList<>(rooms.size());
        for (MeetingRoom room : rooms) {
            boolean closed = Integer.valueOf(1).equals(room.getRoomStatus());
            Map<Integer, Long> roomOccupied = occupied.getOrDefault(room.getId(), Map.of());
            Set<Integer> roomMinePending = minePending.getOrDefault(room.getId(), Set.of());
            Set<Integer> roomOthersPending = othersPending.getOrDefault(room.getId(), Set.of());

            List<Slot> slots = new ArrayList<>(slotSchema.slotsPerDay());
            for (int i = 0; i < slotSchema.slotsPerDay(); i++) {
                LocalDateTime start = slotSchema.startOf(day, i);
                LocalDateTime end = slotSchema.endOf(day, i);
                slots.add(judge(i, start, end, now, closed, tooFar, userId,
                        roomOccupied, byId, roomMinePending, roomOthersPending));
            }
            rows.add(new RoomRow(room.getId(), room.getRoomName(), room.getCapacity(),
                    room.getRoomStatus() == null ? 0 : room.getRoomStatus(), slots));
        }

        return new Board(day.toString(), slotSchema.openLabel(), slotSchema.closeLabel(),
                slotSchema.unitMinutes(), slotSchema.slotsPerDay(), bookable,
                hourMarks(day), rows);
    }

    // ====================================================================

    private Slot judge(int index, LocalDateTime start, LocalDateTime end, LocalDateTime now,
                       boolean closed, boolean tooFar, Long userId,
                       Map<Integer, Long> occupied, Map<Long, Reservation> byId,
                       Set<Integer> minePending, Set<Integer> othersPending) {
        String label = start.toLocalTime().format(HH_MM);

        if (closed) {
            return new Slot(index, label, SlotState.CLOSED.name(), false, "房间维护中");
        }
        // 可选性必须与后端提交校验同一契约：SlotSchema 拒绝 start < now。
        // 因此这里用**开始时刻**判断，而不是结束时刻 —— 否则 10:01 时 10:00–10:15 会显示可选，
        // 用户点了却收到"不能预约过去的时间"。开始时刻恰好等于 now 时后端允许，这里也保持可选。
        if (start.isBefore(now)) {
            return new Slot(index, label, SlotState.PAST.name(), false,
                    "该时段已开始，无法预约");
        }
        Long reservationId = occupied.get(index);
        if (reservationId != null) {
            Reservation r = byId.get(reservationId);
            boolean mine = userId != null && r != null && userId.equals(r.getUserId());
            return mine
                    ? new Slot(index, label, SlotState.MINE.name(), false, "我的预约")
                    : new Slot(index, label, SlotState.OCCUPIED.name(), false, null);
        }
        if (minePending.contains(index)) {
            return new Slot(index, label, SlotState.MINE_PENDING.name(), true, "我的申请待审批");
        }
        if (othersPending.contains(index)) {
            return new Slot(index, label, SlotState.PENDING_ONLY.name(), true, "有人正在申请");
        }
        if (tooFar) {
            return new Slot(index, label, SlotState.TOO_FAR.name(), false, "超出可预约天数");
        }
        return new Slot(index, label, SlotState.FREE.name(), true, null);
    }

    /** 整点刻度，前端用来画时间轴（每 4 个单元一个）。 */
    private List<String> hourMarks(LocalDate day) {
        List<String> marks = new ArrayList<>();
        LocalTime time = slotSchema.openTime();
        while (time.isBefore(slotSchema.closeTime())) {
            marks.add(time.format(HH_MM));
            time = time.plusHours(1);
        }
        marks.add(slotSchema.closeLabel());
        return marks;
    }
}
