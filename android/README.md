# 余量 Android · 阶段E 0.5.0

原生 Kotlin / Compose 本地记账应用。阶段E P21–P25的实现与本轮可执行验收完成，交付0.5.0/code5。用户明确选择本轮交付APK，用户实机检查保持未验。准确状态见[阶段E记录](verification/phase-e-status.md)与[UI01–UI27验收](../docs/phase-e-acceptance.md)。历史范围见[开发计划](../docs/plans/development-plan.md)、[阶段A](verification/phase-a-status.md)、[阶段B](verification/phase-b-status.md)、[阶段C](verification/phase-c-status.md)、[阶段D](verification/phase-d-status.md)。

## 安装包

- [正式APK 0.5.0](artifacts/yohaku-release-0.5.0.apk) · [SHA-256](artifacts/yohaku-release-0.5.0.apk.sha256)：包名`com.yohaku.expensetracker`，固定正式签名。
- [开发APK 0.5.0](artifacts/yohaku-dev-0.5.0.apk) · [SHA-256](artifacts/yohaku-dev-0.5.0.apk.sha256)：包名`com.yohaku.expensetracker.dev`，沿用阶段A–D开发证书。
- Android 10+（minSdk29）、target/compile SDK37、versionCode5、Room v33（信用卡恢复延迟、指定付款月/费用，迁移32→33）。三主题、中日文按账本保存。
- 开发包可覆盖原开发版本。正式包独立安装，与开发包并存，不自动转移开发包资料。覆盖升级不要先卸载；卸载或清除应用存储会删除账本与内部附件。提供手动本地完整备份/恢复，系统云备份仍关闭。
- 当前仅专用API36模拟器验证，用户实机未连接。精简实机检查、密钥位置和完整安装升级说明见[发行说明](RELEASE.md)。

```sh
adb devices
adb -s <serial> install -r artifacts/yohaku-dev-0.5.0.apk
adb -s <serial> install -r artifacts/yohaku-release-0.5.0.apk
```

历史交付：[D 0.4.0](artifacts/yohaku-phase-d-0.4.0.apk)、[C 0.3.0](artifacts/yohaku-phase-c-0.3.0.apk)、[B 0.2.0](artifacts/yohaku-phase-b-0.2.0.apk)、[A 0.1.0](artifacts/yohaku-phase-a-0.1.0.apk)。这些历史包不包含阶段E的真实通知/定位和完整资料管理。

## 功能范围

设置页依次为「外观与语言」「账本与分类」「提醒与汇率」「小票、流水与云端」「数据」「其他」。外观主题行显示当前主题名，进入 `appearance` 二级页选择主题（缩略预览与选中标记保留）；语言切换位于同组。「小票、流水与云端」依次进入 `receipts`、`statements`、`cloud`；「数据」含「备份与恢复」（`backup`）与「从 CSV 导入」（`csv-import`）；「其他」只含回收站。「账本与分类」还提供「商户与地点」（`merchants`）入口，分析页原入口保留。

账本切换只保留顶部当前账本按钮、设置→独立账本管理及其下级管理页。收支、转账、退款表单不再显示账本/主题工具行；顶部账本按钮在记账中打开切换弹层，其余正常页面进入账本页，金融草稿保护继续沿用。主题只在设置→外观主题切换；离开有手动输入的记账时可保留或放弃草稿；未选分类的非转账草稿，或相对打开基线仅改分类/自动定位商户的草稿，直接放弃并离开。恢复的草稿没有基线，已选分类或转账时仍提示。顶部不再提供语言按钮。首次设置的顶部设置按钮打开同时含主题和语言的设置弹层，切换保留未保存设置输入。

并列短操作使用 `YActionRow` 自适应排布：杂志/手账/小物分别沿用 8/10/12dp 间距，按钮保持各主题原有字形、边框和阴影；按自然宽度排列，空间不足时整项换行，主按钮占满所在行剩余宽度；确认弹层中并列的二选一（放弃 / 继续编辑等）都用本主题的次要按钮，不与无框链接混排。长文案或不同层级操作仍纵排。

- 空白设置、独立账本、两级支出/收入分类（可选分类图标）、现金/银行/交通IC/信用卡/贷款账户；期初债务不重复计收入/消费。
- 全部流水的列表/日历切换：日历按月显示每日净支出（与月度消费同口径）、日均预算进度，点选日期查看当天流水并可直接记在这一天。
- 收支、历史补录、原ID编辑/修订、按账本草稿及进程恢复。原币/账户币/统计币、预计与实际、部分/分次退款、同币转账/异币换汇、两端独立日期/在途及明确费用。
- 自然月/分类预算、周期待办、确认或关联已有记录、活动预算/资金预留、多人互请；分析模块排序、分类/商户/活动筛选与下钻。
- 用户配置的一次付/分期/リボ/固定贷款/循环贷款规则，生效版本、计息/费用/舍入/时区及逐期计划；缺项不假设零费用，特殊算法使用平台已知手动金额，无机构预设。
- 信用卡消费付款方式、自动リボ/余额池、总/子/共享额度；实际借还、部分/提前/跨币种付款、待平台清偿/未知拆分。纯试算不修改正式账，确认办理保留原账单和历史。
- 预计收入及实际到账/关联、按实际资金账户的逐日预测、基础/含预计收入两情景、预算与资金缺口分列、付款日历及重要事项。其他账户资金和信用额度不自动抵消扣款账户缺口。
- Frankfurter v2参考汇率、来源日期/持久缓存、自动开关/手动刷新/离线手填；请求仅含币种/日期，不上传账目，参考金额不改写实际账。
- 真实附件/小票多选、内部副本、图片预览、音频播放、外部只读打开；每个文件严格小于100 MB。
- 云端返回的流水JSON整体校验/预览、一对一核对、独立人工差异、按类型正式补记；小票多图ZIP/原字节校验、云端商品结果回填/替换/修订与商品编辑；银行流水核对与小票商品回填不自动写账，无OCR/银行连接；这两种云端转换不提供本地导出或手动选文件导入。
- 五类重要事项的真实通知、非精确AlarmManager调度、持久去重、修改撤销、重启/升级/时区变化重建；金额优先展示，点击进入所属账本和目标月份。用户保存开启时才申请通知权限，拒绝仍能记账。后台时机受Android及厂商省电策略影响。
- 仅今天的新建支出主动前台单次定位、保存地点匹配、默认100米、精度/唯一候选控制自动填入；多个候选手选，手改/清空/旧记录优先。离开前台取消，不申请后台位置权限或记录轨迹。
- 云端分析交接：导入网页生成的连接配置（Keystore加密），把所选小票原图ZIP或银行资料交给私人分析台；中断后按同一份内部文件整份重传（无断点续传）、领取结果核验大小/SHA后走原有预览确认，回执独立重试。
- 整本归档/回收/恢复/永久清理；账户停用/启用/无引用删除；分类（含二级）归档/取消归档/改父级/明确迁移；活动删除影响与历史保留；记录关联组回收/恢复/永久清空及文件最后引用清理。

提供用户手动的本地完整备份/恢复（整份替换、不加密、不自动备份）；没有账户登录、云同步或应用锁；云端交接只上传用户逐次选中的资料。未知金额保持未知，未确认清偿不假结清。产品包不含测试种子、评审入口或测试验证器。

## 记账体验（2026-10-31 源码更新）

本节对应当前源码，文首既有发布 APK 未更新。实现位置、测试方法及结果见[本轮交付记录](verification/entry-experience-2026-10-31.md)。

- 新建分类页与转账页共用支出/收入/转账三等分标签；编辑、周期确认和退款不显示类型切换。切换保留日期、备注、附件会话及普通附件/小票，保留原返回目标。
- 付款账户按同账本同类型最后一次保存记忆（修订时间优先于交易日期）；转账记住上次两端，账户页显式指定优先。停用或不适用的账户回退到可用账户。信用卡付款方式按卡跨支出/转账记忆：分期带回期数，指定月只带回方式，月份与费用重新填写；编辑沿用已保存方式。
- 现金/银行/交通 IC/信用卡可转出，只有资金账户可转入，贷款不可转账。信用卡充值增加该卡欠款，转出总额（含已包含的费用）进入付款方式、账单预测、额度和リボ余额池；本金不计消费，明确手续费仍计支出。账单规则不足时沿用既有待补规则，不自动假设一次付。
- 支出商户输入提供最多5个启用商户的联想及可搜索一览，忽略大小写和全半角，名称完整匹配时关联商户；可清除或修改。候选出现或键盘弹出时自动滚到可见位置。非今天记录不请求当前位置、不显示位置候选，日期改为非今天时清除定位自动带入的商户，保留手填/手选。
- 保存前使用同一份变更预览检查余额：资金账户余额减少且变为负数时先确认，确认后再进入原保存/影响审核流程；债务账户不提示。详情回收后替换当前路由，返回不会重开已回收记录。
- 顶栏账本名按全屏宽度居中，并按两侧实测宽度限宽；首页最近支出显示5笔。

必要验证：`EntryDefaultsTest`（保存顺序、付款方式、草稿基线、负余额、名称规范化）；`TransferTransactionsTest`（信用卡充值 posting/预测/额度/对账/现金时间线及净余额预览）；`EntryExperienceFlowTest`（tab/附件/默认账户、负余额两种保存路径、商户联想与一览）；更新 `DraftEditingFlowTest`、`LocationEntryFlowTest`、`EntryLifecycleFlowTest`、`ThemeControlsTest`。本轮仅运行 JVM 测试和两个 APK 构建，不启动模拟器或运行 connected 测试；相关 UI 类在设备上使用 `looks=all` + `visual=true` 并查看截图，实际视觉结果仍待验。

## 从其他记账软件迁入 CSV

设置→数据→从 CSV 导入。它在用户明确确认后生成正式收支，参与分析、预算和日历；与银行流水核对（仅保存独立核对资料）完全独立。支持 BOM、严格 UTF-8 / Shift_JIS / GBK 回退与手动编码、逗号/制表符/分号、引号内换行、可选表头、通用列对应、类型/正负/全部支出规则及分类对应。收入中的「退款」作为普通收入，不建立退款关联。

须选择本位币账户，可沿用新建账户流程。期初日当天或之前的记录标为已包含期初、无 posting、不改变余额；其余正常影响余额。预览分别列出两部分的收支笔数/金额，问题行须修改日期/金额或明确跳过；控制字符清除。文件内部相同行保留，已有正式账目的同日/同收支/同币种金额/同备注/商户候选默认跳过，可逐行改为迁入；相同 SHA 文件有明显提示。

一次 Room 事务保存整批、新分类、完整 event/settlement/posting/首个 revision 及对应设置。历史批次可查看记录、撤销整批到回收站，30 天内整组恢复；原始 CSV 不持久保存，只记文件名/SHA/批次统计。列、日期、收支、分类、账户等对应按账本+表头签名保存，并纳入完整备份。无多币种、转账或退款关联导入。

`CsvParserTest` 覆盖通用解析；`YOHAKU_CSV_SAMPLE` 环境变量可指定仅本地读取的样本，默认不读取私人文件。`CsvImportRoundTripTest` 使用虚构 CSV 验证导入→撤销→恢复；传 `csvVisual=true` 可额外输出三主题中日文截图至应用外部目录 `csv-visual/`。29→30 验证见 `DatabaseUpgradeTest.version29AddsCsvTablesAndKeepsExistingLedger`。本轮已在原本运行的设备上通过这两项仪器验证，并检查320dp、字体1.3下三主题×中日文页面与确认弹层；未启动模拟器。真实样本仅在主机上做 JVM 解析统计，未放入设备或测试资源。

## 信用卡付款与额度

- 「额度与恢复」→ 编辑额度条件 → 按付款日恢复，可设置 N 个自然日（默认0），付款日后第 N 天预计恢复；实际恢复记录优先。
- 一次付规则可选两段不重叠的ボーナス利用期间及付款月日，随版本保存。记账选择「ボーナス一括」自动计算，缺设置或期间不匹配会提示补齐；「指定月一次付」填 YYYY-MM，不早于正常付款月份，按该月规则还款日支付。手续费未知留空、免费填0，已知费用加入单期；未知时合计与备款待补。两者仍是 SINGLE/CARD_ENTRY，不新增金融模式。
- 「调整付款方案」选择一次付后，可按规则下一期、ボーナス或指定月生成首次付款日。
- 信用卡总览本期及月账单的付款日分组 → 「まとめてリボ」：选择未付款且未逾期的一次付账单（包括已确认），填写不超过所选本金的转换金额，按消费日和创建顺序分配，最后一笔可部分转入既有リボ池。原费用和未转本金保留原日；办理费沿用并池条件，只计一次，未确认费用不能提交。无池时进入既有建池表单。
- 批次在一个事务内保存，并在总览/月账单提供整组撤销。保存前后快照与同一 `undoGroupId` 保留于现有安排输入/计划历史；后续付款或安排已变化时不能直接撤销。退款归属沿用安排重核，不另建账务机制。

Room 32→33 新增 `credit_limit_configs.recoveryDelayDays INTEGER NOT NULL DEFAULT 0`、`events.cardPaymentMonth TEXT`、`events.cardPaymentFeeMinor INTEGER`（后两项默认NULL）。ボーナス规则和批次追溯保存在现有版本JSON中。完整备份仍为原数据库归档，`BACKUP_SCHEMA_VERSION` 跟随33，旧备份通过相同迁移升级；旧记录不会被设成新的付款方式。

必要验证：`CreditCardOptionsTest`（日期、费用、额度恢复和分配守恒），`CreditCardOptionsStorageTest`（单期保存、已确认部分转换、事务回滚/整组撤销），`DatabaseUpgradeTest.version32KeepsLimitRecoveryOnPaymentDay`（老配置延迟0）。本次仪器测试仅编译，未执行；三主题中日文视觉检查交由用户完成。

## 分类图标

分类编辑页新增「分类图标」：新建时按名称自动推荐，并列出其他推荐；手动选过后改名不再覆盖。「更换」打开选择弹层：分组 Tab（有名称推荐时多一个「★ 推荐」）、中/日/英文搜索、顶部预览，二级分类可改回沿用一级。Tab 栏和「使用这个图标」在弹层滚动时分别吸顶、吸底。图标显示于记账选分类、分类管理（二级行标注「沿用一级」）、全部流水、分类预算与小物主题的分析分类卡片；徽记由 `CategoryIconBadge` 按主题绘制。

Room 31→32 为分类新增可空 `icon`（Tabler id），并按名称回填。一级使用首个推荐；二级只有推荐与父级回填值不同时保存。显示解析依次使用有效的自身图标、父级图标、`category`。完整备份包含此字段，旧备份恢复走同一迁移。

精选目录为 Tabler Icons 3.49.0 outline 的 450 个图标、10 组；MIT 原文见 `app/src/main/assets/licenses/Tabler-Icons-MIT.txt`。从仓库根目录重新生成 JS 与 Kotlin：

```sh
python3 -I android/tools/build-category-icons.py <tabler-package-dir> design/category-icon-catalog.js --kotlin android/app/src/main/java/com/yohaku/expensetracker/domain/category/CategoryIconCatalog.kt
```

Kotlin 每组存为独立字符串常量并懒解析，提供 `categoryIconGroups`、`categoryIconOf(id)` 与 `categoryIconGroupOf(iconId)`。推荐、搜索及继承解析见 `domain/category/CategoryIcons.kt`。`saveCategory` 的末尾参数 `CategoryIconChoice.Auto` 在新建时自动推荐、编辑时保留；`Set(id)` 指定图标，`Set(null)` 清空自有图标。CSV 新建分类沿用 Auto。

## 完整备份 / 恢复

本节对应当前源码及新构建的 debug 包；文首既有发布 APK 尚未更新。

设置→数据→备份与恢复；新手机也可在首次设置直接恢复。SAF 创建 `yohaku-backup-YYYYMMDD-HHmm.yohaku`（ZIP），包含 `manifest.json`（`yohaku.backup` v1、应用/schema 版本、创建时间、账本记录数、附件统计及每个载荷的路径/大小/SHA-256）、一致性快照 `database/yohaku.db`、全部 `attachments/`、用户设置 `preferences.json`。

包含全库、草稿/回收站、附件原图、提醒设置/投递去重状态、自动汇率/缓存、定位开关与账本选择。排除云端上传暂存、设备绑定凭证和系统调度/权限；本机云端凭证不变。恢复完整校验并在暂存副本迁移后显示摘要，确认才整份替换；当前数据不会保留，可先自行导出，不自动备份。替换失败回滚，成功重启进程并重建提醒。系统云备份仍关闭；无密码/加密、同步、定期提醒或单账本合并，CSV 迁入使用独立的正式记账流程。详见[格式与执行步骤](../docs/implementation-spec.md#34-手动完整备份--恢复formatversion1)。

验证：`BackupArchiveTest` 覆盖清单/未来版本、路径穿越、大小/SHA 与中途替换失败回滚；`BackupRoundTripTest` 在独立文件目录做全库/附件/设置往返；`BackupVisualTest` 生成三主题×中日文原生页面/确认弹层截图至应用外部文件目录 `backup-visual/`。本轮未连接设备且不启动模拟器，仪器测试运行、截图目视及实际 SAF/跨进程重启仍待设备验收。

## 常用入口

- 设置→独立账本管理→归档与整本回收。归档保留未完提醒；回收取消相应通知，30天内恢复原ID/归档状态。没有正常账本时仍能查看保留资料。回收站不是备份，永久删除/清空不可撤销。
- 账户详情→账户状态与停用。停用保留历史与债务处理；有记录、计划、核对、预留或草稿引用时不能删除。
- 分类编辑→「归档、调整归属或删除」。一级和二级分类都可归档：归档后不出现在记账、预算、常用及 CSV 对应等选择中，记录、预算与周期安排保留；归档一级连同子分类一起收起，子分类各自的归档状态不变。分类管理页每个用途末尾有「已归档」折叠区（杂志索引行/手账信封/小物抽屉），列出归档的一级组和仍在使用的一级下单独归档的二级，可直接「取消归档」或点开管理。数据沿用 `categories.active`（false 即已归档），无迁移。
- 迁移需选择同用途目标和预算处理方式，确认页展示历史月份/活动预算影响，金额与原记录ID保持。
- 活动详情→查看删除影响。列出跨月回归日常后的预算，保留记录/资金/文件及原互请历史；停止该活动安排并释放未用预留。结束活动与删除活动为不同操作。
- 原记录→移至回收站。原消费与本次关联退款、转账两端按组处理；以前独立删除的退款不混入。恢复旧人工核对后须重新确认；独立合同、确认账单和实际还款不因清理来源消费而抹去。
- 设置→商户与地点，或分析→商户资料与保存地点。手填坐标或“使用当前位置”后仍须确认保存；记账页主动启用定位，拒绝/系统关闭仍可手填。
- 设置→重要提醒。保存开启才请求权限；系统不允许时可进入系统通知设置。

30天到期资格严格按时间判定；物理清理由启动及每日唯一WorkManager任务执行，后台可能延后。原文件仍被其他记录引用时保留。

## 云端分析交接

小票识别和流水转换只走云端分析台，应用内没有本地导出或手动选文件导入。契约与限制见[交接包](../docs/android-cloud-handoff-package/)、[正式格式](../docs/formats/README.md)和[落地规格3.2](../docs/implementation-spec.md)。

1. 网站登录→设备连接→生成配置。设置→云端分析交接台→导入连接配置（粘贴或选文件），核对站点/设备ID后保存，自动测试`GET /api/v1/types`。
2. 小票工作台选未整理小票→上传所选小票；或流水核对→交给云端转换流水（选账户与资料）；或交接台→通用分析任务。资料先复制到内部目录，再由WorkManager串行上传、提交。
3. 交接台前台每30秒刷新进行中任务，也可手动刷新。待补资料可补充文件；结果可领取时下载核验后进入预览：流水检查账户/日期语义/覆盖/币种/汇总/疑点后确认导入，再逐笔匹配或明确差异；商品明细整体预览后回填，旧修订/已有明细须确认替换，新增原图仍待整理。保存后才发送imported回执。
4. 解除连接只删除本机凭证，任务与导入记录保留。没有推送、扫码绑定或后台同步。

本地联调（仅可调试包允许`http://10.0.2.2`）：

```sh
python3 tools/cloud_mock_server.py --config /tmp/yohaku-mock-config.json   # 输出可导入的连接配置
curl http://127.0.0.1:8787/admin/tasks                                     # 查看任务
curl -X POST -H 'Content-Type: application/json' -d '{"status":"needs_info"}' http://127.0.0.1:8787/admin/tasks/<taskId>/status
curl -X POST --data-binary @result.json 'http://127.0.0.1:8787/admin/tasks/<taskId>/results?summary=...'   # 发布结果并置ready
```

## 构建与验证

JDK17、AGP9.3.3、Gradle Wrapper9.5.1、Compose BOM2026.09.00。SDK由`local.properties`或`ANDROID_HOME`指定；正式密钥配置见[发行说明](RELEASE.md)。

```sh
# 串行执行；每条完成后再启动下一条，禁止并行 Gradle。
./gradlew :app:testDebugUnitTest --max-workers=2
./gradlew :app:assembleDebug --max-workers=2
./gradlew :app:assembleDebugAndroidTest --max-workers=2
# 如需 release / lint，也分别单独执行并加 --max-workers=2。
```

### 三层测试

以下命令在 `android` 目录执行，每条完成后再启动下一条。日常外观矩阵按测试类完整名称的 JVM `String.hashCode()` 确定性选择一组（三主题 × 中日文）；不同类分散覆盖。`looks=all` 恢复六组，显式 `theme` / `language` 返回所有匹配组合，优先于默认抽取。原本固定语言的专项保留该语言；测试主题/语言切换本身的动作不缩减。金融模式等业务循环不缩减。

1. **每次改动**：单元测试 + 默认参数下相关的数据/逻辑类和模块流程类。跨模块修改可跑默认全量。默认成功路径不写截图、bounds JSON 或尺寸 CSV，失败取证仍保留。

```sh
./gradlew :app:testDebugUnitTest --max-workers=2
# 示例数据/逻辑白名单；按改动模块增补。
./gradlew :app:connectedDebugAndroidTest --max-workers=2 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yohaku.expensetracker.DatabaseUpgradeTest,com.yohaku.expensetracker.CurrencyTransactionsTest,com.yohaku.expensetracker.TransferTransactionsTest
# 示例：分析模块流程（默认单外观）
./gradlew :app:connectedDebugAndroidTest --max-workers=2 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yohaku.expensetracker.AnalysisFlowTest
# 默认全量：业务/存储，单外观；视觉、系统前置和性能专项显示 skipped。
./gradlew :app:connectedDebugAndroidTest --max-workers=2
```

2. **改了 UI**：相关流程类加 `looks=all` 和 `visual=true`，完成后查看截图；公共控件改动加 `ThemeControlsTest` 和相关 Presentation 类。

```sh
./gradlew :app:connectedDebugAndroidTest --max-workers=2 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yohaku.expensetracker.AnalysisFlowTest \
  -Pandroid.testInstrumentationRunnerArguments.looks=all \
  -Pandroid.testInstrumentationRunnerArguments.visual=true
# 只定位 journal / ja 时可显式筛选：
./gradlew :app:connectedDebugAndroidTest --max-workers=2 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yohaku.expensetracker.AnalysisFlowTest \
  -Pandroid.testInstrumentationRunnerArguments.theme=journal \
  -Pandroid.testInstrumentationRunnerArguments.language=ja \
  -Pandroid.testInstrumentationRunnerArguments.visual=true
```

3. **发版前或大改后**：先跑默认全量，再跑十个纯视觉类的完整矩阵；系统和性能专项按各自前提、逐方法运行。`system=true` 不会自动授予权限、提供 GPS、安装 SAF provider，或编排进程恢复。

```sh
./gradlew :app:connectedDebugAndroidTest --max-workers=2
visual_classes=com.yohaku.expensetracker.BackupVisualTest,com.yohaku.expensetracker.IntroPresentationTest,com.yohaku.expensetracker.LeadCoverTest,com.yohaku.expensetracker.NumberFontTest,com.yohaku.expensetracker.RecordPresentationTest,com.yohaku.expensetracker.SectionHeadingTest,com.yohaku.expensetracker.SetupPresentationTest,com.yohaku.expensetracker.SheetBackdropTest,com.yohaku.expensetracker.ThemePaperTest,com.yohaku.expensetracker.SystemWindowThemeTest
./gradlew :app:connectedDebugAndroidTest --max-workers=2 \
  "-Pandroid.testInstrumentationRunnerArguments.class=$visual_classes" \
  -Pandroid.testInstrumentationRunnerArguments.visual=true \
  -Pandroid.testInstrumentationRunnerArguments.looks=all
# 前提：外部已撤销定位权限；其他 GPS/通知状态分别准备、逐方法执行。
./gradlew :app:connectedDebugAndroidTest --max-workers=2 \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.yohaku.expensetracker.ForegroundLocationSystemTest#deniedPermissionIsExplicit' \
  -Pandroid.testInstrumentationRunnerArguments.system=true
./gradlew :app:connectedDebugAndroidTest --max-workers=2 \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.yohaku.expensetracker.LargeLedgerTest,com.yohaku.expensetracker.MediaStorageTest#strictPerFileLimitStreamsAndLeavesNoPartialBlobOrDraftReference' \
  -Pandroid.testInstrumentationRunnerArguments.perf=true
```

已安装 debug app 与 androidTest APK 时，同样可用 AndroidJUnitRunner 直接运行（下面只用于手动设备验证，不代替 JVM 测试）：

```sh
adb shell am instrument -w -r \
  -e class com.yohaku.expensetracker.CurrencyTransactionsTest,com.yohaku.expensetracker.AnalysisFlowTest \
  com.yohaku.expensetracker.dev.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class com.yohaku.expensetracker.AnalysisFlowTest \
  -e looks all -e visual true \
  com.yohaku.expensetracker.dev.test/androidx.test.runner.AndroidJUnitRunner
# 默认全量及视觉套件；visual_classes 使用上面的完整定义。
adb shell am instrument -w -r com.yohaku.expensetracker.dev.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class "$visual_classes" -e looks all -e visual true \
  com.yohaku.expensetracker.dev.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r \
  -e class 'com.yohaku.expensetracker.ForegroundLocationSystemTest#deniedPermissionIsExplicit' -e system true \
  com.yohaku.expensetracker.dev.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r \
  -e class 'com.yohaku.expensetracker.LargeLedgerTest,com.yohaku.expensetracker.MediaStorageTest#strictPerFileLimitStreamsAndLeavesNoPartialBlobOrDraftReference' -e perf true \
  com.yohaku.expensetracker.dev.test/androidx.test.runner.AndroidJUnitRunner
```

截图仍在各测试约定的 `files/verification/...` 或 external-files `phase-...` 目录；`RegressionFailureArtifacts` 的失败截图与语义树不受 `visual` 控制。`ThemeControlsTest` 默认保留点击、输入、禁用和选择状态断言，精确尺寸及截图只在视觉模式验证。`SetupFlowTest` 保留四步完成、导航和不生成记录的行为，已移除 HTML 坐标锚点比对；`SetupPresentationTest` 的视觉基线断言保留。

### 自定义仪器参数

布尔参数只接受字符串 `true` 为开启；未给参数保持原默认。`class` 是 Runner 自带的类/方法白名单，可用逗号组合，不是项目自定义参数。

| 参数 | 含义 |
| --- | --- |
| `looks=all` | 全部六组外观；不传时每类确定性单外观。 |
| `theme=magazine/journal/objects`、`language=zh/ja` | 显式筛选所有匹配组合，可与 `looks=all` 同用；主题/语言切换场景仍执行自身的切换动作。 |
| `visual=true` | 成功路径截图、bounds/尺寸输出；运行十个纯视觉类和混合类中的视觉断言。单独开启仍按默认外观抽取。 |
| `system=true` | `ForegroundLocationSystemTest`、`ReminderSystemTest`、`MediaFlowTest` 的真实 SAF/跨 UID 方法、`LocationEntryFlowTest` 的真实权限/GPS 方法。必须按各方法准备外部状态。 |
| `perf=true` | `LargeLedgerTest` 和 `MediaStorageTest#strictPerFileLimitStreamsAndLeavesNoPartialBlobOrDraftReference`（100MB）。 |
| `captureSuffix` | `verification` 截图/尺寸目录的后缀，用于区分屏宽、字体等配置。 |
| `networkSmoke=true` | `ReferenceRateStorageTest` 的真实汇率服务冒烟；普通 VM 测试注入离线 transport（固定测试汇率 1），保留原偏好存储和时钟。 |
| `processRecovery=true`、`phaseBRecovery=true`、`planningRecovery=true`、`managementRecovery=true` | 各同名进程恢复类；仍需按源码分次运行 prepare / verify，保留原门控，不由 `system` 代替。 |
| `processPhase=write/read` | `FinancialDraftFlowTest` 真实进程草稿恢复的写入/重启后读取阶段。 |
| `systemHandoff=true` | `SystemHandoffTest` 外部编排的闹钟、Worker、通知点击、重启/时区专项，门控保持原样。 |
| `targetCase=recurring/deadline/repayment/budget/funds`、`targetZone` | 系统交接通知目标（默认 recurring）与时区切换目标（默认 UTC）。 |
| `realKeyboard=true` | DraftEditing / Setup 的真实键盘专项。 |
| `realIme=true`、`phaseBKeyboard=true` | 相应金融/转账等流程、活动/商户流程的真实 IME 分支；需实际键盘状态。 |
| `systemMode` | SystemWindowTheme 的外部系统模式标签（默认 unspecified），不自动修改设备配置。 |
| `notificationPermission=true` | ReminderSettings 的真实通知权限对话框专项，先在外部撤销权限。 |
| `reminderStates=granted/denied` | ReminderSettings 的真实权限状态与持久化计划专项，按参数准备权限。 |
| `csvVisual=true` | CSV 导入测试的展示分支；写截图仍须同时给 `visual=true`。 |
| `estimatedLayout=true`、`largeLayout=true` | BudgetRecurrence 的估算金额布局及大字体布局分支。 |
| `modeCase` | FinancialRuleModes 的单业务模式筛选：SINGLE / INSTALLMENT / REVOLVING / CREDIT_LINE / EQUAL_PRINCIPAL / ANNUITY / INTEREST_ONLY / MANUAL / SPECIAL；未给时保留九模式。 |
| `loan=true`、`tiers=true` | 金融池相关流程的贷款账户分支、池付款档位分支。 |
| `splitFees=true`、`unknown=true` | FinancialRetroactive 的拆分费用分支、FinancialProductionOverview 的未知账单分支。 |
| `refund=true`、`merged=true`、`poolSource=true`、`unallocated=true`、`retained=true`、`waive=true`、`futureFee=true` | FinancialReconciliation 的退款、合并、池来源、未分配、保留、豁免和未来费用场景。 |
| `resolve=true`、`cancelParentFee=true`、`resume=true` | FinancialReconciliationChain 的补齐、取消上游费用和恢复草稿分支。 |

既有专项参数仍可单独触发对应专项，新增 `system` 不取代它们；视觉类需要额外给 `visual=true`。纯快照计算现位于 JVM 的 `AnalysisProjectionCalculationTest`、`ExpectedIncomeProjectionTest`、`ImportantItemsTest`，Room 关联、偏好/跨账本约束、到账事务和预览不落库仍由仪器测试负责。

数据库迁移只登记在 `data/db/YohakuDatabase.kt` 的 `ALL_MIGRATIONS`，正式打开（`openYohakuDatabase`）和测试都引用它，不再各自列出。升级测试先写入数据，再调用 `restoreFixtureDatabase(db,N)`：它按 `androidTest/assets/room-vN-schema.json` 把所有表还原成 vN 结构，并设置版本号。要以某个新版本作为升级起点时，把 `app/schemas/.../N.json` 复制成对应的 asset。

仪器测试需要实际兼容设备。真实权限/GPS、IME、跨进程、闹钟/重启、网络服务不能无前置地整类混跑，默认跳过不算通过。专项方法/参数见各测试源码和[阶段E记录](verification/phase-e-status.md)；历史进程/IME依据见A/B/C记录。独立`media-verifier`模块仅提供系统DocumentsProvider和跨UID只读验证，需单独安装，绝不打入产品APK，完成后卸载`com.yohaku.verification`。

专用模拟器`emulator-5580`：标准1170×2532/density480/fontScale1（390dp），窄屏960×2078/density480/fontScale1.3（320dp）。测试后恢复原尺寸/字体/网络/权限等配置。字体许可及数值字形适配见[字体迁移记录](verification/font-migration.md)。
