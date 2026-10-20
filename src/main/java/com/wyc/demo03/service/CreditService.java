package com.wyc.demo03.service;

import com.wyc.demo03.entity.CreditAccount;
import com.wyc.demo03.entity.CreditLog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 信用分。
 *
 * <p>账本优先：{@code t_credit_log} 是唯一事实来源，{@code t_credit_account.score}
 * 只是 {@code clamp(100 + SUM(delta))} 的缓存。任何加分扣分都必须先写流水再重算分数，
 * 否则一旦两步之间失败，分数与流水就对不上，申诉时无法自证。
 */
public interface CreditService {

    /** 当前信用分；无账户时按初始 100 分处理并补建。 */
    int scoreOf(Long userId);

    /** 账户视图（分数 + 更新时间）。 */
    CreditAccount accountOf(Long userId);

    /** 最近流水（含预约时间与房间名），供信用页展示。 */
    List<Map<String, Object>> recentLogs(Long userId, int limit);

    /**
     * 记一笔信用变动。
     *
     * <p>负向原因若在 30 日内已发生过，本次自动加倍，并额外写一条 {@code REPEAT_DOUBLE}
     * 注记行（delta=0），便于审计时解释"为什么这次扣了 20 而不是 10"。
     *
     * @param deltaOverride 为空时用原因的默认分值；申诉撤销时传原流水的相反数
     * @return 是否真的写入；同一预约同一原因重复调用返回 {@code false}（被唯一键挡住）
     */
    boolean adjust(Long userId, CreditLog.Reason reason, Integer deltaOverride,
                   Long reservationId, Long appealId, String operator, String note);

    /**
     * 30 日内无爽约的用户加 5 分。
     *
     * <p>幂等依据是"30 天内已有一条 CLEAN_30D 流水则跳过"，因此每天跑也只会 30 天加一次。
     *
     * @return 本次加分的人数
     */
    int awardCleanRecord(LocalDateTime now);
}
