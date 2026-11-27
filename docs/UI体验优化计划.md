# UI/UX 体验优化计划

> 状态：待实施（仅计划，未改动代码）
> 编写日期：2026-11-27
> 适用范围：`src/main/resources/templates/**`、`src/main/resources/static/**`、`RoomBoardService`（仅新增字段）

---

## 0. 诊断

功能层面已经完整，问题集中在两点：

1. **呈现层没有"共用件"。** 18 个页面各自复制粘贴提示条、空态、分页、确认框，导致同一件事在不同页面长得不一样。风格不统一不是审美问题，是复制粘贴的必然结果。
2. **规则只活在后端。** `RuleEngine` 是唯一规则源（这个设计是对的），但前端拿不到"本次会话的约束快照"，于是所有规则冲突只能在提交后才揭晓。

两条诊断分别对应本轮的两条主线：**建立共用件**、**下发约束快照**。

---

## 1. 问题清单

### P0 — 阻断主要流程

| 编号 | 页面 | 问题 | 证据 | 影响 |
|---|---|---|---|---|
| U01 | `/reservation/board` | 网格固定 56 格 × 30px = 1680px，加房间列 104px 共 **1784px**。1440 屏内容区约 1132px，必须横滚约 650px | `board.css:9-11,26`；`min-width: max-content` | 全天排期一屏看不完，"同一时间比较多个房间"的核心价值被削弱 |
| U02 | `approve-list` / `logs` / `my-reservations` | 表格未包 `.table-responsive`，手机上整页横向溢出（连侧栏一起被撑破） | 三文件 `grep table-responsive` 均为 0；`admin.css:160` 的规则写了但没被使用 | 手机端三张核心列表全部破版 |
| U03 | `/rooms` | 卡片上的"预约"按钮跳 `/reservation/board` **不带 roomId**，用户还得在那张宽表里自己找回来 | `rooms.html` `th:href="@{/reservation/board}"`；`board` 路由签名无 roomId 参数 | 从房间进入排期板等于重新开始找，抵消了入口的意义 |
| U04 | `/profile` | 提示条硬编码 `alert-info`，成功与失败都是蓝色 | `profile.html:17`；其他页面已按 `infoType` 分色 | 改密码成功也看不到绿色，与全站提示语义冲突 |
| U05 | 全站 20 个 submit 按钮 | 仅排期板有提交中状态，其余点击后无禁用、无 loading | `grep type="submit"` = 20，仅 board.js 有按钮状态切换 | 慢网下重复提交，预约/审批场景会产生重复记录 |

### P1 — 一致性与可理解性

| 编号 | 页面 | 问题 | 证据 |
|---|---|---|---|
| U06 | 全站 | 提示条 6 种写法：带 dismissible + infoType / 带 dismissible 但硬编码色 / 裸 alert 无关闭 / 独立错误区 | 18 个模板各有一份，格式各不相同 |
| U07 | 5 处 | 危险操作使用原生 `confirm()`，而审批页用 Bootstrap modal，两套确认体验 | `device-admin` / `my-reservations` / `reservation-detail`×2 / `rooms` |
| U08 | 全站 | 表单栅格三套写法：`row g-3 + col-md-6` / 裸 `mb-3` / 表格单元格内联 width | `rule-config` vs `appeal-new` vs `appeal-review:72,81` |
| U09 | 全站 | 按钮语义混乱：`btn-success` 被用作"新增会议室"主按钮，`btn-primary` 又被用作"提交" | `rooms.html` 新增按钮 |
| U10 | 7 处空态 | 措辞不一（"暂无待审批预约"/"没有待复核工单"/"暂无预约记录"…），且均无引导动作 | 7 处 `text-center text-muted py-4` |
| U11 | 排期板图例 | 6 项中"可预约 `#ecfdf5`"、"已过去 `#f9fafb`"、"已占用 `#e5e7eb`"三色接近；"有人待审批 `#fffbeb` 虚线"与"我的待审 `#fef3c7` 实线"仅差一档黄 | `board.html:43-48`、`board.css:100-170` |
| U12 | `/appeal/review` | 复核表单塞进 7 列表格的单元格：select 110px + input 120px + 按钮，极拥挤；"对象"列只显示 `#12` 不可点 | `appeal-review.html:47,72,81,47` |
| U13 | 2 处 | 分页块重复实现 | `logs.html` / `my-reservations.html` |
| U14 | 全站 | 必填标记三种风格：label 里写"（必填）"/ 完全没标 / 只有 `required` 属性 | `appeal-new:34`、`profile`、`rule-config` |
| U15 | 样式层 | 两套色板并存：`admin.css` 用 slate/sky（`#0ea5e9`），`board.css` 用 Tailwind 灰绿（`#ecfdf5`/`#e5e7eb`） | 两文件 `:root` 无共享变量 |
| U16 | 全站 | 内联 `style` 26 处，其中 `board.html` 11 处、硬编码 width 一屏可见 | 见各文件 |

### P2 — 打磨

| 编号 | 问题 |
|---|---|
| U17 | 表格时间/数字列未用 `tabular-nums`，列宽随内容跳动（仅 `.sel-time` 用了） |
| U18 | 同一操作区 `btn-sm` 与标准 `btn` 混排（rooms 搜索区） |
| U19 | `dashboard.html`、`login.html` 各带独立 `<style>` 块，全局调色会漏改 |
| U20 | 无统一返回/面包屑，详情页与申诉页各自写"返回" |
| U21 | `:focus-visible` 仅 `board.css` 有一处，其余键盘焦点不可见 |

---

## 2. 改造方案

### 2.1 先修 P0（改动小、收益立刻可见）

#### U01 排期板收窄 —— 方案 A（推荐）

**做法**：从"固定格宽 + 横向滚动"改为"铺满容器 + 自适应格宽"，**保持 56 格不变**。

```css
.board-grid {
  grid-template-columns: var(--head-w) repeat(56, minmax(0, 1fr));
  min-width: 0;            /* 移除 max-content */
}
```

- 1440 屏：`(1132 - 104) / 56 ≈ 18.4px` 每格，全部可见
- 1920 屏：约 27.8px，比现在更宽松
- 1280 屏：约 16.4px，仍可点
- `< 1024px`：格子低于 14px 时回落到横向滚动

配套调整：
- `.slot { margin: 1px }` → 容器 `gap: 1px`，避免 1px 边距吃掉 10% 宽度
- 列头两层：上层每 2 小时标一次，下层用短刻度区分半小时
- 横向滚动时加 `scroll-snap-type: x proximity` 吸附整点

**精度补偿**：选中时段后在底部操作栏增加 `−15 分 / +15 分` 微调按钮，这样窄格不影响精度。

**为什么不用方案 B（窄屏降为 30 分钟粒度）**：需要支持"15 分钟预约只占半格"的渲染，`board.js` 要改成绝对定位子块，复杂度显著上升，而收益只是窄屏更好点。列为二期可选。

#### U02 表格补 responsive

三个模板的 `<table>` 外包一层 `<div class="table-responsive">`。`admin.css` 已有对应规则（含移动端 `min-width: 640px`），补类即可。

#### U03 房间定位

- `board` 路由增加 `@RequestParam(required = false) Long roomId`
- 传入时通过 `data-focus-room` 传给前端，渲染后滚动定位到该行并加 2 秒高亮
- `rooms.html` 的"预约"按钮改为 `@{/reservation/board(roomId=${room.id})}`

#### U04 / U05

- `profile.html` 提示条改用 `infoType`，与 `rooms.html` 对齐
- 全站表单提交统一加"点击即禁用 + 文案改为提交中…"，用一小段公共 JS 统一挂载（`static/js/form-guard.js`），避免逐页写

### 2.2 建立共用件（解决"风格统一"的根）

**不要逐页调样式**——18 个页面调完，下次加页面又会漂。建立两个文件：

#### `static/ui-tokens.css`

```css
:root {
  --ui-primary: #0ea5e9;
  --ui-success: #16a34a;
  --ui-warning: #d97706;
  --ui-danger:  #dc2626;
  --ui-surface: #ffffff;
  --ui-surface-alt: #f8fafc;
  --ui-border: #e5e7eb;
  --ui-text: #111827;
  --ui-text-muted: #6b7280;
  --ui-radius: 0.75rem;
  --ui-radius-sm: 0.4rem;
  --ui-gap-1: 0.25rem;  /* 4px  */
  --ui-gap-2: 0.5rem;   /* 8px  */
  --ui-gap-3: 0.75rem;  /* 12px */
  --ui-gap-4: 1rem;     /* 16px */
  --ui-gap-6: 1.5rem;   /* 24px */
}
```

把 `admin.css` 的散落色值与 `board.css` 的 Tailwind 色板统一到这里，两文件改为引用变量。**这一条是 U15 的解法，也是后续所有改动不跑偏的前提。**

#### `templates/fragments/ui.html`

只封装 4 个高频件（不要过度封装，Thymeleaf 片段参数化能力有限）：

| 片段 | 用途 | 替代对象 |
|---|---|---|
| `alert(type, msg)` | 顶部提示条 | U06 的 18 份重复 |
| `empty(text, actionText, actionHref)` | 空态 + 引导动作 | U10 的 7 处 |
| `pagination(page, baseUrl, params)` | 分页 | U13 的 2 份 |
| `confirmModal(id, title, body, confirmText, variant)` | 危险操作确认 | U07 的 5 处 confirm() |

**约定**（写进 `CLAUDE.md`）：
- 新模板禁止内联 `style`，颜色只用 `--ui-*` 变量
- 主操作一律 `btn-primary`，危险操作 `btn-danger` + 确认弹窗，`btn-success` 只用于"确认执行正向动作"
- 必填字段统一 `form-label` + `<span class="req">*</span>`，不写在 label 文案里

### 2.3 校验前置（本轮最有价值的一条）

**原则**：规则仍然只有后端一份。前端拿到的不是规则副本，而是"本次会话的约束快照"。

#### 接口扩展

`GET /api/room-board` 的返回体增加 `limits` 段（由 `CreditService` + `RuleParamsProvider` 一次算出）：

```json
"limits": {
  "creditScore": 72,
  "requirePurpose": true,
  "maxDurationMinutes": 120,
  "maxContinuousUnits": 8,
  "dailyQuota": 2,
  "usedToday": 1,
  "blocked": false,
  "blockReason": null
}
```

#### 前端据此做四件事

| 规则 | 现状 | 改后 |
|---|---|---|
| 容量（`E01`） | 人数框 `max="99"` 硬编码，超员提交后才知道 | 选中房间后 `max = room.capacity`，超限时输入框红框 + 下方提示"该室最多 20 人" |
| 用途必填（`E06`） | label 写"用途（低信用必填）"，用户不知道自己是不是低信用 | `requirePurpose=false` 时标注"（可选）"并弱化；`true` 时标红必填且未填禁用提交 |
| 单次时长（`E08`） | 选完不校验，提交后报错 | 选中区间超过 `maxDurationMinutes` 时，底部立即提示"该信用等级单次最长 120 分钟" |
| 信用封禁（`E07`） | 提交后报错 | `blocked=true` 时禁用提交区并直接展示 `blockReason` |

其他页面同类处理：
- `/profile` 改密码：加 `minlength="6"` + 两次一致的前端校验（现仅后端判断）
- `/rooms` 新增/编辑：名称非空、容量为正的前端校验
- `/my-reservations` 申诉按钮：已申诉过的记录不再显示入口（现点击后才提示"已申诉"）

提交后仍以服务端结果为准，`SlotConflictException` 的"刚被占用"提示保留——那是并发场景，前端无法预知。

### 2.4 剩余项

- **U11 图例**：从 6 项压到 4 项，靠**形状**而非色差区分（虚线=待审批、实线=已占用、方框=可选、无边框=我的）
- **U12 复核页**：表格只保留摘要列，把复核表单移到每行的展开区（Bootstrap collapse），或改为"点击进入复核详情"
- **U16 内联样式**：随 2.2 各页迁移时顺手清掉，`board.html` 的 width 移到 `board.css`
- **U19**：`dashboard`/`login` 的 `<style>` 块并入 `ui-tokens.css` + 各自页面类

---

## 3. 实施批次

| 批次 | 内容 | 依赖 | 预估 | 风险 |
|---|---|---|---|---|
| **1** | U01 排期板铺满、U02 表格 responsive、U03 房间定位、U04 提示色、U05 提交防抖 | 无 | 1–2 天 | 低，均为局部改动 |
| **2** | 2.2 tokens + ui 片段、U06 alert 全量替换、U08/U09/U14 表单与按钮规范、U15 色系统一 | 批次 1 | 3–5 天 | 中，涉及 18 个模板，回归面广 |
| **3** | 2.3 校验前置（接口加 `limits` + 前端联动）、U07 确认弹窗、U10 空态、U11 图例 | 批次 2 | 3–4 天 | 中，属接口变更 |
| **4**（可选） | U12 复核页重构、U13 分页、U17–U21 打磨 | 批次 2 | 2–3 天 | 低 |

**批次 2 必须先于批次 3**：校验前置要往页面里加提示位，如果提示条本身还不统一，会二次返工。

---

## 4. 验收标准

1. 1440×900 下排期板**无横向滚动**，6 间房 × 08:00–22:00 一屏可见
2. 375px 宽下**无整页横向溢出**（仅排期板自身可滚）
3. 任意页面的成功提示为绿、失败为红、提醒为黄，无例外
4. 所有危险操作走同一个确认弹窗，且确认框内包含对象与时间上下文
5. 排期板上选完时段、填人数时，**超容量 / 超时长 / 需用途** 三类问题在点击提交前即可见
6. 全站仅存在一处 alert 实现、一处分页实现、一处确认弹窗实现、一套色变量
7. 键盘 Tab 可走完一次预约提交，焦点可见

---

## 5. 风险与对策

| 风险 | 对策 |
|---|---|
| 铺满后 1280 屏格子约 16px，点击命中率下降 | 批次 1 完成后实测；若不达标启用方案 B（窄屏 30 分钟粒度 + 选中后微调） |
| 18 个模板批量替换 alert 易漏改、难回滚 | 按页面分组提交，每组提交前截图对比；保留一次全站 grep 校验 |
| `limits` 属接口变更，前端漏改会白屏 | `board.js` 加默认值兜底（字段缺失时按"无限制"处理）；同步补一条接口冒烟测试 |
| Thymeleaf 片段过度封装导致模板更难读 | 只封装 4 个高频件，其余用 CSS 类约定 |
| 视觉统一与功能改动混在一起，出问题难定位 | 批次 1、3 只改行为，批次 2 只改样式，不交叉 |

---

## 6. 不做

- 不改路由结构与权限模型
- 不改 `RuleEngine` 判定逻辑（只新增对外暴露的约束快照）
- 不引入前端构建工具或 UI 框架
- 不做深色模式、主题切换
- 不重做登录/注册页视觉（仅在批次 2 统一色变量引用）

---

## 7. 实施记录

### 批次 1 —— 已完成（2026-11-27）

| 编号 | 实际改动 | 涉及文件 |
|---|---|---|
| U01 | 排期板格宽由固定 30px 改为 `minmax(14px, 1fr)` 铺满容器；移除 `min-width: max-content`；`margin: 1px` 改为网格 `gap: 1px`；内边距从网格移到滚动容器，避免撑大 `min-width` 计算。列数改由 `--slot-count` 驱动（顺带修掉 CSS 硬编码 56 与 `SlotSchema.slotsPerDay` 可能脱节的隐患） | `static/board.css`、`static/js/board.js` |
| U02 | 三张列表补 `.table-responsive` 包裹 | `approve-list.html`、`logs.html`、`my-reservations.html` |
| U03 | `board` 路由接收 `roomId` → 模板输出 `data-focus-room` → 前端滚动定位并高亮 2.4 秒；`rooms.html` 的"预约"按钮带上房间 ID | `ReservationController`、`board.html`、`board.js`、`rooms.html` |
| U04 | `/profile` 提示条改用 `infoType`，控制器补齐成功/失败类型 | `profile.html`、`ProfileController` |
| U05 | 新增 `form-guard.js`：POST 表单提交后锁定按钮并改文案，`pageshow` 时还原（bfcache），`confirm()` 取消时不锁定 | `static/js/form-guard.js`、`fragments/common-scripts.html`、`login.html`、`register.html` |

**实测宽度**（内容区 = 视口 − 侧栏 260 − 内边距 48 − 滚动容器边框 2）：

| 视口 | 每格宽度 | 是否横滚 |
|---|---|---|
| 1920 | 约 25.9px | 否 |
| 1440 | 约 17.3px | 否 |
| 1366 | 约 16.0px | 否 |
| 1280 | 约 14.5px | 否 |
| ≲1230 | < 14px | 是（按设计回落） |

**回归**：`mvn test` 329 项全绿（原 327 + 新增 2 条：`boardMarksFocusRoomOnlyWhenProvided`、`boardPageAcceptsRoomId`）。

**遗留**：手机端（<768）因 56 格 × 12px 仍超出屏宽，保留板内横向滚动，符合批次 1 验收口径；如需彻底消除，走方案 B（窄屏降 30 分钟粒度 + 选中后微调）。

### 批次 1 补丁 —— 排期板点按手感（2026-11-27）

**问题**：只有"点两次"一种手势，且第二次点击的语义是"从上次的格子向外延伸"。选完 14:00–15:00 后再点 15:00 会扩成 14:00–16:00，点 14:00 又因落在区间内被忽略——改起点只能清空重来。根子是选区只有两个数字，没有可抓的边界。

| 能力 | 实现 |
|---|---|
| 拖动框选 | `pointerdown / pointermove / pointerup`，从按下格向指针方向延伸；遇到占用或维护格自动停在边界前一格，不跨过 |
| 两端把手 | 选区首末格内挂 `.edge-handle`（命中区 12px、视觉条 3px），可单独拖动起点或终点 |
| ±15 分钟微调 | 底部新增 `起点 [−][14:00][+]` / `终点 [−][15:00][+]`，触摸与键盘都可用 |
| 点两次保留 | 触摸设备不启用拖动（避免与横向滚动抢手势），继续用点两次 |
| 误点保护 | 选区完整时点在区间内部不改动选区，避免误清 |

**配套优化**：重绘从"整表 336 格逐帧遍历"收敛为"只清上一段选区"（`painted` 记录当前绘制范围）；拖动时 `user-select: none`。

**回归**：新增 `boardProvidesStartEndAdjustControls`，`mvn test` 330 项全绿。


