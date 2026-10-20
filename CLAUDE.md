# CLAUDE.md

本文件给后续维护者（包括 AI）提供**开发约定**。
业务规则（状态机、时段窗口、审核优先级、信用与申诉口径）以 `docs/重构改造计划.md` 为准，
本文只记录"容易踩的工程问题"，不复制业务细节 —— 复制必然会过期。

## 构建与运行

```bash
./mvnw test                 # 全量测试（当前 300+ 条）
./mvnw spring-boot:run      # 默认 profile（门禁为 log 模拟）
./mvnw -o test              # 离线构建（依赖已在本地 m2）
```

两个环境坑，踩过多次：

1. **命令行指定 profile 会顶掉默认 `local`**，导致 `application-local.yml` 的数据库密码不加载，
   表现为 `Failed to obtain JDBC Connection`。必须写成 `local,xxx`。
2. **`SERVER__PORT` 环境变量**（WorkBuddy 会注入）会顶掉 `server.port` 且该端口已被占用，
   启动前 `unset SERVER__PORT` 或显式 `--server.port=8080`。

改了主代码的公开 API 后，增量编译会跳过旧 `test-classes`，看到的是假绿。
需要看真实破损面时先 `rm -rf target`。

数据库：MySQL `db02`。账号密码不要入库——复制 `application-local.yml.example` 为
`application-local.yml`，或用 `DB_USERNAME` / `DB_PASSWORD` 环境变量。

## 分层

```
controller → service（接口）→ service/impl → mapper（MyBatis-Plus BaseMapper）→ MySQL
```

- 无 DTO/VO 层，entity 直接进模板（注意：**视图对象不要用 record**，Thymeleaf/SpEL 走 JavaBean
  约定，record 的 `label()` 不是 `getLabel()`，取不到值）。
- 无 XML mapper，自定义 SQL 用 `@Select` 注在接口上。
- 构造器注入：service 用 `@RequiredArgsConstructor`，controller/拦截器用显式构造器。
- entity 用 Lombok `@Data` + `@TableName` / `@TableId`；列名靠驼峰转下划线，不额外标注。
- 时间一律来自注入的 `Clock`，测试用 `support/MutableClock` 拨动；禁止直接 `LocalDateTime.now()`。

## 测试约定

- Service 单测用 Mockito mock 数据层；MyBatis-Plus 的 `insert` / `updateById` 有重载，
  **必须用类型化匹配器** `any(Reservation.class)`，裸 `any()` 会报"引用不明确"。
- `@BeforeEach` 里给部分用例用的桩要 `lenient().when(...)`，否则 strict stubs 会报未使用。
- standalone MockMvc 需要 `InternalResourceViewResolver`（`/WEB-INF/views/` + `.html`）。
  多个 controller 要挂同一个 MockMvc —— 分别 `standaloneSetup` 会各建一个 ServletContext，
  `MockHttpSession` 跨上下文用会报 "Cannot reinitialize with different application context"。
- 真库集成测试（`@SpringBootTest`）依赖 `sql/schema-v2.sql` 建好的 `db02`，
  需要预置账号与设备；改 schema 时同步改这个文件。
- **不要原地修改 mapper 返回的集合**：`List.of(...)` 之类不可变集合会抛
  `UnsupportedOperationException`，先 `new ArrayList<>(...)` 再排序。

## 数据库与并发要点

- 占用仲裁靠 `t_reservation_slot` 的唯一键 `(room_id, slot_date, slot_index)`，
  不是分布式锁。撞键即整条 INSERT 回滚，业务层只需把异常翻译成用户提示。
- 状态迁移一律走 `transit(id, from, to, ...)` 这类**带前置状态的条件更新**，
  用受影响行数判断输赢；禁止 `getById → set → updateById` 三段式。
- 信用分是账本的函数：`score = clamp(100 + SUM(delta))`，缓存列只是派生值。
- 定时任务都在 `task/` 包，全部注入 `Clock`，异常必须吞掉记日志，不能让调度线程死掉。

## Thymeleaf 坑

- 不要嵌套 `${}`，用管道字面量：`th:text="|${r.roomName}（${r.capacity} 人）|"`。
- 复杂 JS 放 `static/js/` 外部文件，内联 `<script>` 容易触发解析问题。
- `th:inline="javascript"` 才能用 `/*[[...]]*/`。
- 自定义 SQL 返回 `List<Map<String,Object>>` 时，Map 的键是**原始列名**（`reservation_status`），
  用 `th:text="${r['reservation_status']}"`；只有 `AS` 起的别名（`roomName`）能点号访问。

## 权限与安全

- 无 Spring Security：`RoleInterceptor`（登录）+ `AdminInterceptor`（角色）+ `CsrfInterceptor`（同步令牌）。
- 所有 POST 必须带 `_csrf`（表单参数或 `X-CSRF-TOKEN` 头），否则被重定向到 `/`。
- 密码 BCrypt；注册强制 `role=STUDENT`；注册入口受 `app.registration.enabled` 控制，默认关闭。
- 设备通道的安全边界在 MQTT 信封（HMAC + 有效期 + msgId 去重 + 预约绑定），
  详见 `deploy/mosquitto/README.md`。

## 文档索引

| 文档 | 内容 |
|---|---|
| `README.md` | 项目总览、启动方式、页面清单、已知限制 |
| `docs/重构改造计划.md` | **业务规则权威来源**：状态机、单元模型、审核优先级、分阶段进度 |
| `docs/acceptance.md` | AC-01~AC-30 逐条对照 |
| `docs/demo.md` | 5 分钟演示脚本 |
| `deploy/mosquitto/README.md` | MQTT 部署与设备端五步校验 |
