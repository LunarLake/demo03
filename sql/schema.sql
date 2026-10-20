-- =============================================================
-- demo03 会议室预约系统 — 数据库初始化脚本（建库 + 建表 + 会议室基础数据）
-- =============================================================
-- 目标数据库：MySQL 8.0（db02）
-- 执行方式：
--   mysql -u root -p < sql/schema.sql
--   或在 Navicat 中打开本文件直接运行
--
-- 表结构依据：entity 包实体类 + mapper 包中 @Select 手写 SQL 的列名还原。
-- 字符集统一 utf8mb4 / InnoDB；不建外键约束（MyBatis-Plus 项目惯例，业务层保证一致性）。
--
-- !! 警告：脚本开头会 DROP 同名表，重复执行将清空这 5 张表的数据 !!
--    仅用于全新初始化；生产环境请先备份再执行。
--
-- 本脚本不含业务账号，账号由 sql/init-admin.sql 单独初始化。
-- =============================================================

CREATE DATABASE IF NOT EXISTS db02
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE db02;

SET NAMES utf8mb4;

-- -------------------------------------------------------------
-- 1. t_user —— 用户表
-- -------------------------------------------------------------
DROP TABLE IF EXISTS t_user;
CREATE TABLE t_user (
    id       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户主键ID',
    username VARCHAR(50)  NOT NULL                COMMENT '登录用户名',
    password VARCHAR(100) NOT NULL                COMMENT '登录密码（BCrypt 哈希，$2a$ 前缀共 60 位）',
    name     VARCHAR(50)  NOT NULL                COMMENT '用户真实姓名',
    role     VARCHAR(20)  NOT NULL DEFAULT 'STUDENT'
                                                  COMMENT '角色权限：STUDENT-学生，TEACHER-教师，ADMIN-管理员（审批/控制台）',
    email    VARCHAR(100) DEFAULT NULL            COMMENT '用户邮箱（用于接收抢占/变更通知）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '用户表';

-- -------------------------------------------------------------
-- 2. t_meeting_room —— 会议室表
-- -------------------------------------------------------------
DROP TABLE IF EXISTS t_meeting_room;
CREATE TABLE t_meeting_room (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '会议室主键ID',
    room_name   VARCHAR(100) NOT NULL                COMMENT '会议室名称/房间号（如：中得楼302）',
    capacity    INT          DEFAULT NULL            COMMENT '会议室容量（可容纳人数）',
    equipment   VARCHAR(255) DEFAULT NULL            COMMENT '会议室设备清单',
    room_status TINYINT      NOT NULL DEFAULT 0      COMMENT '会议室状态：0-正常可用，1-维护中',
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '会议室表';

-- -------------------------------------------------------------
-- 3. t_reservation —— 预约表（核心业务表）
--    状态机：reservation_status
--      0=待审批  1=已批准  2=已拒绝  3=教师覆盖取消  4=用户取消  5=超时释放
-- -------------------------------------------------------------
DROP TABLE IF EXISTS t_reservation;
CREATE TABLE t_reservation (
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '预约单主键ID',
    user_id            BIGINT       NOT NULL                COMMENT '申请人ID（关联 t_user.id）',
    room_id            BIGINT       NOT NULL                COMMENT '申请会议室ID（关联 t_meeting_room.id）',
    start_time         DATETIME     NOT NULL                COMMENT '预约使用起始时间',
    end_time           DATETIME     NOT NULL                COMMENT '预约使用结束时间',
    reservation_status TINYINT      NOT NULL DEFAULT 0      COMMENT '审核状态：0-待审批，1-已批准，2-已拒绝，3-教师覆盖取消，4-用户取消，5-超时释放',
    reject_reason      VARCHAR(200) DEFAULT NULL            COMMENT '拒绝原因（管理员拒绝时填写，展示在申请人的"我的预约"中）',
    check_in_code      VARCHAR(10)  DEFAULT NULL            COMMENT '教师批准后系统生成的4位随机签到码',
    attendee_count     INT          DEFAULT NULL            COMMENT '参会人数',
    PRIMARY KEY (id),
    KEY idx_room_time (room_id, start_time, end_time) COMMENT '冲突检测：findConflicting / findByRoomAndDate',
    KEY idx_user_status (user_id, reservation_status)  COMMENT '我的预约分页筛选：findByUserIdWithRoomPage',
    KEY idx_status_start (reservation_status, start_time) COMMENT '待审批列表、超时释放扫描：findApprovals / findExpiredUnchecked'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '预约表';

-- -------------------------------------------------------------
-- 4. t_attendance_record —— 签到记录表
-- -------------------------------------------------------------
DROP TABLE IF EXISTS t_attendance_record;
CREATE TABLE t_attendance_record (
    id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '签到记录主键ID',
    reservation_id BIGINT      NOT NULL                COMMENT '对应的预约单ID（关联 t_reservation.id）',
    user_id        BIGINT      DEFAULT NULL            COMMENT '应当签到的用户ID（关联 t_user.id）',
    check_in_time  DATETIME    DEFAULT NULL            COMMENT '实际扫码签到时间戳',
    attend_status  TINYINT     NOT NULL DEFAULT 0      COMMENT '签到状态：0-未签到/旷到，1-已到/正常签到',
    ip             VARCHAR(50) DEFAULT NULL            COMMENT '签到客户端的远程IP地址',
    PRIMARY KEY (id),
    KEY idx_reservation (reservation_id)  COMMENT '按预约查签到记录：findByReservationId',
    KEY idx_check_in_time (check_in_time) COMMENT '今日签到统计：countTodayCheckIn'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '签到记录表';

-- -------------------------------------------------------------
-- 5. t_log —— 访问日志表
--    注意：timestamp 为列名，涉及该列的 SQL 需加反引号
-- -------------------------------------------------------------
DROP TABLE IF EXISTS t_log;
CREATE TABLE t_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '日志主键ID',
    username    VARCHAR(50)  DEFAULT NULL            COMMENT '触发操作的用户名',
    url         VARCHAR(500) DEFAULT NULL            COMMENT '请求的系统资源路径/URL',
    ip          VARCHAR(50)  DEFAULT NULL            COMMENT '客户端的远程IP地址',
    `timestamp` DATETIME     DEFAULT NULL            COMMENT '操作时间戳',
    PRIMARY KEY (id),
    KEY idx_timestamp (`timestamp`) COMMENT '日志时间分组统计：countByDay',
    KEY idx_username (username)     COMMENT '访问用户分布统计：countByUsername',
    KEY idx_url (url)               COMMENT '访问来源分布统计：countByUrl'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '访问日志表';

-- =============================================================
-- 会议室基础数据
-- 项目无其他初始化数据来源，此处预置示例会议室；
-- 最后一条 room_status=1（维护中）用于演示会议室维护状态。
-- =============================================================
INSERT INTO t_meeting_room (room_name, capacity, equipment, room_status) VALUES
('中得楼302',        20,  '投影仪、白板、空调',          0),
('中得楼305',        12,  '电视、白板',                  0),
('图书馆研讨室A',     8,  '白板、电源插座',              0),
('图书馆研讨室B',     8,  '白板、电源插座',              0),
('行政楼201报告厅',  120, '投影仪、音响、无线麦克风',     0),
('实验楼108',        40,  '投影仪、视频会议设备',        1);

-- =============================================================
-- 初始化完成。5 张表 + 会议室数据就位。
-- 下一步：执行 sql/init-admin.sql 创建 admin / teacher 账号。
-- =============================================================
