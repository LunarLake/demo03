# 存量测试盘点（P0-T03）

> 基线：`tag v1-baseline`（commit 6d3ac9d）。执行 `mvn test` 得 **131 个测试全绿**。
> 本表用于 P2 阶段"哪些测试能留、哪些必须重写、哪些应当废弃"的决策依据。
>
> 判定标准：
> - **保留** —— 断言的是与状态机/时段模型无关的行为（鉴权、CSRF、异常封装、密码哈希）。
> - **重写** —— 业务语义已变（状态码、签到窗口、占用模型、审批路径），断言必须按新规则重写，**不允许为了变绿删断言**。
> - **废弃** —— 被测对象在新架构中不存在（如 `AdminInterceptor` 的路径前缀放行）。

| # | 测试类 | 用例数 | 覆盖内容 | 处置 | 说明 |
|---|---|---|---|---|---|
| 1 | `common.CsrfInterceptorTest` | 8 | CSRF 同步令牌、POST 校验、排除路径 | **保留** | 与业务模型无关 |
| 2 | `common.GlobalExceptionHandlerTest` | 4 | `/api/*` 返回 `ApiResponse`，页面请求重定向 | **保留** | 后续新增 `/api/*` 端点复用同一封装 |
| 3 | `common.InterceptorsTest` | 7 | `RoleInterceptor` / `LogInterceptor` / `AdminInterceptor` | **部分重写** | `AdminInterceptor` 将被 `PermissionInterceptor` 取代，对应 2–3 条用例废弃，其余保留 |
| 4 | `controller.AdminOpsTest` | 9 | 审批列表、批准/拒绝、房间增删 | **重写** | 批准动作新增"重检单元占用 + 插 slot + 发码"，拒绝新增 `decision_path` |
| 5 | `controller.ControllerLayerTest` | 27 | 全量路由、参数校验、跳转 | **大面积重写** | 路由表变更（`/reservation/new` → `/reservation/board`），新增 6 个端点 |
| 6 | `controller.ReservationFeedbackTest` | 7 | 申请/签到失败的用户提示文案 | **重写** | 提示语随"待审批不占单元""签到窗口 5 分钟"变化 |
| 7 | `Demo03ApplicationTests` | 1 | Spring 上下文加载 | **保留** | 每阶段都靠它兜住装配错误 |
| 8 | `service.impl.MeetingRoomAndLogServiceTest` | 8 | 房间删除守卫、日志统计 | **部分重写** | 房间删除需新增"占用单元"校验；日志统计保留 |
| 9 | `service.impl.ReservationServiceImplTest` | 32 | `apply/approve/reject/checkIn/cancel` 全路径 | **整体重写** | 五个方法全部重写；这是 P2 的主要工作量 |
| 10 | `service.impl.UserServiceImplTest` | 13 | 注册强制 STUDENT、BCrypt、登录 | **保留** + 加 1 条 | 新增"注册开关关闭时拒绝"用例（Q4） |
| 11 | `service.LoginAttemptServiceTest` | 6 | 5 次失败锁 15 分钟 | **保留** | 签到码限流逻辑不变 |
| 12 | `task.ReservationExpiryTest` | 3 | 60 秒僵尸释放 | **重写** | 判定条件由"开始 +15 分钟"改为"开始 +5 分钟"；新增提醒与到期结束 |
| 13 | `template.TemplateRenderingTest` | 6 | 模板渲染（消息样式、表单显隐） | **重写** | `reservation-new` 模板将移除，新增 `board` / `credit` / `appeal` 页面断言 |
| 14 | `domain.ReservationStatusTest` | 8 | 7×7 迁移矩阵、废弃码 4 | **新增**（P1 已完成） | 测试独立维护一份期望表，不复用实现 |
| 15 | `domain.SlotSchemaTest` | 16 | 对齐/时长/提前天数/开放时段 | **新增**（P1 已完成） | 覆盖 AC-01 / AC-02 / AC-03 |

## 汇总

| 处置 | 用例数 | 占比 |
|---|---|---|
| 保留 | 32 | 24% |
| 部分重写 | 15 | 11% |
| 重写 | 84 | 64% |
| 新增（已交付） | 24 | — |
| **基线合计** | **131** | 100% |

## P2 施工提示

1. **先加断言再改实现**：对每个被重写的方法，先按新规则写出期望行为（尤其并发与边界），再动 Service，避免"改完再补测试"变成"照着实现写测试"。
2. `ReservationServiceImplTest` 的 32 条用例中，约 20 条断言的是旧窗口（`start-10min ~ start+15min`）与旧占位规则（`IN (0,1)`），这些属于**语义已变**，重写时要写清新的边界值，不要简单改数字。
3. `ControllerLayerTest` 建议按"预约 / 审批 / 签到 / 上诉 / 设备"五组拆分，单个类 27 条已经太大。
4. 任何一条用例若在 P2 中删除，必须在 commit message 里写明"为什么这个行为不再存在"，便于答辩时解释。
