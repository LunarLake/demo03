package com.wyc.demo03.device;

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
 *
 * <h3>为什么推送入口是"房间"而不是"预约"</h3>
 * 屏幕显示的是<b>房间当前这一时刻</b>的状态，不是"刚刚发生的那笔事件"（BUG-002）。
 * 若按事件推送：A 正在使用房间时批准了三天后的 B，屏幕会立刻绑到 B，
 * A 再次开门会因为"指令所属预约与屏幕显示的预约不一致"被设备拒为 {@code ERR_BIND}；
 * 未来预约的签到码也会提前暴露在屏幕上。
 * 因此接口只接受 {@code roomId}，由实现按当前时刻自行重算该显示什么。
 */
public interface DeviceNotifier {

    /**
     * 按房间当前时刻重算并推送屏幕快照。失败只记日志，**不得**影响业务事务。
     *
     * @param roomId 房间；{@code null} 时什么都不做
     */
    void refresh(Long roomId);

    /**
     * 刷新所有已绑定房间的设备的屏幕。
     *
     * <p>由状态收敛任务每分钟调用一次 —— 预约窗口的切换（上一场结束、下一场开始、
     * 进入/离开签到窗口）不是任何一笔业务事件，没有它屏幕永远不会自动翻页。
     */
    void refreshAll();

    /**
     * 结束前提醒。
     *
     * @return 是否已下发
     */
    boolean remind(Reservation reservation);
}
