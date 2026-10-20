package com.wyc.demo03.task;

import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.mapper.ReservationMapper;
import com.wyc.demo03.service.ReservationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class ReservationScheduler {

    private final ReservationMapper reservationMapper;
    private final ReservationService reservationService;

    public ReservationScheduler(ReservationMapper reservationMapper, ReservationService reservationService) {
        this.reservationMapper = reservationMapper;
        this.reservationService = reservationService;
    }

    @Scheduled(fixedDelay = 60_000)
    public void releaseZombieReservations() {
        LocalDateTime now = LocalDateTime.now();
        // 待审批申请过了开始时间即失效；条件更新不会覆盖已经处理的申请。
        reservationMapper.releaseExpiredPending(now);
        LocalDateTime cutoff = now.minusMinutes(15);
        List<Reservation> zombies = reservationMapper.findExpiredUnchecked(cutoff);
        for (Reservation r : zombies) {
            reservationService.lambdaUpdate()
                    .eq(Reservation::getId, r.getId())
                    .eq(Reservation::getReservationStatus, 1)
                    .set(Reservation::getReservationStatus, 5)
                    .update();
        }
    }
}
