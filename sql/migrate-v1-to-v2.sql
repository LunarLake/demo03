-- =============================================================
-- demo03 数据库 v1 → v2 保数据迁移
-- =============================================================
-- 用途：已有业务数据的环境从 v1（sql/schema.sql）升级到 v2（sql/schema-v2.sql）。
--       全新初始化的环境请直接用 sql/schema-v2.sql，不要执行本脚本。
--
-- ★ 执行前必须备份：
--   mysqldump -u root -p --databases db02 --routines --single-transaction \
--     --default-character-set=utf8mb4 > db02-before-migrate.sql
--
-- 执行方式：
--   mysql -u root -p db02 < sql/migrate-v1-to-v2.sql
--
-- 回退方式：还原上面那份 dump。
--
-- ★ 本脚本是"尽力迁移"，不能保证语义等价的地方都显式标注了 LEGACY，
--   并在最后给出一致性校验查询；校验不通过时请还原备份、人工处理后再迁移。
-- =============================================================

USE db02;
SET NAMES utf8mb4;

-- =============================================================
-- 第 0 步：迁移前置检查（先只读跑一遍，看清家底再动手）
-- =============================================================
-- 0.1 各状态分布
--   SELECT reservation_status, COUNT(*) FROM t_reservation GROUP BY reservation_status ORDER BY 1;
--
-- 0.2 仍需占单元的记录（status 1/6 且未结束）里，有多少没有对齐 15 分钟？
--     这些记录无法映射到「单元序号」，必须先人工处理（改时间或作废）。
--   SELECT id, start_time, end_time
--   FROM t_reservation
--   WHERE reservation_status = 1 AND end_time > NOW()
--     AND (MINUTE(start_time) % 15 <> 0 OR SECOND(start_time) <> 0
--          OR MINUTE(end_time) % 15 <> 0 OR SECOND(end_time) <> 0);
--   ↑ 期望 0 行。非 0 行请先处理，否则第 4 步会跳过它们并导致占用缺口。
--
-- 0.3 签到记录是否存在同一预约多行（v1 没有唯一键，可能重复）
--   SELECT reservation_id, COUNT(*) FROM t_attendance_record GROUP BY 1 HAVING COUNT(*) > 1;

-- =============================================================
-- 第 1 步：改造 t_reservation 结构
-- =============================================================

ALTER TABLE t_reservation
    MODIFY COLUMN reservation_status TINYINT NOT NULL DEFAULT 0
        COMMENT '状态：0待审批 1已通过 2已拒绝 3已取消 5超时释放 6已签到 7已结束（4 已废弃）',
    CHANGE COLUMN reject_reason decision_reason VARCHAR(200) DEFAULT NULL
        COMMENT '拒绝/取消/释放的原因，展示给申请人';

ALTER TABLE t_reservation
    ADD COLUMN created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时刻',
    ADD COLUMN purpose         VARCHAR(200) DEFAULT NULL COMMENT '用途说明（信用分 <70 转人工时必填）',
    ADD COLUMN rule_version_id BIGINT       DEFAULT NULL COMMENT '本次判定所依据的规则版本',
    ADD COLUMN decision_path   VARCHAR(30)  DEFAULT NULL COMMENT 'AUTO_PASS/REJECTED_BASIC/MANUAL_PENDING/MANUAL_APPROVED/MANUAL_REJECTED/TEACHER_AUTO/APPEAL_REBUILD/LEGACY',
    ADD COLUMN hit_rule_codes  VARCHAR(200) DEFAULT NULL COMMENT '命中的规则编号，逗号分隔',
    ADD COLUMN cancel_source   VARCHAR(20)  DEFAULT NULL COMMENT '取消来源：USER/TEACHER_OVERRIDE/ADMIN',
    ADD COLUMN approved_by     BIGINT       DEFAULT NULL COMMENT '人工审核人ID',
    ADD COLUMN approved_at     DATETIME     DEFAULT NULL COMMENT '人工审核时刻',
    ADD COLUMN reminded_at     DATETIME     DEFAULT NULL COMMENT '结束前提醒登记时刻',
    ADD COLUMN closed_at       DATETIME     DEFAULT NULL COMMENT '进入终止态的时刻',
    ADD COLUMN version         INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号';

-- 只新增 v1 没有的索引。
-- idx_room_time / idx_user_status / idx_status_start 在 v1 中已存在，
-- 结构与 v2 需要的一致（idx_room_time 多一列 end_time，仍然可用），不重建。
-- 回填提交时刻：v1 没有记录，用开始时间近似（宁可把旧的算成"早就该办"，也不要算成"刚提交"）
UPDATE t_reservation SET created_at = start_time WHERE start_time < created_at;

ALTER TABLE t_reservation
    ADD KEY idx_manual_due (reservation_status, approved_at),
    ADD KEY idx_created (reservation_status, created_at),
    ADD KEY idx_device_display (room_id, reservation_status, start_time, end_time);

-- =============================================================
-- 第 2 步：状态码迁移
-- =============================================================

-- 2.1 「已批准 1」且已签到 → 「已签到 6」
UPDATE t_reservation r
SET r.reservation_status = 6
WHERE r.reservation_status = 1
  AND EXISTS (SELECT 1 FROM t_attendance_record a
              WHERE a.reservation_id = r.id AND a.attend_status = 1);

-- 2.2 「用户取消 4」→ 「已取消 3」，并记录来源
UPDATE t_reservation
SET reservation_status = 3, cancel_source = 'USER'
WHERE reservation_status = 4;

-- 2.3 「教师覆盖取消 3」保持码不变，补来源
UPDATE t_reservation
SET cancel_source = 'TEACHER_OVERRIDE'
WHERE reservation_status = 3 AND cancel_source IS NULL;

-- 2.4 补齐 decision_path（v1 没有规则引擎，统一标记 LEGACY，不做语义臆测）
UPDATE t_reservation SET decision_path = 'MANUAL_PENDING'  WHERE reservation_status = 0 AND decision_path IS NULL;
UPDATE t_reservation SET decision_path = 'LEGACY'          WHERE reservation_status IN (1, 6) AND decision_path IS NULL;
UPDATE t_reservation SET decision_path = 'MANUAL_REJECTED' WHERE reservation_status = 2 AND decision_path IS NULL;

-- 2.5 已过结束时刻的「已通过/已签到」→「已结束 7」
UPDATE t_reservation
SET reservation_status = 7, closed_at = end_time
WHERE reservation_status IN (1, 6) AND end_time <= NOW();

-- 2.6 终止态补 closed_at
UPDATE t_reservation
SET closed_at = COALESCE(closed_at, end_time, NOW())
WHERE reservation_status IN (2, 3, 5) AND closed_at IS NULL;

-- =============================================================
-- 第 3 步：新建 v2 表（结构同 schema-v2.sql，此处只建增量表）
-- =============================================================

CREATE TABLE IF NOT EXISTS t_reservation_slot (
    id             BIGINT   NOT NULL AUTO_INCREMENT,
    reservation_id BIGINT   NOT NULL,
    room_id        BIGINT   NOT NULL,
    slot_date      DATE     NOT NULL,
    slot_index     SMALLINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_room_date_slot (room_id, slot_date, slot_index),
    KEY idx_reservation (reservation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '有效占用单元（仅已通过/已签到）';

CREATE TABLE IF NOT EXISTS t_checkin_code (
    id             BIGINT   NOT NULL AUTO_INCREMENT,
    reservation_id BIGINT   NOT NULL,
    code           CHAR(4)  NOT NULL,
    issued_at      DATETIME NOT NULL,
    revoked_at     DATETIME DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation (reservation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '签到码';

CREATE TABLE IF NOT EXISTS t_rule_version (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    version_no     INT          NOT NULL,
    effective_from DATETIME     NOT NULL,
    created_by     BIGINT       DEFAULT NULL,
    note           VARCHAR(200) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_version_no (version_no),
    KEY idx_effective (effective_from)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '审核规则版本';

CREATE TABLE IF NOT EXISTS t_rule_config (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    version_id  BIGINT       NOT NULL,
    param_key   VARCHAR(60)  NOT NULL,
    param_value VARCHAR(200) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_version_key (version_id, param_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '附加规则参数';

CREATE TABLE IF NOT EXISTS t_credit_account (
    user_id    BIGINT   NOT NULL,
    score      INT      NOT NULL DEFAULT 100,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '信用账户';

CREATE TABLE IF NOT EXISTS t_credit_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         BIGINT       NOT NULL,
    delta           INT          NOT NULL,
    reason_code     VARCHAR(40)  NOT NULL,
    reservation_id  BIGINT       DEFAULT NULL,
    appeal_id       BIGINT       DEFAULT NULL,
    rule_version_id BIGINT       DEFAULT NULL,
    operator        VARCHAR(50)  NOT NULL,
    note            VARCHAR(200) DEFAULT NULL,
    created_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_user_time (user_id, created_at),
    UNIQUE KEY uk_res_reason (reservation_id, reason_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '信用流水';

CREATE TABLE IF NOT EXISTS t_appeal (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    user_id           BIGINT       NOT NULL,
    target_type       VARCHAR(20)  NOT NULL,
    target_id         BIGINT       NOT NULL,
    reason            VARCHAR(500) NOT NULL,
    evidence          VARCHAR(500) DEFAULT NULL,
    status            VARCHAR(20)  NOT NULL,
    original_decision VARCHAR(300) DEFAULT NULL,
    rule_version_id   BIGINT       DEFAULT NULL,
    reviewer_id       BIGINT       DEFAULT NULL,
    review_note       VARCHAR(500) DEFAULT NULL,
    created_at        DATETIME     NOT NULL,
    due_at            DATETIME     NOT NULL,
    decided_at        DATETIME     DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_target (user_id, target_type, target_id),
    KEY idx_status_due (status, due_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '申诉工单';

CREATE TABLE IF NOT EXISTS t_device (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    device_code       VARCHAR(40) NOT NULL,
    room_id           BIGINT      NOT NULL,
    secret            VARCHAR(64) NOT NULL,
    firmware_ver      VARCHAR(20) DEFAULT NULL,
    last_heartbeat_at DATETIME    DEFAULT NULL,
    online            TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_device_code (device_code),
    KEY idx_room (room_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '门禁设备';

CREATE TABLE IF NOT EXISTS t_device_command (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    msg_id         VARCHAR(40)  NOT NULL,
    device_id      BIGINT       NOT NULL,
    reservation_id BIGINT       DEFAULT NULL,
    cmd_type       VARCHAR(20)  NOT NULL,
    payload        VARCHAR(500) DEFAULT NULL,
    status         VARCHAR(20)  NOT NULL,
    issued_at      DATETIME     NOT NULL,
    expires_at     DATETIME     NOT NULL,
    acked_at       DATETIME     DEFAULT NULL,
    result_code    VARCHAR(40)  DEFAULT NULL,
    retry_count    INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_id (msg_id),
    KEY idx_device_status (device_id, status),
    KEY idx_reservation (reservation_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '设备指令';

CREATE TABLE IF NOT EXISTS t_access_log (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    reservation_id BIGINT      DEFAULT NULL,
    user_id        BIGINT      NOT NULL,
    device_id      BIGINT      DEFAULT NULL,
    command_id     BIGINT      DEFAULT NULL,
    granted        TINYINT     NOT NULL,
    deny_reason    VARCHAR(40) DEFAULT NULL,
    ip             VARCHAR(50) DEFAULT NULL,
    created_at     DATETIME    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_res_time (reservation_id, created_at),
    KEY idx_user_time (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '开门操作记录';

CREATE TABLE IF NOT EXISTS t_audit_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    actor           VARCHAR(50)  NOT NULL,
    action          VARCHAR(40)  NOT NULL,
    target_type     VARCHAR(20)  DEFAULT NULL,
    target_id       BIGINT       DEFAULT NULL,
    detail          VARCHAR(500) DEFAULT NULL,
    rule_version_id BIGINT       DEFAULT NULL,
    created_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_time (created_at),
    KEY idx_action (action),
    KEY idx_target (target_type, target_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '业务审计日志';

-- =============================================================
-- 第 4 步：重建有效占用单元
--   只为「已通过/已签到」且尚未结束、且已对齐 15 分钟的记录生成单元。
--   使用 INSERT IGNORE：撞键说明 v1 里本就存在重叠占用（教师覆盖留下的脏数据），
--   这类冲突会在第 6 步的一致性校验里以数量差的形式暴露。
-- =============================================================

INSERT IGNORE INTO t_reservation_slot (reservation_id, room_id, slot_date, slot_index)
SELECT r.id,
       r.room_id,
       DATE(r.start_time),
       FLOOR((TIME_TO_SEC(TIME(r.start_time)) - 8 * 3600) / 900) + seq.n
FROM t_reservation r
JOIN (
    WITH RECURSIVE nums AS (
        SELECT 0 AS n
        UNION ALL
        SELECT n + 1 FROM nums WHERE n < 15
    )
    SELECT n FROM nums
) seq
  ON TIMESTAMPDIFF(MINUTE, r.start_time, r.end_time) >= (seq.n + 1) * 15
WHERE r.reservation_status IN (1, 6)
  AND r.end_time > NOW()
  AND MINUTE(r.start_time) % 15 = 0 AND SECOND(r.start_time) = 0
  AND MINUTE(r.end_time)   % 15 = 0 AND SECOND(r.end_time)   = 0
  AND TIME(r.start_time) >= '08:00:00'
  AND TIME(r.end_time)   <= '22:00:00';

-- =============================================================
-- 第 5 步：签到码拆分 + 签到记录唯一键
-- =============================================================

INSERT IGNORE INTO t_checkin_code (reservation_id, code, issued_at, revoked_at)
SELECT r.id, LPAD(r.check_in_code, 4, '0'), COALESCE(r.approved_at, r.start_time, NOW()),
       -- 终止态的码一律视为已撤销
       CASE WHEN r.reservation_status IN (2, 3, 5, 7) THEN NOW() ELSE NULL END
FROM t_reservation r
WHERE r.check_in_code IS NOT NULL AND r.check_in_code <> '';

-- 清理签到记录重复行（v1 无唯一键）：
-- 先删同一预约下"未签到"的冗余行（保留已签到那条），再按 id 去重
DELETE a FROM t_attendance_record a
JOIN t_attendance_record b
  ON a.reservation_id = b.reservation_id
 AND a.attend_status = 0 AND b.attend_status = 1;

DELETE a1 FROM t_attendance_record a1
JOIN t_attendance_record a2
  ON a1.reservation_id = a2.reservation_id AND a1.id > a2.id;

ALTER TABLE t_attendance_record
    ADD COLUMN method VARCHAR(20) NOT NULL DEFAULT 'SCREEN_CODE' COMMENT '签到方式',
    ADD UNIQUE KEY uk_reservation (reservation_id);

-- 回填已签到记录的时间（v1 存在 attend_status=1 但 check_in_time 为空的情况）
UPDATE t_attendance_record
SET check_in_time = (SELECT r.start_time FROM t_reservation r WHERE r.id = t_attendance_record.reservation_id)
WHERE attend_status = 1 AND check_in_time IS NULL;

-- check_in_code 已迁出，删除原列（确认无误后再执行；先注释保留一周亦可）
ALTER TABLE t_reservation DROP COLUMN check_in_code;

-- =============================================================
-- 第 6 步：规则版本、信用账户、设备初始化
-- =============================================================

INSERT INTO t_rule_version (version_no, effective_from, created_by, note)
SELECT 1, NOW(), NULL, 'v1 迁移：一期默认参数'
WHERE NOT EXISTS (SELECT 1 FROM t_rule_version WHERE version_no = 1);

INSERT IGNORE INTO t_rule_config (version_id, param_key, param_value)
SELECT v.id, p.k, p.v
FROM t_rule_version v
JOIN (
    SELECT 'AUTO_APPROVE_CREDIT'       AS k, '90' AS v UNION ALL
    SELECT 'MID_CREDIT_MIN',                 '70'      UNION ALL
    SELECT 'MID_MAX_DURATION_MINUTES',       '120'     UNION ALL
    SELECT 'AUTO_APPROVE_DAILY_LIMIT',       '3'       UNION ALL
    SELECT 'MAX_CONTINUOUS_MINUTES',         '240'     UNION ALL
    SELECT 'MANUAL_REVIEW_HEADCOUNT',        '5'       UNION ALL
    SELECT 'MANUAL_REVIEW_DUE_HOURS',        '24'      UNION ALL
    SELECT 'BASE_RULE_MIN_CREDIT',           '60'
) p
WHERE v.version_no = 1;

INSERT IGNORE INTO t_credit_account (user_id, score, updated_at)
SELECT id, 100, NOW() FROM t_user;

-- 设备：按房间各建一台（真机到货后改 device_code 与 secret）
INSERT IGNORE INTO t_device (device_code, room_id, secret, firmware_ver, online)
SELECT CONCAT('ROOM-', m.id, '-01'), m.id,
       CONCAT('demo-secret-room-', m.id, '-please-change'), 'sim-1.0', 0
FROM t_meeting_room m;

-- =============================================================
-- 第 7 步：一致性校验 —— 逐条执行，任何一条不符合预期都必须还原备份
-- =============================================================

-- 7.1 废弃状态码必须清零（★ 最重要：非 0 说明有行会被误读）
--   SELECT COUNT(*) AS should_be_zero FROM t_reservation WHERE reservation_status = 4;
--
-- 7.2 状态码必须都在 v2 合法集合内
--   SELECT reservation_status, COUNT(*) FROM t_reservation
--   WHERE reservation_status NOT IN (0,1,2,3,5,6,7) GROUP BY 1;   -- 期望 0 行
--
-- 7.3 「已通过/已签到」必须都占着单元
--   SELECT r.id FROM t_reservation r
--   WHERE r.reservation_status IN (1,6) AND r.end_time > NOW()
--     AND NOT EXISTS (SELECT 1 FROM t_reservation_slot s WHERE s.reservation_id = r.id);
--   -- 期望 0 行（非 0 说明该记录未对齐 15 分钟，见第 0.2 步）
--
-- 7.4 终止态不得残留占用
--   SELECT s.reservation_id FROM t_reservation_slot s
--   JOIN t_reservation r ON r.id = s.reservation_id
--   WHERE r.reservation_status IN (0,2,3,5,7);
--   -- 期望 0 行
--
-- 7.5 单元总数 = 期望单元总数（差额即 v1 遗留的重叠占用）
--   SELECT
--     (SELECT COUNT(*) FROM t_reservation_slot) AS actual_units,
--     (SELECT COALESCE(SUM(TIMESTAMPDIFF(MINUTE, start_time, end_time) / 15), 0)
--        FROM t_reservation
--       WHERE reservation_status IN (1,6) AND end_time > NOW()
--         AND MINUTE(start_time) % 15 = 0 AND MINUTE(end_time) % 15 = 0) AS expected_units;
--   -- actual 应等于 expected；actual 更小说明有冲突被 IGNORE 掉，需人工核对
--
-- 7.6 签到记录必须 1:1
--   SELECT reservation_id, COUNT(*) FROM t_attendance_record GROUP BY 1 HAVING COUNT(*) > 1;  -- 期望 0 行

-- =============================================================
-- 可选：按一期口径重置房间（★ 会删除多出的房间，默认不执行）
--   一期固定 2 间、每间容量 6（可行性报告 3.2.1 / 附录 A）。
--   若有指向多余房间的历史预约，需先决定这些记录的去留。
-- =============================================================
-- UPDATE t_meeting_room SET capacity = 6 WHERE id IN (1, 2);
-- DELETE FROM t_meeting_room WHERE id NOT IN (1, 2);
