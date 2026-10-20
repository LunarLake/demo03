package com.wyc.demo03.service.impl;

import com.wyc.demo03.entity.CreditAccount;
import com.wyc.demo03.mapper.CreditAccountMapper;
import com.wyc.demo03.service.CreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreditServiceImpl implements CreditService {

    private final CreditAccountMapper creditAccountMapper;
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

    /**
     * 补建账户。并发下两个请求可能同时补建，靠主键冲突兜住 —— 冲突说明别人已经建好了，
     * 这不是错误。
     */
    private void ensureAccount(Long userId) {
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
