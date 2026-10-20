package com.wyc.demo03.service.impl;

import com.wyc.demo03.entity.CreditAccount;
import com.wyc.demo03.entity.CreditLog;
import com.wyc.demo03.mapper.CreditAccountMapper;
import com.wyc.demo03.mapper.CreditLogMapper;
import com.wyc.demo03.mapper.ReservationMapper;
import com.wyc.demo03.service.CreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreditServiceImpl implements CreditService {

    /** 30 日窗口：再犯加倍与"无爽约"加分都基于它。 */
    private static final int WINDOW_DAYS = 30;

    private final CreditAccountMapper creditAccountMapper;
    private final CreditLogMapper creditLogMapper;
    private final ReservationMapper reservationMapper;
    private final Clock clock;

    @Override
    public int scoreOf(Long userId) {
        if (userId == null) {
            return CreditAccount.INITIAL_SCORE;
        }
        CreditAccount account = creditAccountMapper.selectById(userId);
        if (account != null && account.getScore() != null) {
            return account.getScore();
        }
        ensureAccount(userId);
        return CreditAccount.INITIAL_SCORE;
    }

    @Override
    public CreditAccount accountOf(Long userId) {
        CreditAccount account = userId == null ? null : creditAccountMapper.selectById(userId);
        if (account != null) {
            return account;
        }
        ensureAccount(userId);
        CreditAccount fresh = new CreditAccount();
        fresh.setUserId(userId);
        fresh.setScore(CreditAccount.INITIAL_SCORE);
        fresh.setUpdatedAt(LocalDateTime.now(clock));
        return fresh;
    }

    @Override
    public List<Map<String, Object>> recentLogs(Long userId, int limit) {
        return creditLogMapper.findPage(userId, 0, Math.max(1, Math.min(limit, 200)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean adjust(Long userId, CreditLog.Reason reason, Integer deltaOverride,
                          Long reservationId, Long appealId, String operator, String note) {
        if (userId == null || reason == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        int delta = deltaOverride != null ? deltaOverride : reason.defaultDelta();

        // 30 日内同类再犯：加倍，并留一条注记说明加倍原因
        boolean doubled = false;
        if (reason.negative() && delta < 0) {
            int prior = creditLogMapper.countSince(userId, reason.name(), now.minusDays(WINDOW_DAYS));
            if (prior > 0) {
                delta = delta * 2;
                doubled = true;
            }
        }

        CreditLog entry = new CreditLog();
        entry.setUserId(userId);
        entry.setDelta(delta);
        entry.setReasonCode(reason.name());
        entry.setReservationId(reservationId);
        entry.setAppealId(appealId);
        entry.setOperator(operator == null || operator.isBlank() ? "SYSTEM" : operator);
        entry.setNote(note);
        entry.setCreatedAt(now);

        try {
            creditLogMapper.insert(entry);
        } catch (DuplicateKeyException e) {
            // 同一预约同一原因只能记一次（定时任务重复扫描、并发释放都靠这里兜底）
            log.debug("信用流水重复，已忽略：user={} reason={} reservation={}",
                    userId, reason.name(), reservationId);
            return false;
        }

        if (doubled) {
            CreditLog noteRow = new CreditLog();
            noteRow.setUserId(userId);
            noteRow.setDelta(0);
            noteRow.setReasonCode(CreditLog.Reason.REPEAT_DOUBLE.name());
            noteRow.setReservationId(reservationId);
            noteRow.setOperator(entry.getOperator());
            noteRow.setNote("30 日内同类问题再犯，本次扣分加倍（原 " + reason.defaultDelta() + " 分）");
            noteRow.setCreatedAt(now);
            try {
                creditLogMapper.insert(noteRow);
            } catch (DuplicateKeyException ignored) {
                // 注记写不进去不影响主流水，分数仍以账本重算为准
            }
        }

        recompute(userId, now);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int awardCleanRecord(LocalDateTime now) {
        LocalDateTime since = now.minusDays(WINDOW_DAYS);
        List<Long> candidates = reservationMapper.findUsersFinishedSince(since);
        int awarded = 0;
        for (Long userId : candidates) {
            // 30 天内已经加过 → 跳过（这就是幂等依据）
            if (creditLogMapper.countSince(userId, CreditLog.Reason.CLEAN_30D.name(), since) > 0) {
                continue;
            }
            // 30 天内有任何负向流水 → 不算"无爽约"
            if (creditLogMapper.countNegativeSince(userId, since) > 0) {
                continue;
            }
            if (adjust(userId, CreditLog.Reason.CLEAN_30D, null, null, null, "SYSTEM",
                    "30 日内无爽约、无违规")) {
                awarded++;
            }
        }
        return awarded;
    }

    /** 按账本重算分数并夹到 [0,100]。 */
    private void recompute(Long userId, LocalDateTime now) {
        int raw = CreditAccount.INITIAL_SCORE + creditLogMapper.sumDelta(userId);
        int score = CreditAccount.clamp(raw);
        CreditAccount existing = creditAccountMapper.selectById(userId);
        if (existing == null) {
            CreditAccount account = new CreditAccount();
            account.setUserId(userId);
            account.setScore(score);
            account.setUpdatedAt(now);
            try {
                creditAccountMapper.insert(account);
            } catch (DuplicateKeyException e) {
                updateScore(userId, score, now);
            }
        } else {
            updateScore(userId, score, now);
        }
    }

    private void updateScore(Long userId, int score, LocalDateTime now) {
        CreditAccount patch = new CreditAccount();
        patch.setUserId(userId);
        patch.setScore(score);
        patch.setUpdatedAt(now);
        creditAccountMapper.updateById(patch);
    }

    /** 补建账户；并发下靠主键冲突兜住。 */
    private void ensureAccount(Long userId) {
        if (userId == null) {
            return;
        }
        CreditAccount account = new CreditAccount();
        account.setUserId(userId);
        account.setScore(CreditAccount.INITIAL_SCORE);
        account.setUpdatedAt(LocalDateTime.now(clock));
        try {
            creditAccountMapper.insert(account);
        } catch (DuplicateKeyException e) {
            log.debug("信用账户已存在，忽略补建：userId={}", userId);
        }
    }
}
