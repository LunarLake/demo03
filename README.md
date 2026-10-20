# 智慧研讨室预约与门禁管理系统

> 从"纯软件预约"升级为**软硬件结合**：手机 Web 预约 → 服务端判定 → MQTT → STM32 门禁。
> 本文是项目总览；施工细节见 [`docs/重构改造计划.md`](docs/重构改造计划.md)，
> 验收对照见 [`docs/acceptance.md`](docs/acceptance.md)。

## 1. 项目概述

### 1.1 解决什么问题

原来的系统有三个绕不开的痛点：

| 痛点 | 根因 | 现在的做法 |
|---|---|---|
| "看着能约，提交被拒" | 待审批的申请也占着时段，同一时段不能多人申请 | **待审批不占单元**，只有已通过/已签到才占用 |
| 时段粒度混乱 | 前端吸附 15 分钟，后端接受任意时间 | 后端强校验 + **单元化存储**（每格唯一键仲裁） |
| 签到靠自觉 | 没有物理校验，代签/爽约无法约束 | 现场屏幕码 + 5 分钟窗口 + **门禁开锁** + 信用分 |

### 1.2 核心设计

1. **15 分钟单元**：08:00–22:00 共 56 格/间，单次 1–16 格（15 分钟–4 小时），最多提前 7 天。
2. **七态状态机**：待审批 / 已通过 / 已拒绝 / 已取消 / 超时释放 / 已签到 / 已结束。
3. **三层审核**：基础规则（内置 6 条）→ 附加规则（8 个可调参数，版本化）→ 人工审核。
4. **信用分 + 申诉**：爽约 −10、再犯加倍；申诉 7 日内、每项一次、复核人不得是原审核人。
5. **一次性开锁指令**：`msgId` 幂等 + `expiresAt` 过期 + `reservationId` 绑定，设备端五步校验。

## 2. 角色与能力

### 学生（STUDENT）

- 在**排期板**上点选时段（点第一格选开始、第二格选结束），提交后按规则自动通过或转人工
- 查看签到码、门禁开锁状态、信用分与流水
- 现场输入屏幕码签到（窗口 `[开始, 开始+5分钟)`），签到成功自动开锁
- 已签到后可在有效期内**再次开门**（不要求重复输码）
- 对拒绝/取消/超时释放与扣分提交申诉

### 教师（TEACHER）

- 可**覆盖尚未开始**的学生预约（整笔取消 + 释放单元 + 撤销签到码）
- 教师之间冲突直接拒绝；已开始的预约不可覆盖
- 教师预约在单元无冲突时自动通过

### 管理员（ADMIN）

- 审批转人工的申请、查看"为什么进人工"（判定路径与命中规则）
- 规则配置：调整 8 个附加参数，每次发布生成新版本，历史判定不受影响
- 申诉复核（不能复核自己提的，也不能复核自己批过的）
- 设备管理：在线状态、最近指令、测试开锁
- 手工认定超时占用（−5）与违规使用（−20）——一期没有传感器，只能人工认定

## 3. 技术架构

```
手机 Web（Thymeleaf + 原生 JS）
        │
        ▼
Spring Boot 3.5 ── MySQL 8.0（16 张表）
        │
        ▼  MQTT（Eclipse Paho）
   Mosquitto ──► STM32F103C8T6 + ESP8266
                  ├─ SSD1306 显示签到码/剩余时间
                  ├─ 蜂鸣器
                  └─ 12V 电控柜锁
```

| 层次 | 技术 |
|---|---|
| Web | Spring MVC + Thymeleaf、Bootstrap 5、原生 JS（无前端框架） |
| 持久化 | MyBatis-Plus 3.5.15 + MySQL 8.0 |
| 设备 | MQTT 3.1.1（QoS1）、HMAC-SHA256 信封 |
| 其他 | Hutool（BCrypt / HMAC）、ECharts（管理端看板） |

### 3.1 目录结构

```
src/main/java/com/wyc/demo03/
├─ controller/     页面与 JSON 接口
├─ service/        业务（Reservation / Access / Credit / Appeal / RuleConfig / RoomBoard …）
├─ rule/           规则引擎 + 6 条基础规则
├─ domain/         纯计算与枚举（ReservationStatus / SlotSchema / SlotUnit …）
├─ entity/ mapper/ MyBatis-Plus
├─ mqtt/           信封、HMAC、网关（Paho / 进程内）、门禁实现
├─ simulator/      设备模拟器（顶替 STM32）
├─ device/         门禁与屏幕通知端口
├─ task/           定时收敛与日志滚动
└─ common/         拦截器、异常处理、统一响应

sql/               schema-v2.sql / migrate-v1-to-v2.sql / init-demo.sql
deploy/mosquitto/  mosquitto.conf + ACL + 部署说明
docs/              施工图、验收对照、测试盘点
scripts/           mqtt-e2e.sh（门禁链路证据收集）
```

### 3.2 数据库（16 张表）

| 分组 | 表 | 说明 |
|---|---|---|
| 基础 | `t_user`、`t_meeting_room`、`t_log` | 用户、房间、访问日志 |
| 预约 | `t_reservation`、`t_reservation_slot`、`t_checkin_code`、`t_attendance_record` | 单据、**占用单元**、签到码、签到记录 |
| 规则 | `t_rule_version`、`t_rule_config` | 规则版本与参数快照 |
| 信用 | `t_credit_account`、`t_credit_log` | 账户（缓存）与流水（账本） |
| 申诉 | `t_appeal` | 工单，独立于预约状态机 |
| 设备 | `t_device`、`t_device_command`、`t_access_log` | 设备、一次性指令、开门记录 |
| 审计 | `t_audit_log` | 业务审计（与访问日志分开） |

关键约束：`t_reservation_slot` 的 `UNIQUE KEY (room_id, slot_date, slot_index)`。
并发批准时的仲裁完全靠它——撞键即整条 INSERT 回滚，不会出现"插了一半"的占用。

## 4. 快速开始

### 4.1 环境

- JDK 21（项目带 `mvnw`，无需另装 Maven）
- MySQL 8.0，端口 3306

### 4.2 初始化数据库

```bash
mysql -u root -p < sql/schema-v2.sql
```

演示账号（统一密码 `demo123`）：

| 账号 | 角色 |
|---|---|
| `student01` / `student02` / `student03` | 学生 |
| `teacher01` | 教师 |
| `admin01` | 管理员 |

### 4.3 启动

```bash
# Windows Git Bash 下指定 JDK（本机默认 java 可能是 JRE 1.8）
export JAVA_HOME="C:/Users/<用户名>/.jdks/ms-21.0.12.1"
export PATH="/c/Users/<用户名>/.jdks/ms-21.0.12.1/bin:$PATH"

./mvnw spring-boot:run
```

> ⚠️ 若环境中存在 `SERVER__PORT` 变量（WorkBuddy 会注入），它会顶掉 `server.port`
> 并导致"端口被占用"的误报。启动前 `unset SERVER__PORT` 或显式加 `--server.port=8080`。

### 4.4 三种门禁形态

| 形态 | 启动方式 | 说明 |
|---|---|---|
| **A 默认** | `./mvnw spring-boot:run` | 门禁只记录不执行，日志标 `[模拟门禁]` |
| **B 协议全链路** | `-Dspring-boot.run.profiles=local,mqtt-demo` | 进程内总线，无需 Broker；控制台可见 `[LOCK] PULSE 3000ms` |
| **C 真实 Broker** | `MQTT_MODE=paho DOOR_MODE=mqtt` + Mosquitto | 见 [`deploy/mosquitto/README.md`](deploy/mosquitto/README.md) |

> ★ 形态 B **必须同时带 `local`**：命令行指定 profile 会顶掉默认 profile，
> 导致 `application-local.yml` 里的数据库密码不加载。

### 4.5 演示前复位

```bash
mysql -u root -p db02 < sql/init-demo.sql
```

清空预约、签到、信用、申诉、日志，保留账号/房间/设备/规则版本。

## 5. 页面清单

| 路径 | 说明 |
|---|---|
| `/reservation/board` | **预约排期板**（2 房间 × 56 格，两段点选） |
| `/my-reservations` | 我的预约（7 态徽章、签到、开门、取消、申诉入口） |
| `/credit` | 信用分与流水 |
| `/appeal`、`/appeal/new` | 我的申诉与提交 |
| `/reservation/approve-list` | 审批（含"为什么进人工"、24h 超办预警） |
| `/appeal/review` | 申诉复核台 |
| `/admin/rules` | 附加规则配置与版本历史 |
| `/admin/devices` | 设备管理（在线状态、指令记录、测试开锁） |
| `/reservation/detail/{id}` | 预约详情（时间线、开门记录、人工认定） |

移动端：<768px 侧边栏变抽屉，排期板横向滑动、操作栏吸底。

## 6. 测试

```bash
./mvnw test          # 295 tests
```

| 层次 | 代表用例 |
|---|---|
| 领域单测 | 7×7 状态迁移矩阵、15 分钟单元边界、规则判定顺序 |
| 业务单测 | 提交/审批/取消/签到/信用/申诉/开门（Mock 数据层） |
| 真库集成 | 唯一键仲裁、并发批准冲突、教师覆盖、申诉对冲 |
| MQTT 协议 | 逐字段篡改、重放、过期、跨预约绑定、伪造回执 |
| 时间旅行 | 用可拨动 Clock 把一整天压进毫秒 |
| 页面冒烟 | 完整 Spring 上下文渲染 12 个页面 + 排期板接口契约 |

## 7. 文档索引

| 文档 | 内容 |
|---|---|
| [`docs/重构改造计划.md`](docs/重构改造计划.md) | 施工图：冲突清单、数据模型、状态机、分阶段任务与**实际进度记录** |
| [`docs/acceptance.md`](docs/acceptance.md) | AC-01 ~ AC-30 逐条对照与手工验收清单 |
| [`docs/test-inventory.md`](docs/test-inventory.md) | 基线测试盘点与 P2 重写结果 |
| [`deploy/mosquitto/README.md`](deploy/mosquitto/README.md) | MQTT 部署、主题约定、设备侧五步校验 |
| [`sql/schema-v2.sql`](sql/schema-v2.sql) | 建表 + 演示数据 |

## 8. 已知限制

- 真实 Mosquitto 与 STM32 未在本机联调（`PahoMqttGateway` 只有编译保证，
  协议逻辑由进程内实现全覆盖）；硬件到货后需按 `deploy/mosquitto/README.md` 走一遍形态 C。
- 超时占用与违规使用靠管理员手工认定，一期无传感器。
- 审计日志只保留 7 天，超过期限的判定依据不再可查（因此判定信息写在业务表上）。
- `PermissionInterceptor`（权限点模型）尚未落地，管理路由仍按角色拦截。
