package com.wyc.demo03.task;

import com.wyc.demo03.device.DeviceNotifier;
import com.wyc.demo03.entity.Reservation;
import com.wyc.demo03.service.CreditService;
import com.wyc.demo03.service.DeviceCommandService;
import com.wyc.demo03.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 预约状态收敛（每 60 秒一次，文档 3.2.4）。
 *
 * <p>四件事按"先释放、再结束、最后提醒"的顺序做，避免同一笔预约在同一轮里
 * 既被判爽约又被判结束：
 * <ol>
 *   <li>待审批到了开始时刻仍未处理 → 超时释放</li>
 *   <li>已通过但签到截止（开始后 5 分钟）仍未签到 → 超时释放</li>
 *   <li>到达结束时刻 → 已结束</li>
 *   <li>结束前 5 分钟 → 登记提醒（P5 接设备后在此下发屏幕/蜂鸣）</li>
 * </ol>
 *
 * <p>扫描存在最多一个周期（约 60 秒）的正常延迟。因此<b>授权判定不依赖定时任务是否已跑</b>：
 * 签到与开门都用当前时间直接校验窗口，状态只是"展示用的事实"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationScheduler {

    private final ReservationService reservationService;
    private final CreditService creditService;
    private final DeviceCommandService deviceCommandService;
    private final DeviceNotifier deviceNotifier;
    private final Clock clock;

    @Scheduled(fixedDelay = 60_000)
    public void converge() {
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            int pending = reservationService.releasePendingStarted(now);
            int noShow = reservationService.releaseNoShow(now);
            int finished = reservationService.finishExpired(now);
            List<Reservation> reminded = reservationService.remindEnding(now);
            int pushed = pushReminders(reminded);
            // 超时未回执的开门指令置 EXPIRED：过期指令不得再被执行
            int expired = deviceCommandService.expireOverdue(now);

            if (pending > 0 || noShow > 0 || finished > 0 || !reminded.isEmpty() || expired > 0) {
                log.info("预约状态收敛：待审批释放 {}，爽约释放 {}，到期结束 {}，提醒 {}（已下发 {}），指令过期 {}",
                        pending, noShow, finished, reminded.size(), pushed, expired);
            }
        } catch (RuntimeException e) {
            // 单轮失败不能终止调度：把异常吞掉并记录，下一轮会重试（所有操作都是幂等的）
            log.error("预约状态收敛失败，将在下一轮重试", e);
        }
    }

    /**
     * 结束前提醒下发到设备。
     *
     * <p>提醒是"锦上添花"，某一台下发失败不能影响其它台，也不能让整轮收敛失败 ——
     * 因此逐条 try/catch，失败只记 warn。
     */
    private int pushReminders(List<Reservation> reservations) {
        int pushed = 0;
        for (Reservation r : reservations) {
            try {
                if (deviceNotifier.remind(r)) {
                    pushed++;
                }
            } catch (RuntimeException e) {
                log.warn("结束提醒下发失败：reservation={}", r.getId(), e);
            }
        }
        return pushed;
    }

    /**
     * 每日 03:10 给"30 日内无爽约"的用户加 5 分。
     *
     * <p>单独一个调度而不是塞进每分钟的收敛：加分是低频动作，
     * 每分钟扫一遍全体用户没有意义。幂等由信用流水自身保证（30 天内已有加分则跳过）。
     */
    @Scheduled(cron = "0 10 3 * * *")
    public void awardCleanRecords() {
        try {
            int awarded = creditService.awardCleanRecord(LocalDateTime.now(clock));
            if (awarded > 0) {
                log.info("30 日无爽约加分：{} 人", awarded);
            }
        } catch (RuntimeException e) {
            log.error("30 日无爽约加分失败，明日重试", e);
        }
    }
}
