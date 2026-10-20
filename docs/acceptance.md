# 验收用例对照表（P8-T01）

> 依据：`docs/重构改造计划.md` §8 的 AC-01 ~ AC-30。
> 基线：`mvn test` = **295 tests, 0 failures**。

## 总览

| 覆盖情况 | 条数 | 说明 |
|---|---|---|
| ✅ 自动化覆盖 | 29 | 有明确断言，回归时自动把关 |
| ⚪ 未覆盖 | 1 | AC-28 依赖真 Broker 断连，本机无 Broker |

自动化覆盖的用例全部在 `mvn test` 内，不需要人工重复。

## 逐条对照

| AC | 场景 | 期望 | 验证方式 | 状态 |
|---|---|---|---|---|
| AC-01 | 提交 08:07 起始 | 拒绝：未对齐 15 分钟 | `SlotSchemaTest.notAlignedRejected`、`ReservationServiceImplTest.Submit.notAligned` | ✅ |
| AC-02 | 提交 17 格（4h15m） | 拒绝：超过 4 小时 | `SlotSchemaTest.tooLongRejected`、`Submit.tooLong` | ✅ |
| AC-03 | 提交 8 天后 | 拒绝：超过提前 7 天 | `SlotSchemaTest.advanceDays`、`Submit.tooFarAhead` | ✅ |
| AC-04 | 两名学生同时提交同一单元 | 都进待审批，不占单元 | `ReservationFlowIntegrationTest.pendingApplicationsCoexist`（真库） | ✅ |
| AC-05 | 连续批准上述两笔 | 第一笔成功，第二笔冲突且回滚 | `ReservationFlowIntegrationTest.secondApprovalConflicts`、`Approve.propagatesSlotConflict` | ✅ |
| AC-06 | 教师覆盖已开始的学生预约 | 拒绝 | `Teacher.cannotOverrideStarted` | ✅ |
| AC-07 | 两名教师同时段 | 后者拒绝 | `Teacher.teacherConflict` | ✅ |
| AC-08 | 信用 95、人数 4 | 自动通过 | `Submit.autoApproved`、`Submit.midCreditShortDurationAutoApproved` | ✅ |
| AC-09 | 信用 95、人数 5 | 转人工 | `Submit.headcountForcesManual` | ✅ |
| AC-10 | 信用 75、时长 3h | 转人工 | `Submit.midCreditLongDurationForcesManual` | ✅ |
| AC-11 | 信用 55 | 基础规则拒绝 | `Submit.lowCreditRejected` | ✅ |
| AC-12 | 签到码连错 5 次 | 锁定，窗口内不可再试 | `CheckIn.locksAfterFiveFailures` | ✅ |
| AC-13 | 开始后 4:59 签到 | 成功 | `CheckIn.successJustBeforeDeadline` | ✅ |
| AC-14 | 开始后 5:00 签到 | 失败：已过截止 | `CheckIn.expiredExactlyAtDeadline` | ✅ |
| AC-15 | 签到成功但设备离线 | 判为已签到，提示门禁离线，不回滚 | `CheckIn.doorDeniedDoesNotRollbackCheckIn`、`CheckIn.doorExceptionDoesNotRollbackCheckIn`、`MqttAccessIntegrationTest.otherRoomHasNoSimulator` | ✅ |
| AC-16 | 同一 `msgId` 重发 3 次 | 只开锁 1 次 | `MqttRoundTripTest.replayIsRejected` | ✅ |
| AC-17 | 发送过期指令 | 设备拒绝并回 `ERR_EXPIRED` | `MqttRoundTripTest.expiredCommandIsRejected` | ✅ |
| AC-18 | 已签到后再次开门 | 成功，新增一条 `t_access_log` | `AccessFlowIntegrationTest.canReopenAfterCheckIn` | ✅ |
| AC-19 | 已结束预约再次开门 | 拒绝 | `AccessServiceImplTest.afterEnd` | ✅ |
| AC-20 | 15 分钟预约：签到截止与结束提醒相邻 | 两者各自触发、互不干扰 | `TimeTravelConvergenceTest.shortSlotDeadlineAndReminderDoNotInterfere`（09:06 释放未签到、09:12 只提醒已签到、09:16 结束） | ✅ |
| AC-21 | 结束前 5 分钟 | 只提醒一次 | `TimeTravelConvergenceTest.remindOnlyOnce`、`Converge.remindEndingOnlyOnce` | ✅ |
| AC-22 | 签到截止未签到 | 状态→超时释放，slot 清空，信用 −10 | `TimeTravelConvergenceTest.oneDayConverges`、`Converge.releaseNoShowReleasesSlotsAndRevokesCode` | ✅ |
| AC-23 | 同一预约重复触发爽约扣分 | 只扣一次（唯一键） | `TimeTravelConvergenceTest.noShowDeductsOnce`、`CreditAndAppealIntegrationTest.noShowDeductsOnlyOnce` | ✅ |
| AC-24 | 申诉后由原审核人复核 | 拒绝自审 | `AppealServiceImplTest.rejectsOriginalApprover`、`CreditAndAppealIntegrationTest.cannotSelfReviewAsOriginalApprover` | ✅ |
| AC-25 | 申诉成立 | 撤销扣分，单元不恢复 | `CreditAndAppealIntegrationTest.appealRevokeRestoresScore`（分数回到 100、slot 仍为 0 条、状态仍是超时释放） | ✅ |
| AC-26 | 篡改 MQTT 消息 HMAC | 设备丢弃 | `MqttRoundTripTest.tamperedHmacIsRejected`、`MqttEnvelopeTest.tamperAnyFieldIsDetected` | ✅ |
| AC-27 | 伪造 `reservationId` 的开锁指令 | 设备丢弃 | `MqttRoundTripTest.boundToDisplayedReservation` | ✅ |
| AC-28 | 断网 2 分钟后重连 | 按 snapshot 版本重绘屏幕，不执行积压指令 | 需要真实 Broker 断连 + retained 语义，**进程内总线无法模拟** | ⚪ |
| AC-29 | 日志保留 | 7 天前的 `t_log` 与 `t_audit_log` 被清理 | `LogRotationJobTest.rotatesSevenDaysBack` | ✅ |
| AC-30 | 非 ADMIN 访问规则配置 | 重定向，不泄露参数 | `WebPagesSmokeTest.nonAdminCannotOpenRuleConfig` | ✅ |

## 未覆盖项的处理

### AC-28：断网重连

需要 Mosquitto 的 retained 语义与真实断连。`MqttRoundTripTest` 覆盖了 retained 参数与
快照解析，但"断连→重连→补发"这条链路只能连真 Broker 验（见 `deploy/mosquitto/README.md` 形态 C）。
本机未安装 Broker，未执行。

## 手工验收清单（依赖人/硬件，脚本无法替代）

| 项 | 做法 | 期望 |
|---|---|---|
| 移动端可用性 | 手机浏览器打开 `/reservation/board` | 侧边栏可抽屉开合、格子可点、底栏不遮挡内容、横向不撑破布局 |
| 首次操作时长 | 新用户从登录到提交第一笔预约 | ≤ 3 分钟（P8-T04） |
| 真机开锁 | 接 STM32 + 电控锁，`mode=mqtt` | 签到后锁动作 3 秒；断网时页面提示离线 |
| 返听演示 | 按 `docs/demo.md` 走一遍 | 5 分钟内讲完主链路 |

## 复现命令

```bash
# 全部自动化验收
./mvnw test

# 只看真库集成测试
./mvnw test -Dtest='*IntegrationTest,TimeTravelConvergenceTest'

# 只看 MQTT 协议层
./mvnw test -Dtest='Mqtt*Test'
```
