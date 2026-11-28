#!/usr/bin/env bash
# =============================================================
# 重建测试库 db02_test（UI/功能测试专用）
# -------------------------------------------------------------
# 结构与种子数据都来自 sql/schema-v2.sql，不依赖开发库 db02 的内容，
# 因此测试看到的是「干净但可用」的状态：2 间房、6 个账号、2 台设备、规则版本 1。
#
# 用法（在项目根目录执行）：
#   bash sql/init-test-db.sh root '你的密码'
#   MYSQL_BIN="/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" \
#     bash sql/init-test-db.sh root '你的密码'
#
# 何时需要重跑：
#   - 首次准备测试环境
#   - schema-v2.sql 有变更
#   - 测试反复跑导致测试库堆积了脏数据
# =============================================================
set -euo pipefail

USER_NAME="${1:-root}"
PASSWORD="${2:-}"
DB="${DB_TEST_NAME:-db02_test}"
MYSQL_BIN="${MYSQL_BIN:-mysql}"

ARGS=(-u "$USER_NAME")
if [ -n "$PASSWORD" ]; then
  ARGS+=("-p$PASSWORD")
fi

echo ">>> 重建测试库：$DB"
"$MYSQL_BIN" "${ARGS[@]}" -e \
  "DROP DATABASE IF EXISTS \`$DB\`; CREATE DATABASE \`$DB\` DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_general_ci;"

# schema-v2.sql 里的 CREATE DATABASE / USE 写死指向 db02，
# 建测试库时要剥掉这两段，库名改由命令行参数指定。
sed -e '/^CREATE DATABASE/,/;$/d' -e '/^USE db02;/d' sql/schema-v2.sql \
  | "$MYSQL_BIN" "${ARGS[@]}" --default-character-set=utf8mb4 "$DB"

COUNT=$("$MYSQL_BIN" "${ARGS[@]}" -N -B "$DB" -e "SELECT COUNT(*) FROM t_meeting_room;")
echo ">>> 完成：$DB 已重建，房间数 = $COUNT"
