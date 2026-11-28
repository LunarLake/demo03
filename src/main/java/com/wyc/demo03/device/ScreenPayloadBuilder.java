package com.wyc.demo03.device;

import com.wyc.demo03.config.SlotProperties;
import com.wyc.demo03.domain.ReservationStatus;
import com.wyc.demo03.entity.CheckinCode;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.service.CheckinCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 门禁屏幕"该显示什么"的唯一定义处。
 *
 * <p>真机（MQTT）与无设备时的日志模拟屏共用这一份，避免两条链路口径不同 ——
 * 模拟实现和真机不一致，等于没模拟（同 BUG-002 的教训）。
 *
 * <p><b>签到码只在「已通过 + 处于签到窗口」时出现。</b>
 * 屏幕是现场凭证：不在窗口内就不显示，路过的人提前看到也没用。
 */
@Component
@RequiredArgsConstructor
public class ScreenPayloadBuilder {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final CheckinCodeService checkinCodeService;
    private final SlotProperties slotProperties;
    private final Clock clock;

    /**
     * @param reservation 屏幕要展示的预约
     * @param now         当前时刻，用于计算剩余时间与是否进入签到窗口
     */
    public String build(Reservation reservation, LocalDateTime now) {
        ReservationStatus status = reservation.statusOrNull();
        StringBuilder sb = new StringBuilder();
        sb.append("reservation=").append(reservation.getId());
        sb.append(";status=").append(status == null ? "UNKNOWN" : status.name());
        if (reservation.getStartTime() != null) {
            sb.append(";startsAt=").append(reservation.getStartTime().format(ISO));
        }
        if (reservation.getEndTime() != null) {
            sb.append(";endsAt=").append(reservation.getEndTime().format(ISO));
        }
        sb.append(";remain=").append(remainText(reservation.getEndTime()));

        if (status == ReservationStatus.APPROVED && inCheckinWindow(reservation, now)) {
            CheckinCode code = checkinCodeService.findByReservationId(reservation.getId());
            if (code != null && code.isActive()) {
                sb.append(";code=").append(code.getCode());
            }
        }
        return sb.toString();
    }

    /** 签到窗口 {@code [开始, min(开始+窗口, 结束))}：开始包含、截止不包含。 */
    public boolean inCheckinWindow(Reservation r, LocalDateTime now) {
        LocalDateTime start = r.getStartTime();
        if (start == null || now.isBefore(start)) {
            return false;
        }
        LocalDateTime end = start.plusMinutes(slotProperties.getCheckinWindowMinutes());
        if (r.getEndTime() != null && end.isAfter(r.getEndTime())) {
            end = r.getEndTime();
        }
        return now.isBefore(end);
    }

    /** 剩余时间 {@code mm:ss}；已结束则 00:00。 */
    private String remainText(LocalDateTime end) {
        if (end == null) {
            return "00:00";
        }
        long seconds = Duration.between(LocalDateTime.now(clock), end).getSeconds();
        if (seconds <= 0) {
            return "00:00";
        }
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }
}
