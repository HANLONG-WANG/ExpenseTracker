# 云端办理阶段 6：文件工作流、偏好与外部效果

日期：2026-11-02。基线：阶段 5 `1dd4439`。状态：**阶段 6 已验收通过**。实现由 Codex 完成（其环境无法运行 Gradle/模拟器），编译、测试与视觉验收在本机完成，见文末验收结果。首轮能力样例已由用户从注册表生成；本轮返工保持用户生成文件的原字节，不需要再次生成。

## 命令清单

新增 18 个 v1 命令。I=INFORMATION，R=原有具体风险策略（实际资金账户负余额加重才单独勾选），F=手机 USER_FACT。未知命令、版本、字段、类型和非法枚举整批拒绝；可引用字段只接受已有 ID 或前序的相应类型输出。严格 schema、示例及默认值说明均由适配器注册表生成。

撤销记号：S=原有 v3 全量行快照、关联守卫及受限新行移除；P=后续步骤完成后检查最终偏好原值并排队恢复；A=登记并移除本方案新增且未变化的附件关联，保留源文件，不触发物理文件删除；N=整批不可撤销，预览直接说明原因。普通自动媒体维护仍由本机既有策略决定。

| 名称 / 版本 | 输入、默认值与实际前提 | 确认 | 执行方式 | 撤销 |
|---|---|---|---|---|
| `statement.accept` v1 | batch/row/entry 必须指明；note=""。本账本流水行与记录账户、方向、币种一致；资金端未被别行占用 | I/R | transactional | S；恢复差异关联原行 |
| `statement.unlink` v1 | batch/row，必须已有差异关联；保留原流水、记录和金额 | I/R | transactional | S；恢复原关联证据 |
| `statement.supplement` v1 | batch/row/occurredDate/postedDate；kind=EXPENSE、set={}、originalEvent=null。行尚无完全匹配或手动关联；补齐分类/换算/转账另一端，不能改写流水来源端；金融借入还款仍用专用金融命令 | I/R/F：入账日期及金额 | transactional | S；新 entry 输出、修订、结算、金融影响等一起登记 |
| `statement.confirmActual` v1 | batch/row/entry/postedDate；set 只补 baseAmount；账户/币种/方向一致。使用真实流水金额，不把未知本位币额当0 | I/R/F：入账日期及金额 | transactional | S；恢复记录和完整关系 |
| `receipt.items.save` v1 | receipt/items；首次回填须明确 imageIds，编辑省略时保留原覆盖图片；adjustments=[]、total=null、complete=false、issues=[]；商品未知字段保持null。本账本有效小票/记录/图片，币种一致；完整替换文档，检查当前修订，复用 FileProtocols 回填校验 | I/R | transactional | S；商品文档、小票状态及来源守卫 |
| `csvBatch.undo` v1 | batch；首次预览固定仍有效的批次记录集合。沿用关联退款与回收站规则 | I/R | transactional | S；精确恢复批次和回收站状态，不丢失后来编辑 |
| `preferences.analysis` v1 | modules 必填且保持用户顺序；pinned=[]，必须是模块子集；CATEGORIES/MERCHANTS/TREND/ACTIVITIES/COMPARISON | I/R | transactional | S；恢复原分析行（包括原本无行） |
| `entryDraft.reclassify` v1 | sourceCategory/category 类型引用；现有收支草稿须仍匹配来源，目标分类用途一致且启用；其他输入与媒体会话全部保留 | I/R | transactional | S；恢复完整草稿 |
| `book.select` v1 | book 类型引用；可用前序新账本输出，只改变当前选择，不允许后续跨账本写入。目标不得已回收；归档账本也可像本机管理页一样打开查看 | I | post-commit | P；恢复原选择，包括未明确选择 |
| `preferences.reminders` v1 | 完整 ReminderPreferences：enabled=false；repayment/deadline/recurring/budget/funds=true；提前天数[7,1]/[3,1]/[1]；time=09:00。日期列表/时间走原 validate；遗漏用默认，非补丁 | I | post-commit；开启且缺系统授权时附 interactive | P；需要权限交互时 N，绝不代替用户授权 |
| `preferences.automaticRates` v1 | enabled 必填；全应用默认，不发起事务内网络请求 | I | post-commit | P |
| `preferences.location` v1 | enabled 必填；本账本前台定位匹配；不增加后台定位 | I | post-commit；开启且缺系统授权时附 interactive | P；需要权限交互时 N |
| `preferences.photoStorage` v1 | original 必填；true 原样、false 节省空间；全账本新表单默认，不重写已有图片 | I | post-commit | P |
| `attachment.attachSource` v1 | entry 类型引用、sourceName 精确上传名称；只能引用本方案来源任务的 source-N 文件。拒绝生成的协议/上下文文件、其他任务、任意路径/URI。字节、长度、SHA-256 在执行时核验 | I | post-commit | A；未完成时先重试，不能越过待执行效果撤销 |
| `entry.prepareDraft` v1 | set={}；media=NONE/ATTACHMENT/RECEIPT_IMAGES/RECEIPT_CAMERA；沿用表单默认值，不推测金额/分类，缺账户也可留待补完；不得覆盖已有草稿 | I | interactive | N；一次创建持久草稿并等待用户打开，保存只能由本机表单发起 |
| `backup.export` v1 | 无输入；用户在本机选择目录，使用既有完整备份服务。内容是开始导出时的全部账本/附件/设置，不声称冻结在预览时 | I | interactive | N；已导出文件不可整批撤销 |
| `statement.exportOriginal` v1 | batch；本账本已有流水，复制预览时同一份完整原 JSON；内容摘要参与比较 | I | interactive | N |
| `command.exportResult` v1 | 无输入；只复制本方案收到的原结果，不是任意文件或其他任务结果 | I | interactive | N |

小票行金额、合计、调整额用 `{currency,minor}` 整数。单价是原回填协议已有的精确商品单价事实：支持上述金额对象，或互斥的 `unitPriceDecimal` 十进制字符串（例如日元商品单位价格小于1日元）；不能因此截断单价精度。数量也是十进制字符串，未知不补零。来源图片由方案明确（编辑省略时保留旧覆盖，不自动认领新增图片），修订由手机填入，商品 ID 按方案/步骤/行位置生成并映射；不让助手伪造导出包。

## 永久边界判定

| 领域写入 | 判定 | 替代 |
|---|---|---|
| 删除/清空 statement_batches | forbidden `statement.delete` / `statement.clearAll` | `statement.unlink` 只解除可恢复的差异关系 |
| 清除 statement_decisions 单个关联 | 允许的关系解除，有完整前后日志；并非删原流水 | `statement.unlink` |
| 删除小票、商品文档、导出历史 | forbidden `receipt.delete` / `receipt.items.delete` / `receipt.exportHistory.delete` | 完整编辑商品文档，或将所属记录移入回收站 |
| 附件移除、物理文件删除、未引用媒体回收 | forbidden `attachment.delete` / `attachment.deleteFile` / `media.collectUnused` | 保留文件；所属记录可回收；本机历史只能受限移除本方案新建的未变化关联 |
| CSV 批次物理删除、物理清除导入记录、删除列对应 | forbidden `csvBatch.delete` / `csvBatch.purgeRecords` / `csvProfile.delete` | `csvBatch.undo`、本机重新保存列对应 |
| **CSV 撤销导入批次** | **现有 `undoCsvBatch` 本来就调用 `trashEntrySet`，不是物理删除**；保留后续编辑及退款关系，批次历史保留 | 无需借用“新建对象受限移除”例外；按回收站方案开放 |
| 放弃现有普通草稿、重置全部偏好 | forbidden `entryDraft.discard` / `preferences.reset` | 本机处理草稿；分类可 `entryDraft.reclassify`；偏好逐项设置 |
| 备份恢复、云端连接凭据及设备 | 原永久边界继续禁止 | 本机原入口 |
| 导出器自己的未完成暂存文件 | 只用于本次操作的临时工作副本，可在完成后清理；不等于删除用户源文件或导出目标 | 不暴露为云端清理命令 |

新增禁止项已加入 `CommandPermanentBoundary`；集中用例 `CommandLedgerExecutionTest` 的现有集中遍历使用 `permanentAlternatives.keys`，自动包含全部新项，没有新建重复边界矩阵。

## 适配检查表对应

| 检查项 | 本阶段实现 |
|---|---|
| 名称/版本/schema/示例/默认值/未知值 | `FileCommandAdapters`、`FollowupCommandAdapters` 注册18项；严格 DTO/schema，媒体请求/分析模块/时间等额外验证；示例从注册表生成 |
| 引用/固定目标 | entry/book/category 接前序相应类型输出；流水/小票/CSV 是已有资料；CSV 内部 targets 首次 normalize 固定且不接受云端自填；附件从来源任务查精确名称和 source-N 身份 |
| 前提和共用业务 | `previewStatementDifference/acceptStatementDifference`、`statementSupplementDraft/statementActualDraft`、`writeEntryBatchInTransaction`、`saveDirectReceiptItems`、`undoCsvBatch`、`saveAnalysisPreferences`、`reclassifyEntryDraft`；false/null不当成功 |
| I/R/F/冲突 | 原有负余额策略；流水补记/实际确认必须由手机核对具体摘要、入账日、金额；完整偏好和对象冲突阻止撤销；系统授权不接受助手布尔值 |
| StepEffect/净影响/名称 | 差异关联、流水来源、小票商品、CSV批次、附件、草稿、分析与偏好对象；前后值和中间关系保留；三主题业务卡片和中日文，显示提交后/本机操作及撤销说明 |
| 比较字段 | 流水源行、完整记录/入账与关系；商品完整文档和源记录/图片；固定批次集合和回收站变化；偏好前后绝对值及选中账本；附件源元数据与目标记录；交互草稿字段、导出原件摘要、执行方式 |
| 时间/revision/新ID | 时间上下文不变；记录写入继续用递增保存时序；审计 updatedAt/createdAt 仅从比较投影去掉，原行仍入日志；产品行/附件/草稿身份映射；集合规范排序，分析展示顺序保留业务语义 |
| inverse/所有权/关系 | v3 journal 扩 receipt_item_documents/csv_batches/csv_batch_events/analysis_preferences；已有 statement_decisions/drafts 等复用；附件成功时在完成标记同一事务补入本次关联行所有权与目标守卫，逆向方案继续走原本机 restoreExecution |
| 后续撤销 | 偏好用本机 `local.restorePreference` 生成持久逆效果，云端不可调用；任一 N 步骤即整批不可撤销；全部后续完成后才允许生成撤销方案；外部阶段失败不会假称数据库事务也回滚 |
| 数据库/迁移 | 继续 Room v37；现有 command_effects 表字段足够，无表变化、不虚构迁移。执行 JSON 新增可选 followups，旧记录默认为空；BackupPreferences 新增默认空的效果凭据集合 |
| 本机一致/文档/验证 | 小票原先无文档时也可手工录入；方案页增加结果另存入口；其余对应现有页面；必要组合及视觉测试已补，等待用户编译运行 |

## 组件 6 设计

沿用唯一的 `command_effects` outbox，不另建工作流表或后台上传机制。试运行只在内存构造后续描述；正式事务原子保存执行日志、去重、回执和后续队列。启动恢复入口在 `CloudRepository.resumePending` 的连接检查**之前**，离线也可继续本机效果；正常确认后立即继续。

状态：PENDING 待执行 → RUNNING 进行中 → DONE 完成 / RETRY 失败可重试 / WAITING 等待用户操作。取消协程不伪装失败，保留 RUNNING，重建 Repository/协调器可继续。进程内共享互斥只防多个调用同时执行同一队列；持久恢复依靠下列真实提交凭据，而不是内存锁。一个自动步骤失败后，同方案后面的自动步骤保留待执行；权限/选择器等待不阻塞无依赖的后续步骤。历史及方案详情逐项显示状态、错误和重试/继续入口。

- **偏好**：比较原值、写新值、记 effectId 在同一个 DataStore.edit。若在偏好已写而 Room 未标记 DONE 时中断，恢复只补 DONE，绝不重写用户后来修改的设置。凭据随普通本机备份保存，旧备份缺字段采用空集合。多次修改同一偏好使用虚拟前后状态顺序试运行，撤销冲突基准是整批最终值。逆方案按原步骤逆序折叠同一偏好的纯赋值，恢复最终值到最初值，避免临时切回已被数据库逆操作移除的新账本；原方案每步中间值与描述仍完整保留。
- **附件**：固定文件/关联身份。只查本任务已复制到手机的 source-N，校验规范路径、名称、长度、校验值；拷贝到固定暂存/目标位置。媒体锁下核对最终事务记录和完整关联；插入元数据/单一关联、扩展原撤销日志和 DONE 在同一 Room 事务。中断前若只有文件没有关联，恢复仍用同一身份；不会新增第二份业务附件。物理字节不进入数据库试运行。
- **草稿**：写一次草稿与 WAITING 在同一 Room 事务。恢复只保留待用户打开；“打开草稿”核对媒体会话，进入普通编辑器；“结束引导”不是提交记录。原有草稿阻止覆盖。
- **系统权限**：只有用户按钮才打开权限请求；拒绝则继续等待，也可明确保留现权限结束引导。开启定位/提醒若缺权限，预览就标整批不可撤销；若已有权限，只是可撤销偏好写入。系统权限本身从不自动撤回。
- **导出**：用户选择目录后先持久化目录 URI 与授权；不在打开选择器时创建文件。使用固定的操作专属输出名称，在目录中查找/创建唯一文档，保存输出 URI 后才写字节。进程若在文件创建和 URI 落库之间中断，以同一名称找回；若写完未标 DONE，则校验已准备文件的 SHA-256 后只补完成，不另建副本。中断后已有目标校验不符时不能区分未写完与用户后来改过，因此停止为失败可重试；用户查看明确提示后才能重写同一目标，不自动覆盖后来的外部修改。完整备份使用既有服务；备份的数据库副本把本次导出效果标 DONE，防止日后本机恢复这个备份反过来再次导出自身。原数据库仍等外部文件校验通过才标 DONE。用户导出文件不能撤销。

撤销的事务性边界与正向相同：数据库恢复原子提交，偏好恢复随后执行；如果提交后被用户再次改动，后续步骤明确失败待重试，不能声称已跨 DataStore/文件系统实现一个数据库事务。

## 下沉与 UI 写库盘点

- `StatementsScreen` 的 `deleteStatement` DAO 直写 → `LedgerRepository.deleteStatement`（仅本机调用，云端 forbidden）。
- `CsvImportViewModel` 的 `putProfile` DAO 直写 → `LedgerRepository.saveCsvProfile`。
- `StatementEntry` 的补记和实际金额预填 → `StatementEntryDrafts` 共用函数。本机与云端都核对方向和币种；同币种实际金额同步原金额，避免把流水实际额又被同币种准备逻辑覆盖。
- 商品首次手工录入与云端直接回填共用 `saveDirectReceiptItems`，继续调用原 FileProtocols 校验。既有导出回填和既有编辑修订保护保留。
- 普通草稿分类迁移共用 `reclassifyEntryDraft`，保留其他输入和媒体会话；本机现有分类选择器是对应操作入口。
- 完整搜索 `ui/` 的 `db.` / `Dao()` / `dao.`：剩余为 Flow、图查询、读取或类型导入；写调用已下沉。`AppViewModel` 命中 `bookOverviews` 行是读取，文本中 `deletedAt` 不是删除调用。`FinancialOutlookReader` 保留的 withTransaction 只一致读取金融图并计算投影，不调用写入DAO。

设置入口清单：Settings 的主题/语言/账本资料仍用 book.update；分析页模块顺序/置顶用 preferences.analysis；通知页全部 ReminderPreferences 字段、汇率页自动获取、商户页定位、照片保存方式全部开放。初始化表单的临时输入、列表筛选/折叠、导入时单次照片选项不是持久偏好，不另建全局设置命令。连接/设备/恢复始终禁止。

选择器清单：完整备份、流水原件、当前方案结果副本均有 interactive；附件选择/相机使用 entry.prepareDraft 的 media 引导普通表单。CSV 选文件导入以 entry.create 序列替代。给任务追加源文件/创建新的云端分析任务属于提交界面：它会改变任务资料或开启新的远程任务，不能作为当前结果的账本确认步骤递归执行，仍由用户在提交/补充页发起；没有暗中上传未选资料。

## 测试与静态核对

`CommandWorkflowExecutionTest` 五项（全部 runBlocking<Unit>）：

1. 流水接受/解除/实际金额/补记 + 商品回填 + 本任务附件：预览回滚，源文件暂缺导致 RETRY，重建实例后重试只得到一个关联，完整撤销恢复原行及外键。
2. 分析设置、普通草稿分类、提醒全部选项、定位/自动汇率/照片/切换账本组合：预览不写 DataStore；执行后逐项核对；真实偏好变化阻止整批撤销，恢复后精确撤销。可逆组合关闭预先启用的定位/提醒，不夹带不可逆授权。
3. CSV 导入后用户修改备注 → 命令移入回收站 → 撤销，验证编辑、批次历史、完整原行都保留。
4. DataStore 已写、Room 未标 DONE 时模拟 CancellationException；重建 Repository，用户后来的偏好修改不被重放覆盖，状态完成且只一次实际应用。
5. 不完整记账草稿和备份导出引导：只保存一次草稿、不产生记账；重建仍 WAITING；明确不能整批撤销。

`CommandFlowTest.followupPreviewAndStatusAcrossLooks`：使用 `looks=all visual=true`，输出 `cloud-command-stage-1/*-followup-preview.png` 和 `*-followup-status.png`；断言提交后/本机操作标注，执行后 DONE/WAITING/RETRY 并显示可重试按钮。需逐张看三主题×中日文。原 `ReceiptWorkbenchFlowTest` 的封面/记录跳转流程补 `*-first-product-entry.png`，核对小票无文档时的手工入口。原永久边界集中遍历自动含新增项。没有额外罕见边界矩阵。

静态核对使用 Kotlin PSI 语法解析、JSON解析、协议副本一致性、git diff --check、调用/序列化和写库路径检查；**不等同编译或运行通过**。系统权限、SAF目录授权/文档提供器中断恢复、实际备份导出还需设备实测；本轮未宣称其运行结果。

首轮能力样例已由用户生成。今后注册表改变时，在 android/ 串行执行原生成入口：

```sh
./gradlew -Dyohaku.updateCapabilities=true :app:testDebugUnitTest --tests com.yohaku.expensetracker.data.command.CommandCapabilitiesTest
```

本次没有运行此命令，没有手写两份 capabilities.valid.json。完整文件清单以本次 git diff 为准，主要覆盖 data/command、文件/偏好/草稿业务、BackupService/BackupPreferences、CloudRepository、command UI/小票/流水/CSV页面、上述测试及协议/规格/UI地图/README。

## 验收结果

2026-11-02 本机验收（模拟器 API 36），**通过**：

- 能力样例用 `CommandCapabilitiesTest` 生成入口重新生成；单元测试全部通过；debug 与 androidTest APK 构建通过。
- 首轮发现四项问题，返工一轮后全部修复：持久草稿消费效果类型被改名导致阶段 4 `recurrencePaymentCancellationAndBatchLinkConsumeOnlyMatchingDraft` 回归；撤销说明显示内部 step id；交互草稿预览只显示"0 个目标"；状态截图没有拍到后续步骤状态。
- 返工后 `CommandWorkflowExecutionTest`、`CommandFinanceExecutionTest`、`CommandExecutionTest`、`CommandLedgerExecutionTest`、`CommandPlanningExecutionTest`、`DatabaseUpgradeTest` 共 44 项通过。
- `CommandFlowTest`（`looks=all visual=true`）6 项通过；已查看三主题×中日文 `*-followup-preview.png`、`*-followup-status.png`、`*-followup-draft.png`：提交后执行/需要本机操作标注清楚；状态总览同屏显示"完成""等待你操作（继续）""失败可重试（重试/详情）"；草稿预览显示金额、账户、分类、日期、商户、备注、文件意图，未知项写"待你补完"；撤销说明使用"步骤 N · 业务名称"。
- 首轮相关回归（Backup、BookLifecycle/BookManagement、CsvImportRoundTrip、DraftEditingFlow、FinancialDraft、ReceiptWorkbenchFlow、ReminderSettingsFlow、StatementFileWorkflow、StatementReviewStorage、LocationEntryFlow）63 项中 3 项失败：两项为既有失败（`incomeAndOpeningIncludedRemovalShowTheirActualBudgetEffects`、`ledgerSearchAndParentFilterKeepOriginalRecordsAcrossThemes`），`categoryManagementEditsFavoritesAndCreatesSeparateIncomeWithoutChangingLedger` 单独重跑通过，属批量运行时的偶发失败。
- 边界核对：恢复备份、云端连接凭据/设备管理均未开放；导出备份为 interactive，由用户选择目录；CSV 撤销复用移入回收站。
- 真实网站往返、真实系统权限弹窗与文件选择器仍为用户实测项，未声称已验证。
