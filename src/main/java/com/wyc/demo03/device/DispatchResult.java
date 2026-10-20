package com.wyc.demo03.device;

/**
 * 指令投递结果。
 *
 * <p>区分"设备已确认"与"只是发出去了"很重要：
 * <ul>
 *   <li>{@link #ACKED} —— 同步确认（模拟器/本地测试路径），指令可直接结算为 ACKED；</li>
 *   <li>{@link #SENT} —— 已投递到 Broker，等设备回执；指令保持 SENT，回执到达后才结算；</li>
 *   <li>{@link #FAILED} —— 没发出去（未连接/异常）；指令保持 SENT 等过期，不会被设备执行。</li>
 * </ul>
 *
 * <p>早期版本用 boolean 表示，结果 MQTT 路径把"已发布"当成"已开锁"直接结算了 ACKED，
 * 掩盖了"设备根本没回执"的事实。这个枚举就是为了让这个区别无法被忽略。
 */
public enum DispatchResult {
    ACKED,
    SENT,
    FAILED
}
