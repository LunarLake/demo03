package com.wyc.demo03.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 可拨动的测试时钟。
 *
 * <p>签到截止、结束提醒、超时释放这些判定都依赖"现在几点"。用固定时钟只能测一个瞬间，
 * 用真实时钟又要等。这个实现允许测试把时间精确拨到 {@code 开始后 4:59} 与 {@code 5:00}，
 * 逐秒验证边界。
 */
public final class MutableClock extends Clock {

    private final ZoneId zone;
    private Instant instant;

    public MutableClock(LocalDateTime initial, ZoneId zone) {
        this.zone = zone;
        this.instant = initial.atZone(zone).toInstant();
    }

    public static MutableClock at(LocalDateTime time) {
        return new MutableClock(time, ZoneId.systemDefault());
    }

    /** 直接跳到某个时刻。 */
    public void set(LocalDateTime time) {
        this.instant = time.atZone(zone).toInstant();
    }

    /** 在当前时刻上前进若干分钟。 */
    public void plusMinutes(long minutes) {
        this.instant = this.instant.plusSeconds(minutes * 60);
    }

    public LocalDateTime now() {
        return LocalDateTime.ofInstant(instant, zone);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(now(), newZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
