package com.wyc.demo03.service.impl;

import com.wyc.demo03.common.SlotConflictException;
import com.wyc.demo03.domain.SlotSchema;
import com.wyc.demo03.domain.SlotUnit;
import com.wyc.demo03.entity.ReservationSlot;
import com.wyc.demo03.mapper.ReservationSlotMapper;
import com.wyc.demo03.service.SlotService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SlotServiceImpl implements SlotService {

    private final ReservationSlotMapper slotMapper;
    private final SlotSchema slotSchema;

    @Override
    public List<SlotUnit> unitsOf(LocalDateTime start, LocalDateTime end) {
        return slotSchema.split(start, end);
    }

    @Override
    public List<ReservationSlot> findOccupied(Long roomId, List<SlotUnit> units) {
        if (units == null || units.isEmpty()) {
            return List.of();
        }
        LocalDate date = units.get(0).date();
        int from = units.get(0).index();
        int to = from;
        Set<Integer> wanted = new HashSet<>();
        for (SlotUnit unit : units) {
            wanted.add(unit.index());
            from = Math.min(from, unit.index());
            to = Math.max(to, unit.index());
        }
        List<ReservationSlot> inRange = slotMapper.findInRange(roomId, date, from, to + 1);
        List<ReservationSlot> hit = new ArrayList<>();
        for (ReservationSlot slot : inRange) {
            if (wanted.contains(slot.getSlotIndex())) {
                hit.add(slot);
            }
        }
        return hit;
    }

    @Override
    public void occupy(Long reservationId, Long roomId, List<SlotUnit> units) {
        if (units == null || units.isEmpty()) {
            return;
        }
        List<ReservationSlot> rows = new ArrayList<>(units.size());
        for (SlotUnit unit : units) {
            rows.add(ReservationSlot.of(reservationId, roomId, unit.date(), unit.index()));
        }
        try {
            // 单条多值 INSERT：语句级原子，撞唯一键时整条回滚，不留半个占用
            int inserted = slotMapper.insertUnits(rows);
            if (inserted != rows.size()) {
                throw new IllegalStateException(
                        "占用写入行数异常：期望 " + rows.size() + "，实际 " + inserted);
            }
        } catch (DuplicateKeyException e) {
            // 失败语句已被数据库回滚，此刻查到的占用方是"别人"，可以用来生成可读提示
            List<ReservationSlot> taken = findOccupied(roomId, units);
            throw new SlotConflictException(taken);
        }
    }

    @Override
    public void release(Long reservationId) {
        if (reservationId != null) {
            slotMapper.deleteByReservationId(reservationId);
        }
    }

    @Override
    public Set<Integer> occupiedIndexes(Long roomId, LocalDate date) {
        Set<Integer> indexes = new HashSet<>();
        for (ReservationSlot slot : slotMapper.findByRoomAndDate(roomId, date)) {
            indexes.add(slot.getSlotIndex());
        }
        return indexes;
    }

    @Override
    public Map<Long, Set<Integer>> occupiedByRoom(LocalDate date) {
        Map<Long, Set<Integer>> map = new HashMap<>();
        for (ReservationSlot slot : slotMapper.findByDate(date)) {
            map.computeIfAbsent(slot.getRoomId(), k -> new HashSet<>()).add(slot.getSlotIndex());
        }
        return map;
    }
}
