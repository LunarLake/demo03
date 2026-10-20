package com.wyc.demo03.task;

import com.wyc.demo03.mapper.AuditLogMapper;
import com.wyc.demo03.mapper.LogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 日志滚动清理（计划 P7-T05、7.2）。
 *
 * <p>每天 03:20 删除 7 天前的访问日志与业务审计日志。
 *
 * <h3>为什么审计日志也只留 7 天</h3>
 * 文档 7.2 的口径是"日志保留 7 天"，一期按此执行。
 * 但这意味着超过 7 天的判定依据就无法追溯了 —— 因此预约行上的
 * `decision_path` / `hit_rule_codes` / `rule_version_id` 必须**写在业务表上**，
 * 不能只靠审计日志。这也是当初把它们设计成业务字段而不是只记日志的原因。
 *
 * <p>信用流水（{@code t_credit_log}）**不参与滚动**：它是账本，删了分数就没法重算了。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogRotationJob {

    /** 保留天数。 */
    private static final int RETAIN_DAYS = 7;

    private final LogMapper logMapper;
    private final AuditLogMapper auditLogMapper;
    private final Clock clock;

    @Scheduled(cron = "0 20 3 * * *")
    public void rotate() {
        LocalDateTime before = LocalDateTime.now(clock).minusDays(RETAIN_DAYS).withNano(0);
        try {
            int access = logMapper.deleteBefore(before);
            int audit = auditLogMapper.deleteBefore(before);
            if (access > 0 || audit > 0) {
                log.info("日志滚动完成（保留 {} 天）：访问 {} 条，审计 {} 条", RETAIN_DAYS, access, audit);
            }
        } catch (RuntimeException e) {
            // 清理失败不影响业务，明天再试
            log.error("日志滚动失败，明日重试", e);
        }
    }
}
