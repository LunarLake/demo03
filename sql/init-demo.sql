-- =============================================================
-- init-demo.sql —— 演示前一键复位业务数据（P8-T03）
-- =============================================================
-- 用途：答辩/演示前把系统恢复到"干净但可用"的状态。
--       保留账号、房间、设备、规则版本，清空所有业务记录与信用流水。
--
-- 执行：mysql -u root -p db02 < sql/init-demo.sql
--
-- !! 只用于演示环境。会清空预约、签到、信用、申诉、指令与日志 !!
-- =============================================================

USE db02;
SET NAMES utf8mb4;

-- 业务数据按依赖顺序清空（无外键，顺序仅便于阅读）
DELETE FROM t_reservation_slot;
DELETE FROM t_checkin_code;
DELETE FROM t_attendance_record;
DELETE FROM t_appeal;
DELETE FROM t_credit_log;
DELETE FROM t_reservation;

DELETE FROM t_device_command;
DELETE FROM t_access_log;
DELETE FROM t_audit_log;
DELETE FROM t_log;

-- 信用分复位到 100
UPDATE t_credit_account SET score = 100, updated_at = NOW();

-- 规则回到 v1（删除演示期间发布的临时版本）
DELETE FROM t_rule_config WHERE version_id IN
    (SELECT id FROM (SELECT id FROM t_rule_version WHERE version_no > 1) t);
DELETE FROM t_rule_version WHERE version_no > 1;
UPDATE t_rule_version SET effective_from = NOW() WHERE version_no = 1;

-- 设备离线复位（心跳会重新把它点亮）
UPDATE t_device SET online = 0, last_heartbeat_at = NULL;

-- 房间恢复可用、容量 6
UPDATE t_meeting_room SET room_status = 0, capacity = 6;

SELECT '复位完成' AS result,
       (SELECT COUNT(*) FROM t_reservation)     AS reservations,
       (SELECT COUNT(*) FROM t_reservation_slot) AS slots,
       (SELECT MIN(score) FROM t_credit_account) AS min_credit,
       (SELECT MAX(version_no) FROM t_rule_version) AS rule_version;

-- 校验（逐条执行）：
--   SELECT COUNT(*) FROM t_reservation;      -- 0
--   SELECT COUNT(*) FROM t_reservation_slot; -- 0
--   SELECT MIN(score) FROM t_credit_account; -- 100
--   SELECT MAX(version_no) FROM t_rule_version; -- 1
