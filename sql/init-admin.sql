-- =============================================================
-- 管理员 / 教师账号初始化脚本（数据库 db02，表 t_user）
-- =============================================================
-- 密码使用 BCrypt 哈希（与 UserServiceImpl 相同的 Hutool BCrypt，$2a$ 前缀、10 轮）。
-- 重新生成哈希的方法（项目 Maven 仓库已含 hutool-all-5.8.4.jar）：
--   jshell --class-path "%USERPROFILE%\.m2\repository\cn\hutool\hutool-all\5.8.4\hutool-all-5.8.4.jar"
--   然后执行：
--     System.out.println(cn.hutool.crypto.digest.BCrypt.hashpw("你的密码"));
--   BCrypt 每次生成的盐值不同，任意一次输出都是合法哈希。
--
-- ★ 安全提示：系统目前没有修改密码功能，以下默认密码（admin123 / teacher123）
--   仅用于本地开发环境。部署前请用上面的方法生成随机强密码并替换哈希。
--   任何知道这些默认密码的人都能直接获得管理员权限。
--
-- 执行方式：mysql -u root -p db02 < sql/init-admin.sql
-- =============================================================

-- 创建默认账号（INSERT IGNORE 幂等：已存在同名用户时跳过，不报错）
INSERT IGNORE INTO t_user (username, password, name, role, email) VALUES
('admin',   '$2a$10$qL.XuhmMsy/MGlIO.58UEuQbv7egw0XB052DxqEEKbEP9TGVLbEAm', '系统管理员', 'ADMIN',   'admin@example.com'),
('teacher', '$2a$10$I206QujfeKQ9zfTxXOCmP.6ZQg8mrILpYUfzsbDpzI7.GFSaqg7PS', '示例教师',   'TEACHER', 'teacher@example.com');

-- 将既有承担审批职责的教师账号迁移为管理员（按需执行，替换用户名为实际账号）：
-- UPDATE t_user SET role = 'ADMIN' WHERE username = 'xxx';
