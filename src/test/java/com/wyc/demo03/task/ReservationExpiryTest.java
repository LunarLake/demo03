package com.wyc.demo03.task;

import com.wyc.demo03.mapper.ReservationMapper;
import com.wyc.demo03.service.ReservationService;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReservationExpiryTest {
    @Test
    void everySweepReleasesPendingEvenWithoutUncheckedApprovedReservations() {
        ReservationMapper mapper = mock(ReservationMapper.class);
        ReservationService service = mock(ReservationService.class);
        when(mapper.findExpiredUnchecked(any())).thenReturn(List.of());
        LocalDateTime before = LocalDateTime.now();
        new ReservationScheduler(mapper, service).releaseZombieReservations();
        LocalDateTime after = LocalDateTime.now();
        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(mapper).releaseExpiredPending(now.capture());
        verify(mapper).findExpiredUnchecked(cutoff.capture());
        assertFalse(now.getValue().isBefore(before));
        assertFalse(now.getValue().isAfter(after));
        assertEquals(now.getValue().minusMinutes(15), cutoff.getValue());
        verifyNoInteractions(service);
    }

    @Test
    void pendingReleaseSqlIsConditionalAndRetainsHistory() throws Exception {
        String sql = String.join(" ", ReservationMapper.class
                .getMethod("releaseExpiredPending", LocalDateTime.class).getAnnotation(Update.class).value());
        assertEquals("UPDATE t_reservation SET reservation_status = 5 WHERE reservation_status = 0 AND start_time < #{now}", sql);
    }

    @Test
    void scheduleIncludesBothOccupyingStatusesAndCrossDayIntervals() throws Exception {
        String sql = String.join(" ", ReservationMapper.class
                .getMethod("findByRoomAndDate", Long.class, String.class).getAnnotation(Select.class).value());
        assertTrue(sql.contains("r.reservation_status IN (0, 1)"));
        assertTrue(sql.contains("r.start_time < DATE_ADD(#{date}, INTERVAL 1 DAY)"));
        assertTrue(sql.contains("r.end_time > #{date}"));
        assertTrue(sql.contains("r.reservation_status,"));
    }
}
