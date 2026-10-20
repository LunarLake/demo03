#!/usr/bin/env bash
# =============================================================
# 门禁链路端到端见证脚本（P5-T06）
# =============================================================
# 作用：以 MQTT 全链路模式启动后端 + 设备模拟器，把协议报文与开锁日志
#       落到 target/mqtt-evidence.log，用于答辩留证。
#
# 为什么不是全自动：签到需要登录（验证码），不适合脚本冒烟。
# 数据层链路已由 MqttAccessIntegrationTest 自动覆盖，这个脚本负责"人看见"。
#
# 用法：
#   1. 启动本脚本
#   2. 浏览器打开 http://127.0.0.1:8081 ，用 student01 / demo123 登录
#   3. 填验证码，找一个当前时间段的预约并点"签到"（需要先有一笔已通过的预约）
#   4. 观察终端里的 [LOCK] / [SIM] / 收到回执 三类日志
#   5. Ctrl+C 结束，证据已写入 target/mqtt-evidence.log
# =============================================================
set -u

cd "$(dirname "$0")/.." || exit 1

PORT="${PORT:-8081}"
LOG="target/mqtt-run.log"
EVIDENCE="target/mqtt-evidence.log"
mkdir -p target

# 本机 WorkBuddy 会注入 SERVER__PORT，会顶掉 server.port
unset SERVER__PORT

echo "== 启动（profile=local,mqtt-demo, port=$PORT） =="
echo "   门禁通道：MQTT（进程内总线，无需 Broker）"
echo "   设备模拟器：ROOM-A-01"
echo

JAVA_HOME="${JAVA_HOME:-C:/Users/$USERNAME/.jdks/ms-21.0.12.1}"
export JAVA_HOME
export PATH="/c/Users/$USERNAME/.jdks/ms-21.0.12.1/bin:$PATH"

./mvnw -o spring-boot:run \
  -Dspring-boot.run.arguments="--server.port=$PORT --spring.profiles.active=local,mqtt-demo" \
  > "$LOG" 2>&1 &
APP_PID=$!

cleanup() {
  echo
  echo "== 收集证据 =="
  {
    echo "# 门禁链路证据 $(date '+%Y-%m-%d %H:%M:%S')"
    echo "## 启动与订阅"
    grep -E "已订阅设备回执|设备模拟器已启动" "$LOG" || echo "（未找到启动日志）"
    echo
    echo "## 指令下发（后端）"
    grep -E "开锁指令已发布" "$LOG" || echo "（没有下发过开锁指令）"
    echo
    echo "## 设备侧执行（模拟器）"
    grep -E "\[LOCK\]|\[BUZZ\]|\[REMIND\]|\[SCREEN\]|丢弃指令|故障注入" "$LOG" || echo "（设备侧无记录）"
    echo
    echo "## 回执结算（服务端）"
    grep -E "收到回执|忽略迟到" "$LOG" || echo "（没有收到回执）"
    echo
    echo "## 异常"
    grep -E "ERROR" "$LOG" | grep -v "shutdown" || echo "（无）"
  } > "$EVIDENCE"
  echo "   已写入 $EVIDENCE"
  kill "$APP_PID" 2>/dev/null
  wait "$APP_PID" 2>/dev/null
  exit 0
}
trap cleanup INT TERM

# 等待启动
for i in $(seq 1 60); do
  if grep -q "Started Demo03Application" "$LOG" 2>/dev/null; then
    echo "== 已启动，等待你在浏览器完成签到 =="
    echo "   http://127.0.0.1:$PORT   （student01 / demo123）"
    break
  fi
  sleep 1
done

# 实时跟踪关键日志
tail -f "$LOG" | grep --line-buffered -E "\[SIM\]|\[LOCK\]|\[SCREEN\]|开锁指令已发布|收到回执|丢弃指令"
