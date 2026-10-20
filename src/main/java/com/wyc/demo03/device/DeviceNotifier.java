package com.wyc.demo03.device;

import com.wyc.demo03.domain.ReservationStatus;
import com.wyc.demo03.entity.Reservation;

/**
 * 设备侧通知：屏幕快照与结束提醒。
 *
 * <p>与 {@link DoorController} 分开是因为语义不同：
 * 开门是**一次性动作**（授权 → 执行 → 回执），屏幕快照是**状态同步**（retained，随时可被覆盖）。
 * 把两者塞进同一个接口，会逼着每个实现都去关心自己不擅长的部分。
 *
 * <p>由 {@code app.door.mode} 决定实现：{@code log} 用只打日志的版本，
 * {@code mqtt} 用真正发布 {@code room/{code}/snapshot} 的版本。
 */
public interface DeviceNotifier {

    /**
     * 推送屏幕快照。失败只记日志，**不得**影响业务事务。
     *
     * @param reservation 预约；{@code null} 表示清屏
     * @param status      当前状态，用于决定屏幕显示"待签到/已签到/空闲"
     */
    void snapshot(Reservation reservation, ReservationStatus status);

    /**
     * 结束前提醒。
     *
     * @return 是否已下发
     */
    boolean remind(Reservation reservation);
}
