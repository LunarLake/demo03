package com.wyc.demo03.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wyc.demo03.entity.CreditAccount;
import com.wyc.demo03.entity.CreditLog;
import com.wyc.demo03.entity.User;
import com.wyc.demo03.mapper.CreditAccountMapper;
import com.wyc.demo03.mapper.CreditLogMapper;
import com.wyc.demo03.service.CreditService;
import com.wyc.demo03.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-016：信用分缓存的并发覆盖。
 *
 * <p>这个用例**故意不加** {@code @Transactional} —— 要验证的是"两个独立事务"的真实并发，
 * 套在测试事务里所有操作会走同一条连接，锁行为完全不同，测了等于没测。
 *
 * <p>因此它自己负责数据清理：动手前和收尾时都清空该用户的流水并复位分数。
 */
@SpringBootTest
class CreditConcurrencyTest {

    private static final int ROUNDS = 5;

    @Autowired private CreditService creditService;
    @Autowired private CreditLogMapper creditLogMapper;
    @Autowired private CreditAccountMapper creditAccountMapper;
    @Autowired private UserService userService;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = userService.lambdaQuery().eq(User::getUsername, "student03").one().getId();
        reset();
    }

    @AfterEach
    void tearDown() {
        reset();
    }

    private void reset() {
        creditLogMapper.delete(new LambdaQueryWrapper<CreditLog>()
                .eq(CreditLog::getUserId, userId));
        CreditAccount account = creditAccountMapper.selectById(userId);
        if (account != null) {
            account.setScore(100);
            account.setUpdatedAt(LocalDateTime.now());
            creditAccountMapper.updateById(account);
        }
    }

    @Test
    @DisplayName("两个独立事务同步调整同一用户：缓存必须等于账本夹取值")
    void concurrentAdjustKeepsCacheConsistentWithLedger() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            reset();

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            long baseReservation = 9_000_000L + round * 10L;

            Thread noShow = new Thread(() -> {
                await(start);
                try {
                    creditService.adjust(userId, CreditLog.Reason.NO_SHOW, null,
                            baseReservation, null, "TEST", "并发用例");
                } finally {
                    done.countDown();
                }
            }, "credit-a");
            Thread violation = new Thread(() -> {
                await(start);
                try {
                    creditService.adjust(userId, CreditLog.Reason.VIOLATION, null,
                            baseReservation + 1, null, "TEST", "并发用例");
                } finally {
                    done.countDown();
                }
            }, "credit-b");

            noShow.start();
            violation.start();
            start.countDown();
            assertTrue(done.await(15, TimeUnit.SECONDS), "两个调整事务应在 15 秒内完成");

            int expected = CreditAccount.clamp(CreditAccount.INITIAL_SCORE + creditLogMapper.sumDelta(userId));
            int cached = creditAccountMapper.selectById(userId).getScore();
            assertEquals(expected, cached,
                    "第 " + round + " 轮：缓存分数必须等于账本重算结果，不能被不完整总和覆盖");
            assertEquals(70, expected, "两笔扣分（−10 与 −20）后应为 70");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
