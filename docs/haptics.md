# 触感设计

状态：已实现（2026-11-06）。逐个交互点的设计对应见 [附录](haptics-inventory.md)；实现时按源码实际语义调整的条目见 §10，以 §10 为准。波形参数尚待 Galaxy S24 Ultra 真机试用后定稿。

## 1. 原则

- **少而准**。只在“选中了、切换了、到头了、成功了、出事了”这几种时刻震动。导航、打开/关闭弹层、滚动、逐键输入都不震，否则触感会变成噪音。
- **结果触感以落库为准**。提交按钮按下时不震，等业务真正完成后再播放 `Saved` / `Done` / `Removed` / `Restored`。`AppViewModel.execute` 的 `onSuccess` 只表示没有抛异常，不等于成功（例如审阅过时返回 `null`），不能作为成功信号。
- **三套主题手感各自独立**。语义相同，波形不同。主题切换后，手感也随之改变。
- **跟随系统**。用 `USAGE_TOUCH` 播放，系统里“触摸振动”关闭或调低时，应用也跟着关闭或减弱。

## 2. 触感词表

| 词 | 时机 | 层 |
|---|---|---|
| `Select` | 选中一项：菜单项、分类、日期、商户、筛选、模板列映射等 | 按下 |
| `TabSwitch` | 底部导航、一级页签、分段切换（选中项改变时；重复点当前项不震） | 按下 |
| `ToggleOn` / `ToggleOff` | 开关、勾选、收藏、进入/退出模式 | 按下 |
| `Step` | 离散步进：换月箭头、上一页/下一页、上移/下移、上一张/下一张 | 按下 |
| `PageTurn` | 流水日历横滑翻月成功 | 手势结束 |
| `Limit` | 到边界被拒绝：翻到未来月、已是最后一页 | 手势结束/按下 |
| `Compose` | 开始记一笔：底部“记一笔”、记在这一天、用模板记、从账户转账/补记收入 | 按下 |
| `Grab` | 按住音频进度条（拖动过程不震） | 按下 |
| `Media` | 播放 / 暂停 | 按下 |
| `Saved` | 记账、修改、退款、转账、周期付款真正保存（本应用最核心的一刻） | 结果 |
| `Done` | 其他提交成功：金融计划、预算、规则、设置、导入、备份、云端排队等 | 结果 |
| `Removed` | 删除、移入回收站、永久删除、清空、作废、放弃草稿、拒绝方案、解除关联 | 结果 |
| `Restored` | 恢复原记录、恢复活动/待办、取消归档、撤销批量转换、撤销云端方案 | 结果 |
| `Warning` | 需要注意：负余额确认层出现、审阅资料已变化、部分导入失败、流水重复/重叠 | 结果 |
| `Error` | 失败：执行异常（“请检查”弹层）、导入全部失败、连接失败、恢复失败 | 结果 |

## 3. 三主题手感

记法：`CLICK .6` 表示 `PRIMITIVE_CLICK`，强度 0.6；`→40→` 表示两个原语之间间隔 40ms。

- **现代生活杂志：铅字与纸张**。精准、短促、克制，只用 `TICK` / `CLICK` / `QUICK_FALL`，节奏等距，强度偏低。像翻过一页铜版纸、落下一个句号。
- **纸质手账：笔、纸与印章**。钝一些，带摩擦感，节奏略不规则。主要用 `LOW_TICK` / `THUD` / `SLOW_RISE`，像笔尖点纸、打勾、盖朱红印章、撕下纸条。
- **日常小物：实物的重量与弹性**。圆润，有回弹。用 `CLICK` 加衰减的余震，以及 `QUICK_RISE` 和 `THUD`，像硬币落进零钱盘、拨动开关、把东西放进抽屉。

| 词 | 杂志 | 手账 | 小物 |
|---|---|---|---|
| `Select` | `TICK .45` | `LOW_TICK .7`（笔尖点纸） | `CLICK .35`（轻按软键） |
| `TabSwitch` | `TICK .6` | `LOW_TICK .5 →30→ TICK .3`（翻页签） | `QUICK_RISE .25 → CLICK .5`（分隔签弹起） |
| `ToggleOn` | `CLICK .6` | `TICK .5 →45→ CLICK .6`（打勾两笔） | `QUICK_RISE .35 → CLICK .8`（拨钮咔哒） |
| `ToggleOff` | `TICK .5` | `LOW_TICK .6 →30→ LOW_TICK .4`（划掉） | `QUICK_FALL .5`（拨回） |
| `Step` | `TICK .4` | `LOW_TICK .55` | `CLICK .45` |
| `PageTurn` | `QUICK_FALL .4 →20→ TICK .5` | `SLOW_RISE .3 → QUICK_FALL .45`（掀起一页） | `QUICK_RISE .3 →40→ THUD .5`（收纳格推到位） |
| `Limit` | `TICK .8 →40→ TICK .4` | `THUD .5` | `THUD .7`（碰到盒壁） |
| `Compose` | `CLICK .7` | `LOW_TICK .5 →60→ CLICK .7`（拔开笔帽） | `QUICK_RISE .3 → THUD .6`（按下圆钮） |
| `Grab` | `TICK .3` | `LOW_TICK .4` | `CLICK .3` |
| `Media` | `TICK .5` | `LOW_TICK .6` | `CLICK .5` |
| `Saved` | `TICK .5 →70→ CLICK .9`（句号落版） | `QUICK_RISE .5 → THUD 1.0`（盖印章） | `CLICK 1.0 →70→ TICK .6 →60→ TICK .35 →50→ TICK .15`（硬币落盘弹跳） |
| `Done` | `CLICK .7` | `THUD .7`（小印） | `CLICK .8 →70→ TICK .4` |
| `Removed` | `QUICK_FALL .7`（抽出一页） | `LOW_TICK .7 →18→ .6 →22→ .5 →26→ .35`（撕纸） | `SLOW_RISE .4 → THUD .9`（丢进抽屉） |
| `Restored` | `QUICK_RISE .5 → TICK .6` | `SLOW_RISE .4 → TICK .6`（胶带贴回） | `QUICK_RISE .4 → CLICK .7`（放回原位） |
| `Warning` | `CLICK .6 →120→ CLICK .6` | `THUD .6 →140→ LOW_TICK .6` | `THUD .5 →110→ THUD .5` |
| `Error` | `CLICK .9 →80→ CLICK .9 →80→ CLICK .9` | `THUD .9 →90→ THUD .9` | `THUD .9 →60→ QUICK_FALL .6` |

主金额运算符使用 `Select`；点“=”成功只替换输入，不播放保存触感；无效算式点“=”或保存时播放 `Error`。

**退化**：设备不支持某个原语时（用 `Vibrator.areAllPrimitivesSupported` 查询），整个词退回系统语义触感。`Select` / `TabSwitch` / `Step` / `Grab` / `Media` 退回 `SEGMENT_TICK` 或 `CLOCK_TICK`；`ToggleOn` / `ToggleOff` 退回 `TOGGLE_ON` / `TOGGLE_OFF`；`Saved` / `Done` / `Restored` / `Compose` / `PageTurn` 退回 `CONFIRM`；`Warning` / `Error` / `Limit` 退回 `REJECT`；`Removed` 退回 `EFFECT_HEAVY_CLICK`。Android 10（API 29）没有这些常量，改用 `createPredefined` 的 `EFFECT_CLICK` / `EFFECT_TICK` / `EFFECT_DOUBLE_CLICK` / `EFFECT_HEAVY_CLICK`。退化后三个主题的手感相同。

波形参数要在 Galaxy S24 Ultra 真机上逐项试过再定稿，模拟器不能代替。

## 4. 组件层（写在共享组件里，自动覆盖所有调用点）

| 组件 | 触感 |
|---|---|
| `YSelect` / `ThemedSelectMenu` 菜单项、时/分选择 | `Select` |
| `ThemedCalendar` 日期格、“今天” | `Select`；上一月/下一月 `Step`；“完成” 不震 |
| `YToggle` 及 `NotificationPreferenceToggle`、`HistoricalOpeningToggle`、`toggleable` | 新值为真 `ToggleOn`，为假 `ToggleOff` |
| `YTabs`、`CategoryKindTabs`、`LedgerViewTabs`、`ReceiptInboxTabs`、`FinancialTaskTabs` | `TabSwitch`（选中项改变时） |
| `YBottomNav`（`NativeApp.kt:268`）四个页签 | `TabSwitch`；“记一笔” `Compose` |
| `LedgerCalendar` 横滑（`LedgerCalendar.kt:114`） | 超过 120px 翻月成功 `PageTurn`，翻到未来月被拒绝 `Limit`；拖动中不震 |
| `ThemedAudio` | 播放/暂停 `Media`，按下进度条 `Grab` |
| `ImageViewer` | 上一张/下一张 `Step`；边界按钮保持禁用、不震；放大/缩放不震 |
| 主金额运算符 | `Select`；无效结算 `Error` |
| `YButton` / `YLink` | 默认不震。需要按下触感的调用点通过新增参数 `haptic` 指定（`Compose`、`Step`、`Select` 等，见附录） |
| `YField` / `YSearch` / `EntryNotes` | 不震（系统输入法自己负责逐键反馈） |
| `YSheet` 关闭、遮罩点击、系统返回 | 不震 |

## 5. 结果层事件

结果触感在业务判定成功或失败的位置发出：由 ViewModel 发一个触感事件，`NativeApp` 收集后按当前主题播放。

| 事件 | 位置 | 触感 |
|---|---|---|
| 记账/修改/退款/转账保存 | `AppViewModel.entrySaved`（`AppViewModel.kt:365`） | `Saved` |
| 周期付款确认 | `AppViewModel.kt:284`，`saved=true` | `Saved` |
| 负余额确认层出现 | `saveEntry` 设置 `negativeBalanceReview` | `Warning` |
| 负余额“仍然保存”时资料已变化 | `confirmNegativeBalance` 提交过时后照旧自动重走 `saveEntry`，由重走结果发 `Saved` 或新的 `Warning` | 按结果 |
| 记账审阅过时、重新预览 | `confirmEntryReview` 返回 `null`（`AppViewModel.kt:403–412`） | `Warning` |
| 关联已有支出 / 预算提交 | `commitOccurrenceLink` / `commitBudget`：成功或过时（`AppViewModel.kt:216–238`） | `Done` / `Warning` |
| 账本操作 | `commitBookOperation`（`AppViewModel.kt:127–141`）：移入回收站、永久删除、清空为 `Removed`，恢复、取消归档为 `Restored`，其他为 `Done`；过时为 `Warning` | 按操作 |
| 记录生命周期 | `EntryLifecycleSheet.kt:51–58`：`TRASH` / `PURGE` / `EMPTY` 为 `Removed`，`RESTORE` 为 `Restored`；`refreshed` 为 `Warning` | 按操作 |
| 账户 / 分类 / 活动删除审阅 | `AccountManagementSheet.kt:48–55`、`CategoryManagementSheet.kt:70–77`、`ActivityDeletionSheet.kt:41–48`：删除为 `Removed`，取消归档为 `Restored`，其他为 `Done`；`refreshed` 为 `Warning` | 按操作 |
| 所有金融草稿提交 | `FinancialDraftSession.commit`（`FinancialDrafts.kt:61`）是唯一出口：返回非空为 `Done`（作废、放弃草稿为 `Removed`，撤销批量转换为 `Restored`），返回空（过时）为 `Warning` | `Done` / `Warning` |
| 执行异常 | `AppViewModel.execute` 的 catch（`AppViewModel.kt:100–108`），即“请检查”弹层 | `Error` |
| 活动/预留/互请审阅过时 | `ActivityScreens.kt` 三处提交返回 false 时用当前输入重新预览、原地提示“资料已变化，请按当前影响重新确认”，不再弹错误层 | `Warning`；再次确认成功 `Done` |
| 附件/小票批量导入 | `AppViewModel.kt:328–363`：全部成功 `Done`，部分失败 `Warning`，全部失败 `Error` | 按结果 |
| 相机无可用应用 | `MediaPanels.kt:99–110` | `Error`；用户取消拍照不震 |
| 小票替换或金额差异确认层出现 | `ReceiptWorkflowScreen.kt:58–91` | `Warning` |
| CSV 迁入 / 撤销整批 | `CsvImportViewModel.kt:61–89`：迁入完成 `Done`，撤销 `Removed`，解析错误 `Error` | 按结果 |
| 流水重复/重叠提示 | `StatementImportScreen.kt:53–63` | `Warning` |
| 参考汇率主动刷新失败 | `ReferenceRatePanel.kt:26–47` | `Error`；成功不震，自动获取不震 |
| 提醒偏好保存 | `NotificationSettingsScreen.kt:85–94`：保存 `Done`，校验失败 `Error` | 按结果 |
| 备份导出 | `BackupScreen.kt:30–43`：`Done` / `Error`；检验备份只出预览，不震 | 按结果 |
| 从备份恢复 | 重启后结果弹层出现时（`MainActivity.kt:32–39`）：`Done` / `Error` | 按结果 |
| 主动定位失败 | `ForegroundLocation.kt:28–69` | `Error`；成功不震 |
| 云端连接绑定 / 测试 | `CloudScreens.kt:273–287`：成功 `Done`，失败 `Error` | 按结果 |
| 云端准备并排队上传、补充资料、报告无法导入 | `CloudScreens.kt:306,429,457,53–55,365` | `Done`（只表示本地排队成功） |
| 领取结果、本地预览、保存小票或核对资料 | `CloudScreens.kt:68–72,330–372`：保存 `Done`，下载或预览失败 `Error` | 按结果 |
| 云端方案确认 | `CommandRepository.confirm`：普通方案为 `Done`，撤销方案为 `Restored`，失败为 `Error`；`reject` 为 `Removed`。确认时资料已变化（`CommandRepreviewRequired`）不再弹错误层，方案页原地显示提示并清空勾选 | 按结果；过时 `Warning` |
| 后续步骤完成/重试 | `CommandFollowupViews.kt`：结束引导 `Done`；`CommandFollowupCoordinator.retry` 返回是否成功，重试后仍失败只发 `Error`，状态照旧标为可重试，不弹错误层 | 按结果 |
| 页面“重新读取”后仍失败 | `AppViewModel` 主库/金融/额度读取，及金融草稿、计划版本、资金展望、修订历史的重读 | `Error`；初次与后台读取不震 |

## 6. 明确不震

- 导航、打开详情、下钻、打开/关闭弹层、返回、展开/收起。
- 预览、比较、试运行等只生成审阅的按钮（结果由后续确认按钮负责）。
- 文本输入、滚动、拖动过程、地图平移缩放、图片缩放。
- 后台事件：草稿自动保存、30 秒轮询、上传进度、回执发送、提醒重建、启动维护、`AppNotice` 本身。不能因为 Toast 出现就震动，Toast 来源各异，也没有类型。
- 打开外部应用、系统文件选择器（取消也不震）。
- 页面初次加载失败（不是用户操作）。用户点“重新读取”后仍然失败才算 `Error`。

## 7. 实现要点

- 新建 `ui/haptics/`：`Haptic` 枚举、三主题波形表、`LocalHaptics`（由 `NativeApp` 在主题作用域注入当前主题与本机开关），以及 `Vibrator` 封装（原语支持查询、退化、`USAGE_TOUCH`）。
- `AndroidManifest.xml` 增加 `android.permission.VIBRATE`（普通权限，无需运行时申请）。
- `YButton` / `YLink` 增加 `haptic:Haptic?=null`，只在附录标出“按下”触感的调用点传值。
- 结果层：`AppViewModel` 增加 `haptics: SharedFlow<Haptic>`，在 §5 的判定点发事件；`NativeApp` 收集，用播放时的当前主题播放。`execute` 的 catch 统一发 `Error`，同一次操作的 `Done` 不会再发（因为 `onSuccess` 不运行）。
- 真实输出 `SystemHapticOutput` 将同一帧内多个触感合并为最后一个，避免组件触感和结果触感叠在一起；测试输出可记录每次语义调用。
- 主题设置页选中新主题时，用新主题的 `Select` 波形播放，让用户马上感受到新手感。
- 设置页增加“触感反馈”开关，默认开启。它是本机设置，不随账本切换；关闭后所有触感都不播放。系统“触摸振动”关闭时，即使这里开着也不震。
- 触感只在本机 UI 层发生，不影响云端工作台（云端办理）的协议、数据和流程。

实现说明：

- 对外接口：`HapticOutput.play(theme,haptic,scale)`、`Haptics.perform(haptic,scale=1f,theme=当前主题)`、`LocalHaptics`；`NativeApp` 最后一个参数为 `haptics:HapticOutput=rememberSystemHapticOutput()`，测试传入 `RecordingHapticOutput`。业务结果用 `vm.haptic(...)`；不持有 `vm` 的页面通过 `NativeApp` 提供的 `LocalResultHaptic` 发送。`BackupViewModel`、`CsvImportViewModel` 各自用 Channel 发出结果，由页面转交。
- 设置开关位于“外观、语言与触感 / 外観・言語・触覚”，标签字号与同组设置行一致；关闭动作静音，打开写入生效后播放一次 `ToggleOn`。偏好随备份导出／恢复，旧备份缺字段默认开启。
- 云端：新增 `preferences.haptics`（`{"enabled":true}`，全账本共用，post-commit 原值校验，可撤销），当前注册表 90 个命令（含两个扫描偏好），见[覆盖审计](plans/cloud-command-coverage.md)。

## 8. 决定与待决定

1. 已定：加“触感反馈”开关（见 §7）。
2. 已定并实现：活动、预留、互请三处审阅过时改为原地刷新并发 `Warning`（见 §5）。
3. 已定：保持现状。保存时校验不通过（例如必填缺失）时按钮是 `enabled=false`，按不下去，因此不震。
4. 待定：CSV 迁入（`CsvImportRepository`）与流水核对的部分保存接口（`FileWorkflowRepository`、`StatementReviews`）仍用普通异常表示审阅过时，因此目前经通用错误弹层发 `Error`。要改为 `Warning` + 原地刷新，需要这些接口返回明确的过时结果，做法同第 2 项。

## 9. 统计

1282 个交互调用点（附录逐条列出）：

- 按下时有触感 379 个：`Select` 253、开关 73、`Step` 28、`Compose` 12、`TabSwitch` 8、翻月/到头 3、媒体 2。
- 按下时不震 903 个，其中 98 个在结果层发 `Saved` / `Done` / `Removed` / `Restored`。
- 另有 26 类结果事件（§5）。

## 10. 实现偏差（以源码实际语义为准）

- ENTRY-001 打开确认表单，不立即发 `Done`；最终保存发 `Saved`。ENTRY-035、057–058 的“关联已有支出”分支只生成预览，确认关联成功后发 `Done`。
- TRANSFER-RATES-033 只更新记账草稿，不震。
- ENTRY-011、CATEGORIES-018：只有无子分类时的选择发 `Select`，展开不震。CATEGORIES-020–021 打开分类编辑，不震；CATEGORIES-008 推荐图标实为选择，经 034 发 `Select`。
- CATEGORIES-032、040 与 LEDGER-HOME-020–022 是页签，用 `TabSwitch`，重复点当前项不震。
- LEDGER-HOME-031–032：未来月箭头本就禁用，不发 `Limit`；横滑越界仍发 `Limit`。
- LEDGER-HOME-035–037、SETTINGS-THEME-HOME-032 实为开始记账，改为 `Compose`。语言切换行发 `Select`。
- BUDGET-RECURRING-TEMPLATES-025 解除关联发 `Removed`；052 周期付款发 `Saved`。ANALYSIS-032–033 排序步进发 `Step`。
- ACTIVITY-063 实为开关；MERCHANTS-LOCATION-042 实为选入商户，发 `Select`；MEDIA-RECEIPT-006/007 调整图片顺序，发 `Step`；CLOUD-007 仅领取并预览，不震；CLOUD-044 保存文件成功发 `Done`；CSV-IMPORT-015 是整批事务，没有部分失败分支。
- FINANCIAL-119 实际只有返回，不震；FINANCIAL-263 实为提交合同关联，成功发 `Done`；FINANCIAL-191 撤销被资料变化阻止时发 `Warning`；FINANCIAL-279 核对发现差异或不完整发 `Warning`。
- 自动定位（进入页面时）失败不震，只有用户主动点“重新定位”等失败才发 `Error`。


### 2026-11-13 交互更新

- 对应表中旧`ENTRY-019`、`ENTRY-055`已随固定增量按钮移除，按本节覆盖其旧定义；对应表本轮保留用户未提交的编辑，不改写该文件。
- 主金额运算符按下为`Select`，`=`成功不额外震动，非法算式结算/保存为`Error`；自动预览结果/错误不震动。最终保存仍仅成功后`Saved`。
- 周期更多菜单、已处理开关与详情展开属于打开/关闭，不播放触感；取消/解除关联完成后仍为`Removed`，恢复完成后仍为`Restored`。月分类点击沿用原分析页`Select`及管理页编辑行为。
