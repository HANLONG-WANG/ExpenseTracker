# 云端办理阶段8：全覆盖审计与总验收工具

日期：2026-11-03。基线：阶段7已验收提交 `5216585`。状态：**阶段8已验收通过**（云端办理计划全部完成）。实现由 Codex 完成（其环境无法运行 Gradle/模拟器），编译、测试、独立助手往返与验收在本机完成，见文末验收结果。

## 覆盖审计

[完整覆盖表](cloud-command-coverage.md)按领域逐条列出源码函数/分支、命令或永久边界、内部编排理由。先rg全仓盘点Repository及所有扩展、三个ViewModel（含BackupScreen内的BackupViewModel）、UI DAO/SQL、AppPreferences/BackupPreferences，再按Kotlin声明和实际调用追踪写入。不是按记忆列既有命令。

| 领域 | 路径数 |
|---|---:|
| 账本、账户、分类、联系人 | 24 |
| 记录、转账、退款、模板、草稿 | 21 |
| 预算、活动、周期、预计收入 | 27 |
| 金融、合同、安排、额度、金融草稿 | 28 |
| 文件、媒体、导入、分析设置 | 19 |
| 偏好、月计划、备份 | 25 |
| ViewModel写入动作 | 46 |
| 云端任务、凭据、结果、执行器 | 50 |
| 合计 | **240** |

计数为“函数声明＋有不同准入规则的分支”，同一用户意图经过多个层级会有多条路径，不能与命令数相加。**命令109、边界39、内部编排92**，三类互斥。内部包括自动草稿/列对应缓存、预测/周期派生维护、修订、迁移、传输/去重/回执和执行队列，不把业务遗漏藏在内部类。

- 注册表仍为87个命令，未发现需新增业务命令的遗漏；新增显式forbidden `cloud.task.discard`，共58项，覆盖放弃尚未提交任务时的任务/暂存资料/月评占位删除。此前未知命令也会拒绝，本轮补明确边界和可读替代。
- UI非import的DAO/db命中32处，均为Flow、图/历史/上下文读取；FinancialOutlookReader事务只读。UI直接DAO/SQL写入为0，前期下沉没有遗漏，不重复改动UI。
- CSV列对应来自CsvImportViewModel.previewNow自动保存，属于文件signature解析缓存，无独立配置保存按钮。首次设置/普通/金融草稿临时保存与提交消费，和用户独立放弃输入分别归内部与边界。
- BackupPreferences是DTO，validate只校验；每个字段的开放偏好或缓存/凭据归属已核对，全体恢复仅由backup.restore边界内的AppPreferences.restoreBackup执行。
- 恢复备份、云端凭据/设备、所有永久删除保持永不开放。整批本机撤销及阶段5已批准的批量转循环关系恢复不扩展永久删除权限。

## 协议与能力最终校对

三份协议逐字同步：应用assets、docs/formats、安卓交接包formats。审读87项能力的schema、默认、前提、说明、示例后修改源适配器，不手写生成样例。

1. `entry.confirmActual`原示例仅修改备注，本机validate必然拒绝。改为实际账户金额、入账日与settlementConfirmed=true的可用输入片段。
2. 能力schema的book/category/entry/activity/activityTreat/occurrence生命周期明确限制允许枚举；普通记录kind仅EXPENSE/INCOME/TRANSFER/REFUND，不再把金融专用或永久删除选项暗示为可用。已有业务validate和永久边界仍保留；非法枚举错误提供中日文说明。
3. 修正文案：book.create的budgetMinor名称、book.select/两类草稿仍指未来阶段的文字、账户DEACTIVATE名称、余额池unallocatedShares/adoptedFees真实字段名、预计收入草稿及实例link/pay不同前提。
4. 明确account.save是完整保存且编辑时type仍默认CREDIT；creditLimit省略/null保持；商户note省略/null保持、空字符串清空；规则追加不继承旧版本，外层paymentAccount优先级；预测terms/candidates二选一、原合同起始期数和重算语义。
5. 修正协议“rule内不要嵌引用”的旧禁令：实际schema支持billing.paymentAccountId/deadlines[].billId类型引用。补结构kind与输出类型不同、example只是input片段、真实ID/digest复制、缺省/null、金额/币种和账户范围与选择器主账户语义差异。
6. 补只看附件的填写顺序与最小无步骤回答模板；缺资料返回questions/空steps，forbidden替代不得冒充原需求；USER_FACT只能本机勾选。月评可由monthly-review附件识别，被评月来自汇总，message非空、steps可选，不新增review/month等顶层字段；领取评语不等于执行建议。
7. `CommandCapabilitiesTest.everyCapabilityExamplePassesItsActualInputValidator`集中检查每项示例的真实schema和适配器validate，示例中的前序引用按已声明输出类型提供上下文。只验证输入片段，不声称凭占位ID证明业务可执行；继续用原生成比较测试约束两处能力样例。

能力说明/schema/forbidden改变了catalogDigest。**首版未改写两份capabilities.valid.json，用户随后已生成；本轮保留收到的这两份生成文件，修订后仍按用户要求由验收方重新生成**。在android/串行执行：

```sh
./gradlew -Dyohaku.updateCapabilities=true :app:testDebugUnitTest --tests com.yohaku.expensetracker.data.command.CommandCapabilitiesTest --max-workers=2
```

## 实际附件集合

直接核对CloudRepository.prepareCommandTask、commandExports、monthlyReviewBundle、提交页和任务队列；不是只比较静态文件清单。

| 任务 | 初次实际上传 |
|---|---|
| command（含问答） | 协议MD、当前注册表能力JSON、当前账本结构JSON必带；用户选范围时附records；选定截图/文件按实际上传名和原字节附带 |
| monthly-review | 协议、能力、当前结构、月汇总四份；初次无records及源文件 |
| 原receipt/statement/general analysis | 保留原小票ZIP、银行原件+标识、通用所选文件契约；不是所有general都变成办理 |

源文件先由真实copySources复制到source-N，随后任务和transfers持久化，重试使用同一字节。附件名称冲突沿用uniqueName；attachment.attachSource只能指本任务的用户源文件，不含自动生成文件。结构包括规划/余额/偏好/当前月计划，不包含所有交易、草稿、路径、凭据或月评历史。所选records.workflows只有同范围资料与图片元数据，不自动附图片字节。后续明确补资料仍由原用户入口发起。

## 独立助手往返工具

新增 `CommandAssistantRoundTripTest.kt`，恰有两个 `runBlocking<Unit>` 仪器测试。默认参数在任何数据库/文件操作前Assume跳过。

- `-e assistant export`：建立持久Room `assistant-roundtrip.db`，银行、两信用卡（一个已有规则和两期分期）、6个分类、18笔含误分类记录、预算、周期和月计划。调用生产commandExports/prepareCommandTask，导出真实cloud_transfers到 `/sdcard/Android/data/<应用包>/files/assistant-roundtrip/attachments/`，核对大小/SHA，写README.txt及session/task-request留档，建立plans/results目录。
- `-e assistant verify`：打开同一库及测试偏好，读取plans/*.json；只模拟已下载的远端任务/结果元数据，再走真实receiveCommand→preview。不会直接parse绕开领取验证、不会改写ID/digest，也不confirm。每份txt包含通过/失败、原始原因及异常、可执行性、逐步中日文标题、净变化、缺项/风险/冲突和需勾选项目；完整模型另存preview.json。无步骤ANSWER如实报告，重复plan_id按真实去重说明。
- 无效方案只产生报告；缺失/损坏夹具、IO或预览污染业务快照/偏好使工具失败。每份前后核对持久业务状态；上一份从未执行，不作为下一份的前提。重复verify复用原库与原任务。export不会覆盖既有夹具。
- 时间固定2026-11-02 12:00 Asia/Tokyo，JPY账本、不取网络汇率；README要求自然语言需求注明同一日期。导出普通办理任务；月评四附件契约沿用生产和已有MonthlyCommandTest/CommandFlowTest核对，不伪造带逐笔的月评任务。
- 任务文件与媒体放在files/assistant-roundtrip/；偏好是独立持久DataStore。CloudRepository新增可注入commandPreferences，生产默认仍AppPreferences(context)，测试将同一隔离偏好传给导出与预览，避免真实当前账本ID/偏好进入附件。无真实凭据/网络/Worker，不改主库yohaku.db或真实偏好。

完整编译安装、两模式adb、pull/push、自然语言需求示例、只清专用夹具的重建命令见 [Android README](../../android/README.md#阶段8独立助手往返工具)。首版交付时尚无独立助手结果；本轮已读取首轮返回方案和报告，具体问题与修改见后文，新的独立助手复验仍由用户执行；离线工具不等于真实网站往返。

## 文档与文件

- 新增本记录及coverage；总计划状态改“阶段8实现完成，待验收”，独立助手由用户派发。
- implementation-spec重整完整云端办理章节及实际附件/原子边界/月评/问答，修正云端交接上传表述、小票手工编辑说明与重复章节编号。
- product-design补完整办理、月计划/评语、问答、后续与撤销及永久边界的产品描述。
- ui-implementation-map补当前审计/工具、既有CommandFlowTest全领域三主题中日文入口，修正此前已复验却写未验的说明。
- docs/formats及交接包formats全目录逐文件相同；格式README从阶段1四命令改为当前87命令/五种格式。交接包README.txt替换阶段1补充为最终客户端契约；Word保留原HTTP/API基线，README明确覆盖其早期客户端流程。MCP交接reference/README补Work委托中command/monthly-review、附件、digest、事实与边界要求，网站代码未改。
- Android README补离线工具全部用法，修正阶段7已验收/Room38已生成状态和本阶段能力样例重新生成要求。

生产文件：`data/cloud/CloudRepository.kt`；`data/command/{CommandAdapters,CommandPermanentBoundary,CommandSchema,EntryCommandAdapters,FinanceCommandAdapters,LedgerCommandAdapters,PlanningCommandAdapters,ScheduleCommandAdapters}.kt`；应用assets协议。测试：新增`CommandAssistantRoundTripTest.kt`、扩展`CommandCapabilitiesTest.kt`。其余为上述文档和副本；完整清单以git diff/status为准。无Room表/迁移、UI、既有效果类型或网站变更。

## 静态核对与待验收

- Kotlin PSI语法解析、引用/调用签名人工核对；不是类型编译。协议三副本、两份formats全目录一致；JSON可解析；首版能力样例与当时HEAD原字节一致；本轮保留用户已生成的版本，不手写能力样例；无新增中文紧跟未加花括号的字符串模板；git diff --check通过。
- 既有87个能力样例通过静态JSON Schema结构遍历；本阶段修正的实际确认业务验证由新增JVM测试落实。新源码注册表导出未运行，不能把旧编译产物当本轮通过。
- 待用户：生成能力样例并跑CommandCapabilitiesTest/CommandProtocolTest；编译debug/androidTest；默认运行确认往返测试跳过，再按export→独立助手→verify检查报告；回归CommandLedgerExecutionTest.permanentOperationsRejectTheWholePlanWithAlternatives及相关CommandFlowTest上传/领取流程。Gradle一律串行。
- 没有新命令或UI，不要求本轮新增三主题截图。总验收可用已有CommandFlowTest的looks=all visual=true入口；此前三主题中日文验收证据见阶段3–7，本轮未宣称新截图通过。
- 不确定之处：本轮无法证明编译/设备IO和Room/DataStore运行通过，新线程的修复后独立助手复验尚未执行；新能力样例/catalogDigest须本机重生成。真实网站/Work订阅、系统权限与文件选择器仍是用户实测项。

## 助手往返测试：第1轮发现与返工（已复验）

依据用户提供的独立Codex线程输出：已读取assistant-plans-r1/01–06.json、results/01–06.txt及已有preview.json、assistant-out.md末尾9条反馈。这里记录首轮发现和本轮修改，不回填编译、仪器、视觉或新线程的验收结果。

### 1. 建卡空方案：统一合理推断与日期/额度说明（反馈1、2、3）

首轮01在输入格式上是合法ANSWER，但没有完成“当前无欠款的新卡＋已知额度＋账期规则”的业务意图。原协议一方面要求不足就空steps，另一方面允许不完整金融规则，缺少统一优先级，迫使助手把可提议的日期/模式和可空政策都当成硬缺项。

协议三副本及account.save/financialRule.addVersion/creditLimit.save能力现在区分“助手提案约定”和“Kotlin/JSON省略默认”：用户明确值、已有对象值优先，其次按约定合理推断并在message逐条列假设，仍显式填入required字段；仅关键对象/金额等无法合理确定才追问。当前状态的期初日期、额度配置生效日/占用基准日、规则生效日取任务基准日；无欠款可提议期初及三个占用基准0，USER_FACT仍须手机亲自勾选；日本信用卡未说明模式提议SINGLE，月底为31、结账当天计本期、次月为偏移1，付款日取用户陈述。已有明确分期/循环、历史日期或相反占用证据不能被默认覆盖。

这些值核对了BillingRule、dayInMonth/billingDates、CreditLimitInput及previewCreditLimit/accountCreditLimit：31自动落短月末；日期须basisDate≤effectiveFrom且均不在所用时区的未来。额度未说明的分项上限、sharing、恢复/占用政策使用null，保存允许；usageBasis不是PRINCIPAL_ONLY或缺recovery/usageStart时，自动可用额度估计保持未知。这不是要求停止建卡，也不能为显示数值编造银行政策。协议逐字段解释了必填可空与可选字段，能力源码共用creditLimitInputGuide保持嵌套/独立额度语义一致。

新增完整通用JSON示例：account.save带完整creditLimit＋financialRule.addVersion引用前步，message列日期/模式/结账边界/0占用与未知政策。无欠款不创建零本金预测；已有正本金/来源交易的预测示例仍保留。新增CommandExecutionTest.protocolCurrentDebtFreeCardExamplePreviewsWithUnknownLimitPolicies直接读取应用协议这个示例，核对2步可预览、额度事实勾选、回滚和上下文日期/初始化状态；本轮未运行。

### 2. 源文件未上传：恢复真实完成路径及明确身份（反馈8）

首轮04正确指向本任务source文件，但旧export只prepare，未走上传阶段，transfers.fileId/uploadedAt为空；附件命令拒绝是正确的，不能削弱它的校验。

工具export现在注入只替换HttpURLConnection的离线UploadTransport，调用真实CloudRepository.advance：创建任务→CloudApi.uploadFile实际读取/发送每份字节→返回文件ID→advance保存fileId/uploadedAt→submit/registerSubmitted核对完整文件集合。替身核对上传SHA和文件名，未开启任何网络。export检查所有transfer完成及SUBMITTED；verify复用持久任务ID/inputRevision/input文件信息，只模拟结果元数据，再走原receiveCommand→preview。旧PREPARED夹具明确提示用新版export重建，不直接伪造transfer状态，不绕过AttachSourceAdapter。

生产任务context.source_files新增“初次提交用户源文件实际上传名称”清单，从复制后的source-N transfers生成，不包含本机路径、生成附件或额外资料；同名由uniqueName消歧。五个生成文件完整名称在初次/补充复制时一律保留（含本次未生成的可选records/monthly-review名），避免用户文件冒充自动附件。source_files是初始创建快照，不改写原幂等请求；后补资料看当前task input列表。只看到附件时可排除协议明确列出的五个完整生成名称，其余input为源文件，不能按yohaku-*前缀错误排除用户文件。测试README/task-request留档不是上传input。附件数仍为原5份，没有新增清单附件或UI。

### 3. targets机器约束与本机一致（反馈4）

entryWriteTargetsSchema增加oneOf：ids/select恰一个非null，未用一侧可省略或null，与原validateTargets一致。ids带minItems=1、maxItems=5000、uniqueItems=true，保留已有ID/前序类型引用。CommandSchema实际解释oneOf及这三个数组约束；不是只导出关键字但手机忽略。选择器实际匹配数仍由本机normalize验证，JSON Schema不能预知查询结果。

新增CommandProtocolTest.exportedTargetsSchemaMatchesExclusiveSelectionAndSizeLimits，用同一组有意义输入同时比较导出schema解释与领域校验（两类选择、显式null、缺选择、同时选择、空/重复/超限数组）。本轮未运行，不扩大为完整边界矩阵。

### 4. 逐命令复用文案清理（反馈9）

去掉CommandCapability默认unavailable中的“不完整预测”，只在预测适配器保留其实际缺项语义。后续能力按附件、偏好、草稿、导出分别写提交前拒绝/提交后重试/等待本机动作及比较字段，attachment.attachSource不再出现预测。automaticRates不再声称需要前台定位或权限。

account/merchant/friend.setActive各自只描述自己的引用类型和限制。账本、分类、商户/地点/朋友、模板、记录新建/编辑/实际确认、预算、活动/预留/请客、周期/实例、预计收入、流水的复用defaults/prerequisites/description按实际动作拆分，移除例如“新建记录要求1–5000已有目标”“移动模板要求重新满足完整保存字段”“解除流水关联描述为接受差异”等混淆；通用事务/撤销机制仍共用。完整schema和执行校验不因文案推断约定放松。

### 5. 月计划、初始化状态与统计口径（反馈5、6、7）

- 月计划无生效月份：保持每账本一份当前文本，不新增定时任务/未来版本。协议及MonthlyPlanAdapter说明预算可设未来month，文本执行时即时更新以表达未来意图；message明确时间差/假设。若用户明确要求届时才改文本，说明不能延时保存，可先提议支持的未来预算，不冒充已经安排。这是当前产品语义，不是输入错误。
- 初始化状态：应该导出。book.values增加initialized/setupStep（原行白名单值），可预判月计划前提；手机仍重查。旧附件缺字段不等于false。
- 任务日期：LedgerContextExport增加asOfDate/zoneId，来自同一个生成时刻及Repository时区，供只有附件的助手确定“当前/下个月”。字段有默认null以兼容旧持久月评快照，两个ledger-context schema同步允许；新导出总是填写。
- 范围与退款：不自动扩大用户选择的记录上传范围。协议与结构coverage明确日期范围按每条记录date，不补范围外原消费/退款或全部费用组成；普通问答只能声称可见范围快照口径。需要本机退款回原消费月的净消费/预算执行时使用monthly-review汇总或请用户补选资料。首轮05已主动注明这个限制，本轮把规则固定下来，不臆造缺失退款。

### 文件与静态核对

本轮Kotlin：CommandAssistantRoundTripTest、CommandExecutionTest、CommandProtocolTest；CloudRepository；CommandExports、CommandSchema、CommandRegistry、CommandAdapters、FinanceCommandAdapters、FollowupCommandAdapters、LedgerCommandAdapters、EntryCommandAdapters、PlanningCommandAdapters、ScheduleCommandAdapters、FileCommandAdapters、MonthlyReview。

协议/文档：三处yohaku-command-protocol-v1.md；两处ledger-context.v1.schema.json与formats/README；android/README；implementation-spec、product-design、ui-implementation-map；安卓交接包README.txt；MCP reference/README的Work委托语；适配器检查表、本记录及覆盖表说明。没有新增命令、Room迁移、效果类型或界面布局。写入覆盖计数仍为240（109命令、39边界、92内部）。

静态核对只包含Kotlin PSI语法、JSON/示例结构、协议及格式副本一致性、上传/领取调用链与git diff --check，不等于类型编译或运行通过。用户已经生成的两份能力样例本轮不改写；这些能力说明/schema变化需要用户再次生成catalogDigest并重建export，再派新的独立助手。首轮01–06原方案和报告未改动；本轮没有填入复验成功结论。

本轮静态结果：当前工作区18个新增/修改Kotlin文件PSI语法错误为0（含前轮尚未提交文件）；6个协议JSON代码块均可解析，新建卡示例按已有真实能力字段schema核对通过；三份协议及formats镜像一致，git diff --check通过。两份用户生成能力样例仍为catalogDigest `8026327037e0a4a7406b56601369fb9d6cb349021ef58c4bd116917d291c3aba`，本轮未重生成。这些检查不等于新版注册表/JVM测试、Room或设备往返运行通过。

## 验收结果

2026-11-03 本机验收（模拟器 API 36），**通过**：

- 能力样例由生成入口重新生成；单元测试全部通过；debug 与 androidTest APK 构建通过。返工引入一处编译错误（`ContactAdapter` 漏传能力说明参数），由验收方按原意为商户/朋友/地点分别补写说明。
- 覆盖审计：240 条写入路径（命令 109、永久边界 39、内部编排 92），抽查内部编排理由成立；UI 直接写库为 0。
- 独立助手往返（两轮，每轮都是全新、不了解实现的 Codex 线程，只给 export 出的真实附件、任务正文和 6 条自然语言需求：建乐天卡+额度+还款规则、批量改 10 月错分类、下月餐饮预算+月计划、附上咖啡凭据、10 月餐饮问答、彻底删除家电卡）：
  - 第 1 轮：02/03/05/06 通过；01 因协议未说明可推断项而返回空方案（核心建卡场景失败）；04 因往返夹具未登记上传完成而预览失败。返工后见上一节。
  - 第 2 轮：6 份全部通过本机领取与预览。01 生成账户、额度配置与 SINGLE 规则，零占用基准作为 USER_FACT 留给手机勾选，假设逐条写在 message；04 预览为提交后附件关联；06 拒绝永久删除并给出停用/回收站替代。
- 第 2 轮助手的协议意见中，验收方直接修正三处：`account.save.type` 增加账户类型枚举（能力导出与两份 command-plan schema 同步）；`financialRule.addVersion` 付款账户由 "active cash account" 改为 "active non-debt account (CASH, BANK or IC)"；`account.setActive` 前提改为如实说明停用不受依赖阻止、不结清或删除任何内容。其余意见（日期/月份格式约束、月汇总只读能力、方案顶层机器可读 schema、源文件元数据）不影响正确性，留作后续改进。这三处修改改变了 catalogDigest，第 2 轮方案未在新 digest 上重跑；修改仅为说明文字与已被方案满足的枚举。
- 修正后 `MonthlyCommandTest`、`CommandWorkflowExecutionTest`、`CommandFinanceExecutionTest`、`CommandExecutionTest`、`CommandLedgerExecutionTest`、`CommandPlanningExecutionTest`、`DatabaseUpgradeTest`、`CommandAssistantRoundTripTest`（默认跳过）共 50 项通过；`CommandFlowTest` 7 项通过（`looks=all visual=true` 截图在返工前一轮已查看，本阶段未改 UI）。
- 用户实测项（未声称已验证）：真实网站往返（含任务 context 新增的 `source_files` 字段被网站接受）、真实 Work 助手按附件写方案、真实通知送达、系统权限弹窗与文件选择器。

