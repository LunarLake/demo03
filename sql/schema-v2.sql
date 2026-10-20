-- =============================================================
-- demo03 智慧研讨室预约与门禁管理系统 — 数据库 v2（软硬件结合版）
-- =============================================================
-- 依据：docs/重构改造计划.md（第 3 节数据模型 / 第 4 节状态机）
--      项目更新可行性计划.docx（3.2.1 时段规则、3.2.2 三层审核、3.2.3 签到开门、3.2.4 提醒释放）
--
-- 目标数据库：MySQL 8.0（db02）
-- 执行方式：
--   mysql -u root -p < sql/schema-v2.sql
--
-- !! 警告：本脚本会 DROP 全部业务表，重复执行将清空数据 !!
--    仅用于全新初始化。已有数据的环境请改用 sql/migrate-v1-to-v2.sql 做保数据迁移。
--
-- 与 v1（sql/schema.sql）的差异摘要：
--   1. t_reservation 状态码重定义（7 态，码 4 废弃），新增规则/决策/审计字段
--   2. 新增 t_reservation_slot —— 「房间+日期+单元序号」唯一占用，并发仲裁靠唯一键
--   3. 签到码从 t_reservation 拆到 t_checkin_code —— 需要独立的撤销位与有效期
--   4. 新增规则版本 / 信用账本 / 申诉工单 / 设备 / 指令 / 开门记录 / 业务审计 共 8 张表
--   5. t_attendance_record 增加 method / success，reservation_id 提为唯一键
-- =============================================================

CREATE DATABASE IF NOT EXISTS db02
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE db02;

SET NAMES utf8mb4;

-- 无外键约束：与项目既有惯例一致（MyBatis-Plus），一致性由业务层与事务保证。
-- 但所有该唯一的地方都建了 UNIQUE KEY —— 并发安全不依赖应用层判断。

DROP TABLE IF EXISTS t_access_log;
DROP TABLE IF EXISTS t_device_command;
DROP TABLE IF EXISTS t_device;
DROP TABLE IF EXISTS t_appeal;
DROP TABLE IF EXISTS t_credit_log;
DROP TABLE IF EXISTS t_credit_account;
DROP TABLE IF EXISTS t_rule_config;
DROP TABLE IF EXISTS t_rule_version;
DROP TABLE IF EXISTS t_audit_log;
DROP TABLE IF EXISTS t_checkin_code;
DROP TABLE IF EXISTS t_reservation_slot;
DROP TABLE IF EXISTS t_attendance_record;
DROP TABLE IF EXISTS t_reservation;
DROP TABLE IF EXISTS t_meeting_room;
DROP TABLE IF EXISTS t_user;
DROP TABLE IF EXISTS t_log;

-- -------------------------------------------------------------
-- 1. t_user —— 用户表（结构不变）
-- -------------------------------------------------------------
CREATE TABLE t_user (
    id       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户主键ID',
    username VARCHAR(50)  NOT NULL                COMMENT '登录用户名',
    password VARCHAR(100) NOT NULL                COMMENT '登录密码（BCrypt 哈希，$2a$ 前缀共 60 位）',
    name     VARCHAR(50)  NOT NULL                COMMENT '用户姓名',
    role     VARCHAR(20)  NOT NULL DEFAULT 'STUDENT'
                                                  COMMENT '角色：STUDENT-学生，TEACHER-教师，ADMIN-管理员',
    email    VARCHAR(100) DEFAULT NULL            COMMENT '用户邮箱',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '用户表';

-- -------------------------------------------------------------
-- 2. t_meeting_room —— 房间表（结构不变）
--    一期按可行性报告固定 2 间、每间容量 6 人；room_status=1 表示维护中。
-- -------------------------------------------------------------
CREATE TABLE t_meeting_room (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '房间主键ID',
    room_name   VARCHAR(100) NOT NULL                COMMENT '房间名称/房间号',
    capacity    INT          DEFAULT NULL            COMMENT '容量（人）。一期固定 6',
    equipment   VARCHAR(255) DEFAULT NULL            COMMENT '设备清单，顿号分隔文本',
    room_status TINYINT      NOT NULL DEFAULT 0      COMMENT '0-正常可用，1-维护中',
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '房间表';

-- -------------------------------------------------------------
-- 3. t_reservation —— 预约表（核心业务表，v2 重定义）
--
--    状态机 reservation_status（详见 docs/重构改造计划.md 第 4 节）：
--      0 = 待审批 PENDING          —— 不占单元，可有多笔并存
--      1 = 已通过 APPROVED         —— 占单元
--      2 = 已拒绝 REJECTED         —— 终止
--      3 = 已取消 CANCELED         —— 终止，来源见 cancel_source
--      4 = 【废弃】原「用户取消」，已合并进 3；迁移后此值必须为 0 行
--      5 = 超时释放 TIMEOUT_RELEASED —— 终止
--      6 = 已签到 CHECKED_IN       —— 占单元
--      7 = 已结束 FINISHED         —— 终止
--
--    ★ 为什么新状态用 6/7 而不是 4/6：
--      旧值 4 表示「用户取消」。若把 4 复用来表示「已签到」，任何漏迁移的旧行
--      或未及时更新的 SQL 都会被读成「已签到」并因此授予开门权限 —— 这是安全
--      方向的错误。取用从未出现过的 6/7，旧代码读到新值必然落进 default 分支
--      （拒绝），失败安全。
-- -------------------------------------------------------------
CREATE TABLE t_reservation (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '预约单主键ID',
    user_id          BIGINT       NOT NULL                COMMENT '申请人ID（关联 t_user.id）',
    room_id          BIGINT       NOT NULL                COMMENT '房间ID（关联 t_meeting_room.id）',
    start_time       DATETIME     NOT NULL                COMMENT '预约开始时刻（必然对齐 15 分钟整点）',
    end_time         DATETIME     NOT NULL                COMMENT '预约结束时刻（必然对齐 15 分钟整点）',
    reservation_status TINYINT    NOT NULL DEFAULT 0      COMMENT '状态：0待审批 1已通过 2已拒绝 3已取消 5超时释放 6已签到 7已结束（4 已废弃）',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提交时刻（24h 办结时限的起算点）',
    attendee_count   INT          DEFAULT NULL            COMMENT '参会人数（1..房间容量）',
    purpose          VARCHAR(200) DEFAULT NULL            COMMENT '用途说明（信用分 <70 转人工时必填）',
    decision_reason  VARCHAR(200) DEFAULT NULL            COMMENT '拒绝/取消/释放的原因，展示给申请人',
    rule_version_id  BIGINT       DEFAULT NULL            COMMENT '本次判定所依据的规则版本（t_rule_version.id）',
    decision_path    VARCHAR(30)  DEFAULT NULL            COMMENT 'AUTO_PASS/REJECTED_BASIC/MANUAL_PENDING/MANUAL_APPROVED/MANUAL_REJECTED/TEACHER_AUTO/APPEAL_REBUILD',
    hit_rule_codes   VARCHAR(200) DEFAULT NULL            COMMENT '命中的规则编号，逗号分隔，用于申诉举证',
    cancel_source    VARCHAR(20)  DEFAULT NULL            COMMENT '取消来源：USER/TEACHER_OVERRIDE/ADMIN',
    approved_by      BIGINT       DEFAULT NULL            COMMENT '人工审核人ID',
    approved_at      DATETIME     DEFAULT NULL            COMMENT '人工审核时刻（用于 24 小时办结考核）',
    reminded_at      DATETIME     DEFAULT NULL            COMMENT '结束前提醒的登记时刻，保证只提醒一次',
    closed_at        DATETIME     DEFAULT NULL            COMMENT '进入终止态的时刻（申诉 7 日期限的起算点）',
    version          INT          NOT NULL DEFAULT 0      COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    KEY idx_room_time (room_id, start_time, end_time)        COMMENT '按房间查当日/范围记录（与 v1 同名同构，迁移不重建）',
    KEY idx_user_status (user_id, reservation_status)        COMMENT '我的预约分页筛选',
    KEY idx_status_start (reservation_status, start_time)    COMMENT '待审批列表 / 签到截止扫描 / 到期结束扫描',
    KEY idx_manual_due (reservation_status, approved_at)     COMMENT '人工审核 24h 超时预警',
    KEY idx_created (reservation_status, created_at)          COMMENT '待审批超时扫描',
    KEY idx_device_display (room_id, reservation_status, start_time, end_time) COMMENT '设备屏幕当前有效预约查询'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '预约表';

-- -------------------------------------------------------------
-- 4. t_reservation_slot —— 有效占用单元（★ 并发正确性的唯一支点）
--
--    表中出现一行 = 该「房间+日期+单元」被有效占用。
--    只有「已通过 / 已签到」写入；拒绝/取消/超时释放/已结束 立即删除。
--    绝不保留"占位但已作废"的行 —— 否则会把历史记录的唯一约束误当成资源占用约束。
--
--    并发策略：批准时逐行 INSERT，任意一行撞 uk_room_date_slot 就抛
--    DuplicateKeyException，事务整体回滚。不需要先 SELECT 再 INSERT，
--    也不需要分布式锁；最坏结果是"批准失败并提示重选"，而不是"重复占用"。
-- -------------------------------------------------------------
CREATE TABLE t_reservation_slot (
    id             BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    reservation_id BIGINT   NOT NULL                COMMENT '关联 t_reservation.id',
    room_id        BIGINT   NOT NULL                COMMENT '冗余房间ID，供唯一键使用',
    slot_date      DATE     NOT NULL                COMMENT '占用日期',
    slot_index     SMALLINT NOT NULL                COMMENT '当日单元序号：0..55，对应 08:00 + index*15min',
    PRIMARY KEY (id),
    UNIQUE KEY uk_room_date_slot (room_id, slot_date, slot_index) COMMENT '★ 并发仲裁键',
    KEY idx_reservation (reservation_id) COMMENT '按预约批量删除'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '有效占用单元（仅已通过/已签到）';

-- -------------------------------------------------------------
-- 5. t_checkin_code —— 签到码（从 t_reservation.check_in_code 拆出）
--
--    拆分理由：
--      1) 文档 3.2.3 要求取消/覆盖/释放时「撤销签到码与开门授权」，需要独立撤销位；
--      2) 「未来预约的码可先保存在服务端，但设备只显示进入当前签到窗口的有效码」，
--         码需要能被设备侧判断是否仍然有效；
--      3) 申诉撤销后可能重建预约，码与预约 1:1 但生命周期独立更清晰。
-- -------------------------------------------------------------
CREATE TABLE t_checkin_code (
    id             BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    reservation_id BIGINT   NOT NULL                COMMENT '关联 t_reservation.id（1:1）',
    code           CHAR(4)  NOT NULL                COMMENT '4 位数字签到码',
    issued_at      DATETIME NOT NULL                COMMENT '签发时刻',
    revoked_at     DATETIME DEFAULT NULL            COMMENT '撤销时刻；非空表示已失效，不得再用于开门',
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation (reservation_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '签到码';

-- -------------------------------------------------------------
-- 6. t_attendance_record —— 首次签到记录（v2 改造）
--    reservation_id 提升为唯一键：签到成功判定改为依赖库的原子性，
--    而不是"先查再条件更新"。
-- -------------------------------------------------------------
CREATE TABLE t_attendance_record (
    id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '签到记录主键ID',
    reservation_id BIGINT      NOT NULL                COMMENT '关联 t_reservation.id（1:1）',
    user_id        BIGINT      DEFAULT NULL            COMMENT '签到用户ID',
    check_in_time  DATETIME    DEFAULT NULL            COMMENT '首次签到成功时刻',
    attend_status  TINYINT     NOT NULL DEFAULT 0      COMMENT '0-未签到，1-已签到',
    method         VARCHAR(20) NOT NULL DEFAULT 'SCREEN_CODE' COMMENT '签到方式：SCREEN_CODE-屏幕码',
    ip             VARCHAR(50) DEFAULT NULL            COMMENT '签到客户端 IP',
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation (reservation_id),
    KEY idx_check_in_time (check_in_time) COMMENT '今日签到统计'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '首次签到记录';

-- -------------------------------------------------------------
-- 7. t_rule_version —— 审核规则版本
--    每改一次参数就产生新版本；预约行记录判定时的 version_id，
--    因此改参数不会影响历史判定（文档 3.2.2「历史审核按当时的版本记录」）。
-- -------------------------------------------------------------
CREATE TABLE t_rule_version (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    version_no     INT          NOT NULL                COMMENT '版本号，自增',
    effective_from DATETIME     NOT NULL                COMMENT '生效时刻',
    created_by     BIGINT       DEFAULT NULL            COMMENT '发布人ID',
    note           VARCHAR(200) DEFAULT NULL            COMMENT '变更说明',
    PRIMARY KEY (id),
    UNIQUE KEY uk_version_no (version_no),
    KEY idx_effective (effective_from)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '审核规则版本';

-- -------------------------------------------------------------
-- 8. t_rule_config —— 附加规则参数（键值化，不做脚本）
--    ★ 刻意不做通用表达式 DSL：16 周内不可维护、无法穷举测试。
--      param_key 由代码中的枚举白名单校验，未知 key 拒绝保存。
-- -------------------------------------------------------------
CREATE TABLE t_rule_config (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    version_id  BIGINT       NOT NULL                COMMENT '所属规则版本',
    param_key   VARCHAR(60)  NOT NULL                COMMENT '参数名（白名单）',
    param_value VARCHAR(200) NOT NULL                COMMENT '参数值',
    PRIMARY KEY (id),
    UNIQUE KEY uk_version_key (version_id, param_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '附加规则参数';

-- -------------------------------------------------------------
-- 9. t_credit_account —— 信用账户（分数是缓存，流水才是账本）
-- -------------------------------------------------------------
CREATE TABLE t_credit_account (
    user_id    BIGINT   NOT NULL                COMMENT '用户ID',
    score      INT      NOT NULL DEFAULT 100    COMMENT '当前信用分，上下限 100/0',
    updated_at DATETIME NOT NULL                COMMENT '最后变动时刻',
    PRIMARY KEY (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '信用账户';

-- -------------------------------------------------------------
-- 10. t_credit_log —— 信用流水（只由规则写入）
--     uk_res_reason 从库层挡住同一预约同一原因的重复扣分。
-- -------------------------------------------------------------
CREATE TABLE t_credit_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id         BIGINT       NOT NULL                COMMENT '用户ID',
    delta           INT          NOT NULL                COMMENT '变动值：负扣分，正加分，0 为注记',
    reason_code     VARCHAR(40)  NOT NULL                COMMENT 'NO_SHOW/OVERTIME_OCCUPY/VIOLATION/REPEAT_DOUBLE/ON_TIME_CHECKIN/CLEAN_30D/APPEAL_REVOKE',
    reservation_id  BIGINT       DEFAULT NULL            COMMENT '关联预约',
    appeal_id       BIGINT       DEFAULT NULL            COMMENT '关联申诉',
    rule_version_id BIGINT       DEFAULT NULL            COMMENT '依据的规则版本',
    operator        VARCHAR(50)  NOT NULL                COMMENT 'SYSTEM 或人工认定的管理员用户名',
    note            VARCHAR(200) DEFAULT NULL            COMMENT '说明',
    created_at      DATETIME     NOT NULL                COMMENT '发生时刻',
    PRIMARY KEY (id),
    KEY idx_user_time (user_id, created_at) COMMENT '查流水与 30 日窗口',
    UNIQUE KEY uk_res_reason (reservation_id, reason_code) COMMENT '防重复扣分（reservation_id 为 NULL 时不受约束）'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '信用流水';

-- -------------------------------------------------------------
-- 11. t_appeal —— 申诉工单（独立于预约状态机，不占房间单元）
--     uk_user_target 实现文档「7 日内提交一次」。
-- -------------------------------------------------------------
CREATE TABLE t_appeal (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id           BIGINT       NOT NULL                COMMENT '提交人',
    target_type       VARCHAR(20)  NOT NULL                COMMENT 'RESERVATION/CREDIT',
    target_id         BIGINT       NOT NULL                COMMENT '预约ID 或 信用流水ID',
    reason            VARCHAR(500) NOT NULL                COMMENT '申诉说明',
    evidence          VARCHAR(500) DEFAULT NULL            COMMENT '凭证说明或链接（一期不做文件上传）',
    status            VARCHAR(20)  NOT NULL                COMMENT 'SUBMITTED/UPHELD/REVOKED/PARTIAL',
    original_decision VARCHAR(300) DEFAULT NULL            COMMENT '原处理结论与依据的规则版本快照',
    rule_version_id   BIGINT       DEFAULT NULL            COMMENT '原判定的规则版本',
    reviewer_id       BIGINT       DEFAULT NULL            COMMENT '复核人（必须非原审核人）',
    review_note       VARCHAR(500) DEFAULT NULL            COMMENT '复核结论说明',
    created_at        DATETIME     NOT NULL                COMMENT '提交时刻',
    due_at            DATETIME     NOT NULL                COMMENT '应办结时刻 = created_at + 3 工作日',
    decided_at        DATETIME     DEFAULT NULL            COMMENT '办结时刻',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_target (user_id, target_type, target_id) COMMENT '同一目标只可申诉一次',
    KEY idx_status_due (status, due_at) COMMENT '超期工单扫描'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '申诉工单';

-- -------------------------------------------------------------
-- 12. t_device —— 门禁设备
-- -------------------------------------------------------------
CREATE TABLE t_device (
    id                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    device_code       VARCHAR(40) NOT NULL                COMMENT '设备编码，即 MQTT 主题中的 {deviceCode}',
    room_id           BIGINT      NOT NULL                COMMENT '绑定房间',
    secret            VARCHAR(64) NOT NULL                COMMENT 'HMAC-SHA256 密钥（演示期明文，正式环境需加密存储）',
    firmware_ver      VARCHAR(20) DEFAULT NULL            COMMENT '固件版本',
    last_heartbeat_at DATETIME    DEFAULT NULL            COMMENT '最后一次心跳时刻',
    online            TINYINT     NOT NULL DEFAULT 0      COMMENT '0-离线 1-在线；90 秒无心跳判离线',
    PRIMARY KEY (id),
    UNIQUE KEY uk_device_code (device_code),
    KEY idx_room (room_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '门禁设备';

-- -------------------------------------------------------------
-- 13. t_device_command —— 下发指令（一次性，不保留重放）
--     uk_msg 保证同一 msgId 只入库一次；status 追踪投递与回执。
-- -------------------------------------------------------------
CREATE TABLE t_device_command (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    msg_id         VARCHAR(40)  NOT NULL                COMMENT 'UUID，设备端据此幂等去重',
    device_id      BIGINT       NOT NULL                COMMENT '目标设备',
    reservation_id BIGINT       DEFAULT NULL            COMMENT '关联预约',
    cmd_type       VARCHAR(20)  NOT NULL                COMMENT 'UNLOCK/DISPLAY/CLEAR/REMIND/BUZZ',
    payload        VARCHAR(500) DEFAULT NULL            COMMENT '指令载荷 JSON',
    status         VARCHAR(20)  NOT NULL                COMMENT 'PENDING/SENT/ACKED/FAILED/EXPIRED',
    issued_at      DATETIME     NOT NULL                COMMENT '下发时刻',
    expires_at     DATETIME     NOT NULL                COMMENT '失效时刻；过期指令设备必须丢弃',
    acked_at       DATETIME     DEFAULT NULL            COMMENT '回执时刻',
    result_code    VARCHAR(40)  DEFAULT NULL            COMMENT '回执结果：OK/ERR_HMAC/ERR_EXPIRED/ERR_DUP/ERR_BUSY',
    retry_count    INT          NOT NULL DEFAULT 0      COMMENT '重试次数',
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_id (msg_id),
    KEY idx_device_status (device_id, status),
    KEY idx_reservation (reservation_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '设备指令';

-- -------------------------------------------------------------
-- 14. t_access_log —— 开门操作记录
--     每次「申请开门」都留一行，成功与失败都记（文档 3.2.3 要求独立操作记录）。
-- -------------------------------------------------------------
CREATE TABLE t_access_log (
    id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    reservation_id BIGINT      DEFAULT NULL            COMMENT '关联预约',
    user_id        BIGINT      NOT NULL                COMMENT '申请人',
    device_id      BIGINT      DEFAULT NULL            COMMENT '目标设备；房间未配置设备时为空（仍要留拒绝记录）',
    command_id     BIGINT      DEFAULT NULL            COMMENT '关联指令',
    granted        TINYINT     NOT NULL                COMMENT '1-已授权下发 0-拒绝',
    deny_reason    VARCHAR(40) DEFAULT NULL            COMMENT '拒绝原因：NOT_OWNER/STATUS_INVALID/OUT_OF_WINDOW/NO_CODE/DEVICE_OFFLINE',
    ip             VARCHAR(50) DEFAULT NULL            COMMENT '申请人 IP',
    created_at     DATETIME    NOT NULL                COMMENT '发生时刻',
    PRIMARY KEY (id),
    KEY idx_res_time (reservation_id, created_at),
    KEY idx_user_time (user_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '开门操作记录';

-- -------------------------------------------------------------
-- 15. t_audit_log —— 业务审计（与访问日志 t_log 分开）
--     文档 4.1 T12 要求记录：预约变更、规则判定版本、信用流水、指令结果。
-- -------------------------------------------------------------
CREATE TABLE t_audit_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    actor           VARCHAR(50)  NOT NULL                COMMENT '操作者用户名，或 SYSTEM',
    action          VARCHAR(40)  NOT NULL                COMMENT 'RESERVE/APPROVE/REJECT/CANCEL/RELEASE/CHECKIN/UNLOCK/RULE_PUBLISH/APPEAL_DECIDE/CREDIT_ADJUST',
    target_type     VARCHAR(20)  DEFAULT NULL            COMMENT 'RESERVATION/APPEAL/DEVICE/RULE',
    target_id       BIGINT       DEFAULT NULL            COMMENT '目标ID',
    detail          VARCHAR(500) DEFAULT NULL            COMMENT '变更详情',
    rule_version_id BIGINT       DEFAULT NULL            COMMENT '涉及规则判定时记录版本',
    created_at      DATETIME     NOT NULL                COMMENT '发生时刻',
    PRIMARY KEY (id),
    KEY idx_time (created_at),
    KEY idx_action (action),
    KEY idx_target (target_type, target_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '业务审计日志';

-- -------------------------------------------------------------
-- 16. t_log —— 访问日志（结构不变；保留期改为 7 天滚动）
--     注意：timestamp 为列名，涉及该列的 SQL 需加反引号
-- -------------------------------------------------------------
CREATE TABLE t_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '日志主键ID',
    username    VARCHAR(50)  DEFAULT NULL            COMMENT '触发操作的用户名',
    url         VARCHAR(500) DEFAULT NULL            COMMENT '请求的资源路径',
    ip          VARCHAR(50)  DEFAULT NULL            COMMENT '客户端 IP',
    `timestamp` DATETIME     DEFAULT NULL            COMMENT '操作时刻',
    PRIMARY KEY (id),
    KEY idx_timestamp (`timestamp`),
    KEY idx_username (username),
    KEY idx_url (url)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '访问日志表';

-- =============================================================
-- 初始化数据
-- =============================================================

-- 房间：一期固定 2 间、每间容量 6（可行性报告 3.2.1 / 附录 A）
INSERT INTO t_meeting_room (room_name, capacity, equipment, room_status) VALUES
('研讨室A', 6, '白板、电源插座、显示屏', 0),
('研讨室B', 6, '白板、电源插座',         0);

-- 演示账号：5 个虚构账号，统一密码 demo123（仅演示环境）
-- 密码哈希对应明文 demo123，由 Hutool BCrypt（$2a$10）生成。
-- 生产/正式部署必须替换，系统当前没有修改密码功能。
INSERT INTO t_user (username, password, name, role, email) VALUES
('student01', '$2a$10$y/Ip6RS199BMZuBzTbJNlOKj0v/HaL27JsO6FsjJzXcQF1Z/Px.2K', '学生一', 'STUDENT', 'student01@example.com'),
('student02', '$2a$10$y/Ip6RS199BMZuBzTbJNlOKj0v/HaL27JsO6FsjJzXcQF1Z/Px.2K', '学生二', 'STUDENT', 'student02@example.com'),
('student03', '$2a$10$y/Ip6RS199BMZuBzTbJNlOKj0v/HaL27JsO6FsjJzXcQF1Z/Px.2K', '学生三', 'STUDENT', 'student03@example.com'),
('teacher01', '$2a$10$y/Ip6RS199BMZuBzTbJNlOKj0v/HaL27JsO6FsjJzXcQF1Z/Px.2K', '教师一', 'TEACHER', 'teacher01@example.com'),
('admin01',   '$2a$10$y/Ip6RS199BMZuBzTbJNlOKj0v/HaL27JsO6FsjJzXcQF1Z/Px.2K', '管理员', 'ADMIN',   'admin01@example.com');

-- 信用账户：初始 100 分
INSERT INTO t_credit_account (user_id, score, updated_at)
SELECT id, 100, NOW() FROM t_user;

-- 规则版本 1：附加规则默认参数（文档 3.2.2）
INSERT INTO t_rule_version (version_no, effective_from, created_by, note)
VALUES (1, NOW(), NULL, '一期默认参数（可行性报告 3.2.2）');

INSERT INTO t_rule_config (version_id, param_key, param_value)
SELECT v.id, p.k, p.v
FROM t_rule_version v
JOIN (
    SELECT 'AUTO_APPROVE_CREDIT'        AS k, '90'  AS v UNION ALL
    SELECT 'MID_CREDIT_MIN',                    '70'         UNION ALL
    SELECT 'MID_MAX_DURATION_MINUTES',          '120'        UNION ALL
    SELECT 'AUTO_APPROVE_DAILY_LIMIT',          '3'          UNION ALL
    SELECT 'MAX_CONTINUOUS_MINUTES',            '240'        UNION ALL
    SELECT 'MANUAL_REVIEW_HEADCOUNT',           '5'          UNION ALL
    SELECT 'MANUAL_REVIEW_DUE_HOURS',           '24'         UNION ALL
    SELECT 'BASE_RULE_MIN_CREDIT',              '60'
) p
WHERE v.version_no = 1;

-- 设备：2 台虚拟设备，与房间绑定（真机到货后改 secret 与 device_code 即可）
INSERT INTO t_device (device_code, room_id, secret, firmware_ver, online) VALUES
('ROOM-A-01', 1, 'demo-secret-room-a-please-change', 'sim-1.0', 0),
('ROOM-B-01', 2, 'demo-secret-room-b-please-change', 'sim-1.0', 0);

-- =============================================================
-- 初始化完成。
-- 校验：
--   SELECT COUNT(*) FROM t_meeting_room;   -- 2
--   SELECT COUNT(*) FROM t_user;           -- 5
--   SELECT param_key, param_value FROM t_rule_config WHERE version_id = 1;
-- =============================================================
