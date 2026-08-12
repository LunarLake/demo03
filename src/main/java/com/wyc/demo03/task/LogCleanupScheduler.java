package com.wyc.demo03.task;

import com.wyc.demo03.service.LogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 日志定期清理：每天凌晨 3 点删除 30 天前的访问日志，防止 t_log 无限增长。
 */
@Component
public class LogCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(LogCleanupScheduler.class);

    // 日志保留天数
    static final int RETENTION_DAYS = 30;

    private final LogService logService;

    public LogCleanupScheduler(LogService logService) {
        this.logService = logService;
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanOldLogs() {
        int deleted = logService.deleteBefore(LocalDateTime.now().minusDays(RETENTION_DAYS));
        if (deleted > 0) {
            log.info("日志清理完成：删除 {} 天前的记录 {} 条", RETENTION_DAYS, deleted);
        }
    }
}
