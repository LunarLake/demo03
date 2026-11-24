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
deploy/mosquitto/  mosquitto.conf（Docker）+ mosquitto-windows.conf（原生）+ ACL + 部署说明
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

### 4.1 前置依赖

| 依赖 | 版本 | 必需性 | 说明 |
|---|---|---|---|
| JDK | 21 | **必需** | 项目带 `mvnw`，不需要单独装 Maven |
| MySQL | 8.0 | **必需** | 端口 3306，库名 `db02` |
| Mosquitto | 2.x | 可选 | 只有接真实门禁（形态 C）才需要 |
| Docker | 任意 | 可选 | 跑 Mosquitto 的方式之一；没有就用原生版 |

```bash
java -version      # 必须是 21
mysql --version
docker --version   # 可选
```

**JDK 不是 21 时**（Windows 默认 `java` 常是 JRE 1.8，IDE 下载的 JDK 一般在
`C:\Users\<用户名>\.jdks\`）：

```bash
export JAVA_HOME="C:/Users/<用户名>/.jdks/ms-21.0.12.1"
export PATH="/c/Users/<用户名>/.jdks/ms-21.0.12.1/bin:$PATH"
```

> Git Bash 下 `PATH` 必须写成 `/c/...` 形式。写成 `C:/...` 不生效，会静默退回系统 JRE，
> 报的错却是"不支持的 class 版本"，很容易查错方向。

### 4.2 第一步：初始化数据库

```bash
mysql -u root -p < sql/schema-v2.sql
```

脚本自带 `CREATE DATABASE IF NOT EXISTS db02` 与 `USE db02`，不用先手工建库。
它会 **DROP 全部业务表后重建**，并写入 2 个房间、2 台设备、5 个账号与初始规则版本。

> ⚠️ 已有数据的环境不要执行本脚本，改用 `sql/migrate-v1-to-v2.sql` 做保数据迁移。

演示账号（统一密码 `demo123`）：

| 账号 | 角色 |
|---|---|
| `student01` / `student02` / `student03` | 学生 |
| `teacher01` | 教师 |
| `admin01` | 管理员 |

### 4.3 第二步：配置数据库密码

`application.yml` 里的密码是 `${DB_PASSWORD:}`，默认为空。本地开发把密码放在
`application-local.yml`（该文件已在 `.gitignore`，不会误提交）：

```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
```

编辑成：

```yaml
spring:
  datasource:
    username: root
    password: 你的密码
```

也可以直接用环境变量 `DB_PASSWORD`。优先级：**环境变量 > `application-local.yml` > `application.yml`**。

### 4.4 第三步：启动

| 形态 | 命令 | 需要 Broker | 能验证什么 |
|---|---|---|---|
| **A 默认** | `./mvnw spring-boot:run` | 否 | 业务全流程；门禁只记日志 `[模拟门禁]` |
| **B 协议全链路** | `./mvnw spring-boot:run -Dspring-boot.run.profiles=mqtt-demo` | 否 | 信封、HMAC、过期、去重、绑定**全部真跑**；控制台见 `[LOCK] PULSE 3000ms` |
| **C 真实 Broker** | `./mvnw spring-boot:run -Dspring-boot.run.profiles=mqtt-broker` | 是 | 接 Mosquitto 与真机 |

**建议先用 A 确认页面能打开，再切 B 证明协议正确。** 形态 B 是"没有硬件时最能说明
问题"的形态，答辩默认用它——它证明的是协议跑得通，而不只是代码编译得过。

三种形态的差异全部落在配置文件中，**换形态只需换 profile，启动前不用 export 任何环境变量**：

| 配置文件 | 对应形态 | 关键配置 |
|---|---|---|
| `application.yml`（默认） | A | `door.mode=log`、`mqtt.mode=inprocess`、模拟器关 |
| `application-mqtt-demo.yml` | B | `door.mode=mqtt`、`mqtt.mode=inprocess`、模拟器开 |
| `application-mqtt-broker.yml` | C | `door.mode=mqtt`、`mqtt.mode=paho`、模拟器关 |

> `mqtt-demo` / `mqtt-broker` 已在 `application.yml` 的 **profile 组**里声明了 `local`，
> 所以**不需要再手写 `local,`**。历史上漏写会让 `application-local.yml` 不加载、
> 报数据库认证失败；现在这条由配置保证，不靠人记。

启动前仍建议清掉可能被注入的端口变量：

```bash
unset SERVER__PORT
```

### 4.5 第四步：确认启动成功

日志出现这两行即可：

```
Tomcat started on port 8080 (http) with context path '/'
Started Demo03Application in 2.4 seconds
```

形态 B 还会多出设备模拟器的输出：

```
[SIM] 设备模拟器已启动：device=ROOM-A-01 failRate=0.0
[SCREEN] status=FREE
```

浏览器打开 `http://localhost:8080`，用 `student01` / `demo123` 登录。

### 4.6 第五步：接真实门禁（可选）

三步：**起 Broker → 后端切 paho → 设备上电**。完整步骤（含无 Docker 时的 Windows 原生
启动、ACL 反向验证、设备侧五步校验、常见坑）见
[`deploy/mosquitto/README.md`](deploy/mosquitto/README.md)，这里只给最小命令。

```bash
# 1) 起 Broker（Docker 方式；没有 Docker 见 deploy/mosquitto/README.md 第 2.2 节）
docker run -d --name mosquitto -p 1883:1883 \
  -v "$PWD/deploy/mosquitto/mosquitto.conf:/mosquitto/config/mosquitto.conf:ro" \
  -v "$PWD/deploy/mosquitto/acl:/mosquitto/config/acl:ro" \
  -v "$PWD/deploy/mosquitto/passwd:/mosquitto/config/passwd:ro" \
  eclipse-mosquitto:2

# 2) 后端切到形态 C —— 一个 profile 就够了
./mvnw spring-boot:run -Dspring-boot.run.profiles=mqtt-broker
```

Broker 地址与账号的默认值都写在 `application-mqtt-broker.yml` 里，
默认 `tcp://127.0.0.1:1883` + `backend/change-me`，与 `deploy/mosquitto/passwd`
中的演示账号一一对应。换环境时用环境变量覆盖即可，不必改文件：

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `MQTT_BROKER_URL` | `tcp://127.0.0.1:1883` | Broker 地址 |
| `MQTT_USERNAME` / `MQTT_PASSWORD` | `backend` / `change-me` | 正式环境必须换 |
| `SIMULATOR_ENABLED` | `false` | 没有真机时可设 `true`，让模拟器接真实 Broker |

`/admin/devices` 页面的"通道"显示 `mqtt:paho`，才说明真的走了 Broker 而不是日志桩。

设备侧只需要对上三个值（决定主题名、签名、Broker 登录）：

| 项 | 值 | 存在哪 |
|---|---|---|
| `deviceCode` | `ROOM-A-01` | `t_device.device_code` |
| HMAC 密钥 | `demo-secret-room-a-please-change` | `t_device.secret` |
| Broker 账号 | `room-a` / `room-a-secret` | `deploy/mosquitto/passwd` |

> 签名密钥和 Broker 账号密码是**两套凭据**，互不影响。`ERR_HMAC` 是前者的问题，
> 连不上 1883 是后者的问题，排障别混。上正式环境时两套都要换掉。

### 4.7 演示前复位

```bash
mysql -u root -p db02 < sql/init-demo.sql
```

清空预约、签到、信用、申诉、日志，保留账号/房间/设备/规则版本。

### 4.8 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `Port 8080 was already in use` | 8080 被别的进程占用 | `SERVER_PORT=18080 ./mvnw spring-boot:run` |
| "端口被占用"但 8080 明明是空的 | 环境注入了 `SERVER__PORT` | `unset SERVER__PORT` 后重启 |
| `Access denied for user 'root'@'localhost'` | `application-local.yml` 缺失或密码错 | 按 4.3 重新生成 |
| 指定 profile 后连不上数据库 | 自建的 profile 没带 `local` | 内置的 `mqtt-demo` / `mqtt-broker` 已自动带 `local`；自建 profile 需写 `local,xxx` |
| `不支持的 class 文件版本` | `PATH` 里的 JDK 是 1.8 | 按 4.1 用 `/c/...` 形式重设 `PATH` |
| 门禁显示离线、开门被拒 | Broker 未连上或设备无心跳 | 见 [deploy/mosquitto/README.md](deploy/mosquitto/README.md)；预约/签到等业务不受影响 |
| 原生 Mosquitto 报 `Address in use` | 安装包自建的服务占着 1883 | 见 [deploy/mosquitto/README.md](deploy/mosquitto/README.md) 第 2.2 节 |
| 设备回执全是 `ERR_HMAC` | 固件里的密钥与 `t_device.secret` 不一致 | 两边逐字节对齐（UTF-8） |
| 开锁指令全是 `ERR_EXPIRED` | 设备时钟偏差超过 TTL（30 秒） | 设备侧 NTP 校时，时区固定东八区 |

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
| [`docs/demo.md`](docs/demo.md) | 5 分钟答辩演示脚本、兜底方案与预备问答 |
| [`docs/小组分工.md`](docs/小组分工.md) | 团队分工、阶段任务与贡献度口径 |
| [`deploy/mosquitto/README.md`](deploy/mosquitto/README.md) | MQTT 部署、主题约定、设备侧五步校验 |
| [`sql/schema-v2.sql`](sql/schema-v2.sql) | 建表 + 演示数据 |

## 8. 已知限制

- **Mosquitto 链路已在本机实测走通**（2026-11-24）：Broker 启动、连接订阅、retained 快照、
  心跳鉴权、回执结算、越权拦截均验证通过；HMAC 规范用 Python 独立复现，与后端签名逐字节一致。
  复现步骤见 [`deploy/mosquitto/README.md`](deploy/mosquitto/README.md)。
- **真机（STM32F103 + ESP8266）尚未接入**：`DeviceSimulator` 与真机执行同一套五步校验，
  但物理开锁、断电重连、时钟漂移等只有在硬件上才能验。硬件到货后按 4.6 走一遍形态 C。
- 超时占用与违规使用靠管理员手工认定，一期无传感器。
- 审计日志只保留 7 天，超过期限的判定依据不再可查（因此判定信息写在业务表上）。
- `PermissionInterceptor`（权限点模型）尚未落地，管理路由仍按角色拦截。
