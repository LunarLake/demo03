-- =============================================================
-- 审批流程增强迁移：t_reservation 增加拒绝原因列
-- 执行方式：mysql -u root -p db02 < sql/migration-reject-reason.sql
-- 幂等：已存在该列时 MySQL 8 会报错（Duplicate column），可忽略或使用下方安全写法
-- =============================================================

-- 安全写法（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，先查再手动决定）：
-- SELECT COUNT(*) INTO @col_exists FROM information_schema.COLUMNS
--   WHERE TABLE_SCHEMA = 'db02' AND TABLE_NAME = 't_reservation' AND COLUMN_NAME = 'reject_reason';

ALTER TABLE t_reservation
    ADD COLUMN reject_reason VARCHAR(200) NULL COMMENT '拒绝原因（管理员拒绝时填写）' AFTER reservation_status;
