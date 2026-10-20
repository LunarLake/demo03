package com.wyc.demo03.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 预约状态机（v2 七态）。
 *
 * <p>取代 v1 中散落在 Service 里的字面量 0/1/2/3/4/5。所有状态判断、展示文案、迁移合法性
 * 都应当来自这里，不允许在业务代码里再写魔法数字。
 *
 * <h3>为什么新增状态用 6/7 而不是 4/6</h3>
 * v1 中 {@code 4} 表示「用户取消」。如果让 {@code 4} 复用为「已签到」，那么任何漏迁移的旧数据、
 * 任何未及时更新的 SQL，都会被读成「已签到」，并因此被授予开锁权限 —— 这是安全方向的错误。
 * 取用从未出现过的 {@code 6}/{@code 7}，旧代码读到新值必然落入 default 分支（拒绝），失败安全。
 * 旧值 {@code 4} 在迁移中已全部改写为 {@code 3}，见 {@code sql/migrate-v1-to-v2.sql}。
 *
 * <h3>状态迁移表</h3>
 * <pre>
 * 待审批(0) ──基础规则不通过──→ 已拒绝(2)
 *           ├─自动通过/人工批准─→ 已通过(1)
 *           ├─用户取消──────────→ 已取消(3)
 *           ├─教师覆盖──────────→ 已取消(3)
 *           └─开始时刻已过──────→ 超时释放(5)
 *
 * 已通过(1) ──首次签到成功────→ 已签到(6)
 *           ├─签到截止未签到────→ 超时释放(5)
 *           ├─用户取消──────────→ 已取消(3)
 *           ├─教师覆盖(未开始)──→ 已取消(3)
 *           └─到达结束时刻──────→ 已结束(7)
 *
 * 已签到(6) ──到达结束时刻────→ 已结束(7)
 *
 * 已拒绝(2) / 已取消(3) / 超时释放(5) / 已结束(7) 为终止态，不接受任何迁移。
 * </pre>
 */
public enum ReservationStatus {

    /** 0 — 待审批。★ 不占单元，同一单元可以有多笔并存。 */
    PENDING(0, "待审批"),

    /** 1 — 已通过。占单元，等待首次签到。 */
    APPROVED(1, "已通过"),

    /** 2 — 已拒绝。终止态。 */
    REJECTED(2, "已拒绝"),

    /** 3 — 已取消。终止态。来源由 {@code cancel_source} 区分（用户/教师覆盖/管理员）。 */
    CANCELED(3, "已取消"),

    /** 5 — 超时释放。终止态。签到截止（开始后 5 分钟）仍未签到，或待审批到了开始时刻仍未处理。 */
    TIMEOUT_RELEASED(5, "超时释放"),

    /** 6 — 已签到。占单元，有效期内可再次开门。 */
    CHECKED_IN(6, "已签到"),

    /** 7 — 已结束。终止态。到达预约结束时刻，授权已撤销。 */
    FINISHED(7, "已结束");

    /**
     * v1 遗留码：用户取消。已合并进 {@link #CANCELED}。
     * 保留常量是为了让迁移校验与错误信息能明确指出"这个值不该再出现"。
     */
    public static final int RETIRED_USER_CANCEL_CODE = 4;

    private static final Map<Integer, ReservationStatus> BY_CODE;

    /** 终止态集合：进入后不接受任何迁移。 */
    private static final Set<ReservationStatus> TERMINAL =
            Collections.unmodifiableSet(EnumSet.of(REJECTED, CANCELED, TIMEOUT_RELEASED, FINISHED));

    /** 进行中（未终止）的状态：待审批、已通过、已签到。 */
    private static final Set<ReservationStatus> ACTIVE =
            Collections.unmodifiableSet(EnumSet.of(PENDING, APPROVED, CHECKED_IN));

    private static final Map<ReservationStatus, Set<ReservationStatus>> ALLOWED;

    static {
        Map<Integer, ReservationStatus> byCode = new HashMap<>();
        for (ReservationStatus s : values()) {
            byCode.put(s.code, s);
        }
        BY_CODE = Collections.unmodifiableMap(byCode);

        Map<ReservationStatus, Set<ReservationStatus>> allowed = new EnumMap<>(ReservationStatus.class);
        allowed.put(PENDING, EnumSet.of(APPROVED, REJECTED, CANCELED, TIMEOUT_RELEASED));
        allowed.put(APPROVED, EnumSet.of(CHECKED_IN, TIMEOUT_RELEASED, CANCELED, FINISHED));
        allowed.put(CHECKED_IN, EnumSet.of(FINISHED));
        allowed.put(REJECTED, EnumSet.noneOf(ReservationStatus.class));
        allowed.put(CANCELED, EnumSet.noneOf(ReservationStatus.class));
        allowed.put(TIMEOUT_RELEASED, EnumSet.noneOf(ReservationStatus.class));
        allowed.put(FINISHED, EnumSet.noneOf(ReservationStatus.class));
        ALLOWED = Collections.unmodifiableMap(allowed);
    }

    private final int code;
    private final String label;

    ReservationStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    /** 中文展示名，用于页面徽章与日志。 */
    public String label() {
        return label;
    }

    /**
     * 由数据库状态码取枚举。
     *
     * @throws IllegalArgumentException 码非法（含已废弃的 4）
     */
    public static ReservationStatus of(int code) {
        ReservationStatus status = BY_CODE.get(code);
        if (status == null) {
            if (code == RETIRED_USER_CANCEL_CODE) {
                throw new IllegalArgumentException(
                        "预约状态码 4（v1 的\"用户取消\"）已废弃，迁移后应改写为 3（已取消）；"
                                + "请执行 sql/migrate-v1-to-v2.sql 修正数据");
            }
            throw new IllegalArgumentException("未知的预约状态码：" + code);
        }
        return status;
    }

    /**
     * 宽松版本：未知码返回 {@code null} 而不是抛异常。
     * 仅用于"读取历史数据做展示"这类不能因脏数据中断整个页面的场景；
     * 任何改变状态的写路径都必须用 {@link #of(int)}。
     */
    public static ReservationStatus ofOrNull(Integer code) {
        return code == null ? null : BY_CODE.get(code);
    }

    /** 是否终止态（不再接受任何迁移）。 */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** 是否进行中（未终止）。 */
    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    /**
     * 进行中状态的码集合，供 "IN (...)" 类查询使用。
     *
     * <p>把 v1 里散落的 {@code IN (0, 1)} 收口到这里：v2 新增了「已签到」，
     * 任何仍写死 {@code (0, 1)} 的地方都会漏掉已签到的预约 —— 例如房间删除守卫。
     */
    public static Set<Integer> activeCodes() {
        return ACTIVE.stream().map(ReservationStatus::code).collect(java.util.stream.Collectors.toSet());
    }

    /**
     * 是否占用房间单元。
     * 只有「已通过」与「已签到」占单元；「待审批」不占（文档 3.2.1）。
     */
    public boolean occupiesSlot() {
        return this == APPROVED || this == CHECKED_IN;
    }

    /** 是否允许迁移到目标状态。 */
    public boolean canTransitTo(ReservationStatus target) {
        if (target == null) {
            return false;
        }
        return ALLOWED.get(this).contains(target);
    }

    /** 当前状态的全部合法后继，供测试与调试使用。 */
    public Set<ReservationStatus> allowedTargets() {
        return ALLOWED.get(this);
    }

    @Override
    public String toString() {
        return name() + "(" + code + "," + label + ")";
    }
}
