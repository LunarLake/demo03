package com.wyc.demo03.service;

/**
 * 信用分读取。
 *
 * <p>P2 只读（规则判定需要当前分值）；P3 补上加减分与流水记账。
 */
public interface CreditService {

    /** 当前信用分；无账户时按初始 100 分处理并补建账户。 */
    int scoreOf(Long userId);
}
