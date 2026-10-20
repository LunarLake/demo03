package com.wyc.demo03.service;

import com.wyc.demo03.entity.AccessLog;
import com.wyc.demo03.entity.Reservation;

import java.util.List;

/**
 * 开门授权（文档 3.2.3）。
 *
 * <p>两条入口：
 * <ul>
 *   <li>{@link #openAfterCheckIn} —— 首次签到成功后自动下发开锁指令；</li>
 *   <li>{@link #reopen} —— 已签到预约在有效期内再次认证开门。</li>
 * </ul>
 *
 * <p><b>无论授权还是拒绝，都要写 {@code t_access_log}</b>。拒绝是排查"为什么门没开"
 * 的唯一线索，不记录等于把现场问题变成玄学。
 *
 * <p>永远不抛异常表示"设备失败"：设备离线、超时都是正常结果，通过返回值表达。
 */
public interface AccessService {

    /**
     * 开门结果。
     *
     * @param granted       是否已授权下发
     * @param denyReason    拒绝原因码（granted=false 时有值）
     * @param message       面向用户的说明
     * @param msgId         指令唯一标识（granted 时有值）
     * @param commandStatus 指令状态：ACKED（设备已回执）/ SENT（已下发待回执）
     */
    record UnlockResult(boolean granted, String denyReason, String message,
                        String msgId, String commandStatus) {

        public static UnlockResult deny(String reason, String message) {
            return new UnlockResult(false, reason, message, null, null);
        }

        public static UnlockResult granted(String msgId, String status, String message) {
            return new UnlockResult(true, null, message, msgId, status);
        }

        /** 设备是否已确认开锁（false 表示指令已发出但还没回执）。 */
        public boolean acknowledged() {
            return granted && "ACKED".equals(commandStatus);
        }
    }

    /**
     * 已签到预约再次开门。
     *
     * <p>每次都会重新校验：当前账号、预约归属、状态（必须已签到）、有效时段。
     * 不允许只凭预约号开门 —— 预约号是可枚举的。
     */
    UnlockResult reopen(Long reservationId, Long userId, String ip);

    /**
     * 首次签到成功后的自动开门。
     *
     * <p><b>必须由控制器在签到事务提交之后调用</b>（BUG-004）：如果放在签到事务内，
     * 设备指令可能已经执行而签到随后回滚，出现"门开了但系统里没签到"。
     * 因此这里只接收预约 id，实现方自行读取已提交的数据。
     *
     * <p>不重复校验签到码（刚刚校验过），但仍校验归属与时段。
     */
    UnlockResult openAfterCheckIn(Long reservationId, Long userId, String ip);

    /** 某预约的开门记录，最新的在前。 */
    List<AccessLog> recentLogs(Long reservationId);
}
