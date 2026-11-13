# 余量 Android · 阶段E 0.5.0

原生 Kotlin / Compose 本地记账应用。阶段E P21–P25的实现与本轮可执行验收完成，交付0.5.0/code5。用户明确选择本轮交付APK，用户实机检查保持未验。准确状态见[阶段E记录](verification/phase-e-status.md)与[UI01–UI27验收](../docs/phase-e-acceptance.md)。历史范围见[开发计划](../docs/plans/development-plan.md)、[阶段A](verification/phase-a-status.md)、[阶段B](verification/phase-b-status.md)、[阶段C](verification/phase-c-status.md)、[阶段D](verification/phase-d-status.md)。

## 安装包

- [正式APK 0.5.0](artifacts/yohaku-release-0.5.0.apk) · [SHA-256](artifacts/yohaku-release-0.5.0.apk.sha256)：包名`com.yohaku.expensetracker`，固定正式签名。
- [开发APK 0.5.0](artifacts/yohaku-dev-0.5.0.apk) · [SHA-256](artifacts/yohaku-dev-0.5.0.apk.sha256)：包名`com.yohaku.expensetracker.dev`，沿用阶段A–D开发证书。
- Android 10+（minSdk29）、target/compile SDK37、versionCode5、Room v39（支出与模板 budgetExcluded，迁移38→39；此前月计划/月评、方案/效果队列、撤销关联及任务 purpose 保留）。三主题、中日文按账本保存。
- 开发包可覆盖原开发版本。正式包独立安装，与开发包并存，不自动转移开发包资料。覆盖升级不要先卸载；卸载或清除应用存储会删除账本与内部附件。提供手动本地完整备份/恢复，系统云备份仍关闭。
- 当前仅专用API36模拟器验证，用户实机未连接。精简实机检查、密钥位置和完整安装升级说明见[发行说明](RELEASE.md)。

```sh
adb devices
adb -s <serial> install -r artifacts/yohaku-dev-0.5.0.apk
adb -s <serial> install -r artifacts/yohaku-release-0.5.0.apk
```

历史交付：[D 0.4.0](artifacts/yohaku-phase-d-0.4.0.apk)、[C 0.3.0](artifacts/yohaku-phase-c-0.3.0.apk)、[B 0.2.0](artifacts/yohaku-phase-b-0.2.0.apk)、[A 0.1.0](artifacts/yohaku-phase-a-0.1.0.apk)。这些历史包不包含阶段E的真实通知/定位和完整资料管理。

## 功能范围

设置页依次为「外观、语言与触感」「账本与分类」「提醒与汇率」「小票、流水与云端」「数据」「其他」。外观主题行显示当前主题名，进入 `appearance` 二级页选择主题（缩略预览与选中标记保留）；语言切换与“触感反馈”开关位于同组。触感默认开启、所有账本共用并跟随系统触摸振动设置；三主题具有独立波形，详见[触感设计](../docs/haptics.md)。「小票、流水与云端」依次进入 `receipts`、`statements`、`cloud`；「数据」含「照片保存方式」、「备份与恢复」（`backup`）与「从 CSV 导入」（`csv-import`）；「其他」只含回收站。「账本与分类」还提供「商户与地点」（`merchants`）入口，分析页原入口保留。

账本切换只保留顶部当前账本按钮、设置→独立账本管理及其下级管理页。收支、转账、退款表单不再显示账本/主题工具行；顶部账本按钮在记账中打开切换弹层，其余正常页面进入账本页，金融草稿保护继续沿用。主题只在设置→外观主题切换；离开有手动输入的记账时可保留或放弃草稿；未选分类的非转账草稿，或相对打开基线仅改分类/自动定位商户的草稿，直接放弃并离开。恢复的草稿没有基线，已选分类或转账时仍提示。顶部不再提供语言按钮。首次设置的顶部设置按钮打开同时含主题和语言的设置弹层，切换保留未保存设置输入。

并列短操作使用 `YActionRow` 自适应排布：杂志/手账/小物分别沿用 8/10/12dp 间距，按钮保持各主题原有字形、边框和阴影；按自然宽度排列，空间不足时整项换行，主按钮占满所在行剩余宽度；确认弹层中并列的二选一（放弃 / 继续编辑等）都用本主题的次要按钮，不与无框链接混排。长文案或不同层级操作仍纵排。需要与次要按钮同行同高时，主按钮用 `compact=true`：保留主按钮配色，字号与内边距取次要按钮尺寸。记录详情的「编辑记录」（`record-edit-button`）即以此与「记录退款」同行，位于退款历史之后；无可退金额或非支出记录时该行只有编辑按钮。

- 空白设置、独立账本、两级支出/收入分类（可选分类图标）、现金/银行/交通IC/信用卡/贷款账户；期初债务不重复计收入/消费。
- 全部流水的列表/日历切换：日历按月显示每日净支出（与月度消费同口径）、日均预算进度，点选日期查看当天流水并可直接记在这一天。
- 全部流水列表搜索商户、备注、金额及小票商品名（不含商品备注），忽略大小写和全半角；商品名命中时行内列出命中的商品（杂志 RECEIPT 标签、手账小票图标、小物胶囊）。日历视图不受搜索影响。
- 收支、历史补录、原ID编辑/修订、按账本草稿及进程恢复。原币/账户币/统计币、预计与实际、部分/分次退款、同币转账/异币换汇、两端独立日期/在途及明确费用。
- 自然月/分类预算、周期待办（规则内固定商户/备注/付款方式，到期预览后一键确认）、确认或关联已有记录、记账模板、活动预算/资金预留、多人互请；分析模块排序、分类/商户/活动筛选与下钻。
- 用户配置的一次付/分期/リボ/固定贷款/循环贷款规则，生效版本、计息/费用/舍入/时区及逐期计划；缺项不假设零费用，特殊算法使用平台已知手动金额，无机构预设。
- 信用卡消费付款方式、自动循环还款/循环还款计划、总/子/共享额度；实际借还、部分/提前/跨币种付款、待平台清偿/未知拆分。纯试算不修改正式账，确认办理保留原账单和历史。
- 预计收入及实际到账/关联、按实际资金账户的逐日预测、基础/含预计收入两情景、预算与资金缺口分列、付款日历及重要事项。其他账户资金和信用额度不自动抵消扣款账户缺口。
- Frankfurter v2参考汇率、来源日期/持久缓存、自动开关/手动刷新/离线手填；请求仅含币种/日期，不上传账目，参考金额不改写实际账。
- 真实附件/小票多选、内部副本、图片预览、音频播放、外部只读打开；每个文件严格小于100 MB。
- 云端返回的流水JSON整体校验/预览、一对一核对、独立人工差异、按类型正式补记；小票多图ZIP/已保存字节校验、云端商品结果回填/替换/修订与商品编辑；银行流水核对与小票商品回填不自动写账，无OCR/银行连接；这两种云端转换不提供本地导出或手动选文件导入。
- 首页核对提示：银行卡按自然月、信用卡按结账日，期间结束两天后仍未被已导入流水完整覆盖的期间（从账户期初日起，含首个半期与多期）在今天页合并显示一行；可带账户与期间直接交给云端转换，或标记本期不核对。
- 五类重要事项的真实通知、非精确AlarmManager调度、持久去重、修改撤销、重启/升级/时区变化重建；金额优先展示，点击进入所属账本和目标月份。用户保存开启时才申请通知权限，拒绝仍能记账。后台时机受Android及厂商省电策略影响。
- 仅今天的新建支出主动前台单次定位、保存地点匹配、默认100米、精度/唯一候选控制自动填入；多个候选手选，手改/清空/旧记录优先。离开前台取消，不申请后台位置权限、不记录轨迹；只有今天新记、有定位且非空名称在解析后仍无正式关联的支出会留下当时的位置，保留或明确放弃位置后才删除；未处理的已关联位置仍可在商户资料中整理。
- 云端分析交接：导入网页生成的连接配置（Keystore加密），把所选小票图片ZIP或银行资料交给私人分析台；中断后按同一份内部文件整份重传（无断点续传）、领取结果核验大小/SHA后走原有预览确认，回执独立重试。
- 整本归档/回收/恢复/永久清理；账户停用/启用/无引用删除；分类（含二级）归档/取消归档/改父级/明确迁移；活动删除影响与历史保留；记录关联组回收/恢复/永久清空及文件最后引用清理。

提供用户手动的本地完整备份/恢复（整份替换、不加密、不自动备份）；没有账户登录、云同步或应用锁；云端交接由用户逐次发起；办理任务会自动附带账本结构，具体记录按用户选择范围附带，并在提交前显示摘要。未知金额保持未知，未确认清偿不假结清。产品包不含测试种子、评审入口或测试验证器。

## 记账体验（2026-10-31 源码更新）

本节对应当前源码，文首既有发布 APK 未更新。实现位置、测试方法及结果见[本轮交付记录](verification/entry-experience-2026-10-31.md)。

- 新建分类页与转账页共用支出/收入/转账三等分标签；编辑、周期确认和退款不显示类型切换。切换保留日期、备注、附件会话及普通附件/小票，保留原返回目标。
- 付款账户按同账本同类型最后一次保存记忆（修订时间优先于交易日期）；转账记住上次两端，账户页显式指定优先。停用或不适用的账户回退到可用账户。信用卡付款方式按卡跨支出/转账记忆：分期带回期数，指定月只带回方式，月份与费用重新填写；编辑沿用已保存方式。
- 现金/银行/交通 IC/信用卡可转出，只有资金账户可转入，贷款不可转账。信用卡充值增加该卡欠款，转出总额（含已包含的费用）进入付款方式、账单预测、额度和リボ余额池；本金不计消费，明确手续费仍计支出。账单规则不足时沿用既有待补规则，不自动假设一次付。
- 商户详情支持确认后永久删除（无撤销/回收站）：删除保存地点和记录关联，模板、周期规则及草稿清空商户 ID，保留商户文字。删除保留记录文字并重算旧名称组；若剩余唯一同名商户，则重新关联并使用其正式显示名称。修订快照不改；云端撤销保留商户历史并标记已撤销，拒绝新增商户归属不一致。云端禁止 `merchant.delete`，可用 `merchant.setActive false` 停用。
- 支出商户输入提供最多5个启用商户的联想及可搜索一览，忽略大小写和全半角，名称完整匹配且同名商户唯一时关联商户（含停用，并提示将关联到已停用商户；保存事务再次解析）；可清除或修改。候选出现或键盘弹出时自动滚到可见位置。非今天记录不请求当前位置、不显示位置候选，日期改为非今天时清除定位自动带入的商户，保留手填/手选。
- 保存前使用同一份变更预览检查余额：资金账户余额减少且变为负数时先确认，确认后再进入原保存/影响审核流程；债务账户不提示。详情回收后替换当前路由，返回不会重开已回收记录。
- 顶栏账本名按全屏宽度居中，并按两侧实测宽度限宽；首页最近支出显示5笔。
- 收支表单的保存区固定为弹层底栏（`entry-save-dock`，经 `LocalSheetFooter` 放在滚动区之外），表单在它上方滚动，可视区止于底栏；软键盘弹出期间底栏让出空间，保存区回到表单末尾。按钮文字附带本次账户币金额（如「保存记录  ¥680」，关联已有支出时不附带）。编辑时「取消」（`entry-cancel-edit`）为与保存同高的次要按钮，与「保存修改」同行。杂志为纸色实底加发丝线，手账与小物为纸色渐隐。转账、退款表单保持原样。仪器测试用 `entryAction(tag)`（`EntrySaveDock.kt`）定位保存/取消：先等软键盘状态稳定，在滚动区内（键盘弹出时）才滚动过去。
- 账户页资金与欠款列表一行两个账户：名称最多两行，余额单行自动缩小并贴卡片底部，同行等高，奇数时最后一张半宽；杂志为竖横发丝线分隔的目录表，手账为纸片，小物为实体卡片。屏宽 ≤360dp 或字号 >1.1 倍时仍一行一个。

必要验证：`EntryDefaultsTest`（保存顺序、付款方式、草稿基线、负余额、名称规范化）；`TransferTransactionsTest`（信用卡充值 posting/预测/额度/对账/现金时间线及净余额预览）；`EntryExperienceFlowTest`（tab/附件/默认账户、负余额两种保存路径、商户联想与一览）；更新 `DraftEditingFlowTest`、`LocationEntryFlowTest`、`EntryLifecycleFlowTest`、`ThemeControlsTest`。本轮仅运行 JVM 测试和两个 APK 构建，不启动模拟器或运行 connected 测试；相关 UI 类在设备上使用 `looks=all` + `visual=true` 并查看截图，实际视觉结果仍待验。

## 桌面小组件与模板快捷方式（2026-11-07）

本节对应当前源码，文首既有发布 APK 不包含本功能。视觉依据为 [widgets.html](../design/widgets.html)：小组件默认 4×2、最小 2×2，宽度达到 250dp 显示前四个模板。Android 12+ 使用两种尺寸映射的 RemoteViews，Android 10–11 根据尺寸选布局；支持添加时选账本和 Android 12+ 长按重新配置。配置页用当前账本的 Compose 主题；小组件按所绑定账本的主题与中日语言显示。杂志用报头、分隔线和编号目录，手账用横线纸、红页边、装订孔、胶带、算式、波浪线和清单，小物用钱袋、提带、分半奶油卡及带分类图标的模板纸条。

月份取设备当地今天；`WidgetBudget` 纯函数接收 `BudgetProjection` 和日期。预算内还能花为 `remainingMinor`，自由可花为 `availableMinor`（扣除本月待付预留），与首页相同。剩余天数包含今天，日均为非负自由可花除以剩余天数、按币种最小单位向下取整，金额仍用 `Money.display()`。未设预算显示本月已花、未设预算和剩余天数；超支显示正数超出额、月预算和剩余天数；预留金额未知时自由可花/日均显示「—」，仅自由可花为负时保留负数而日均为零。

余额点击切到账本首页；宽版模板按 sortOrder 取前四个（包含失效模板），无模板时进入模板管理。失效格置灰并进入编辑；已删除模板打开管理并显示主题提示。有效模板复用 `startTemplate`、既有草稿确认和金额聚焦，不新增保存流程。长按 app 图标的动态快捷方式取当前账本前四个**可用**模板；标签为模板名、长标签附固定金额。图标用共享字体链或现有分类路径绘制 adaptive bitmap，三主题独立设计；没有 pinned 快捷方式或桌面记账弹窗。快捷方式内容未变时不重复发布；系统限流会记录错误并安排重试，应用回到前台也重新检查。

刷新集中在 `YohakuApplication.desktop`：Room `InvalidationTracker` 监听已提交变化，约一秒合并刷新所有小组件及快捷方式；activeBook 偏好另行监听。观察账本、事件/退款确认/手续费、预算、周期规则/实例、活动及关联、预留及使用、模板、账户、分类、商户、账本功能和记录保存顺序相关表；不在记账、云端执行/撤销、CSV 导入等写入点插入调用。`onUpdate`/调整尺寸通过 `goAsync()` 在 IO 线程读数据库；每天当地零点后 5 秒安排非精确 AlarmManager 刷新，系统可延迟投递，时间/时区、日期、重启、升级和语言变化也触发刷新。系统周期更新为 30 分钟。绑定按 widgetId 存在 noBackup 下独立 DataStore，删除小组件时清理；账本备份恢复不替换启动器绑定，`:restore` 前停止观察，应用重启先恢复数据库再启动桌面刷新。绑定账本不存在时保留最后主题/语言显示空状态，点击重新配置。

`ThemeTypefaces` 抽出原 `ThemeFonts` 的原生 Typeface 构建与缓存，资源顺序、400 字重和 variable font 参数完全不变，Compose 仍包装同一组原生字体。journal 为 journal_math → hand → hand_cn → hand_full → hand_cn_full → journal_check → journal_star，objects 为 round → round_cn → round_full → round_cn_full，magazine 正文为 editorial → editorial_full，数字前置 editorial_number。运行时每个主题文字字段按系统 fontScale、实际 dp×density 独立绘制到 ImageView，设置完整 contentDescription；名称超长省略但读屏保留全文，金额必要时按列宽缩小。位图高度按 `ascent/descent`（hhea 行高）计算，不用 `top/bottom`：后者取回退链首个字体的包围盒，journal_math 只有 0.22–0.28em 会把手账文字裁成细线，editorial/round_cn 又过大。`DesktopArtwork` 的像素密度字段命名为 `pxPerDp`，因为 `TextPaint.apply{}` 内的 `density` 会解析成 `TextPaint.density`（默认 1）。高于设计稿的尺寸（如 S24 Ultra 4×2 约 230dp、Pixel 4×3）把多出的高度平分给间隔与模板行。只保留大小两种布局并裁去文字尾部透明像素，不把整个小组件画成一张位图。装饰为 XML shape/vector 与小尺寸平铺位图；静态选择器预览用 magazine，Android 12+ 为 previewLayout、旧版为 previewImage。

验证入口：JVM `WidgetBudgetTest` 五项覆盖正常（11 月 7 日含当天共 24 天）、未设预算、超支、未知预留、最小单位取整及负自由可花归零。仪器 `DesktopWidgetVisualTest` 默认只运行一次模板描述、失效提示与余额/模板点击绑定断言，使用独立内存仓库，不写真实账本；截图方法须显式开启 `visual=true`，用 `testLooks(this)` 选择主题/语言，`looks=all` 覆盖六组。固定日期 2026-11-07，直接展开生产 RemoteViews，宽版 356×180dp、小版 172×180dp，背景为桌面近似色；Android 12+ 直接使用渲染器单尺寸布局，避免 `apply` 自动选择映射分支。每组输出 normal 宽/小版、over、nobudget、unknown、edge（失效模板及长名称）、empty、unavailable，共八张；每主题另输出一张四个快捷方式图标的圆形遮罩拼图。

`connectedDebugAndroidTest` 结束后会卸载 app，截图和种子账本随之消失，因此先串行构建并安装两个 APK，再用 `am instrument` 运行。截图在 `getExternalFilesDir(null)/desktop-widget/`（开发包通常为 `/sdcard/Android/data/com.yohaku.expensetracker.dev/files/desktop-widget/`），文件名为 `{theme}-{language}-{state}.png` 和 `{theme}-shortcuts.png`：

```sh
adb shell am instrument -w -e class com.yohaku.expensetracker.DesktopWidgetVisualTest -e looks all -e visual true \
  com.yohaku.expensetracker.dev.test/androidx.test.runner.AndroidJUnitRunner
```

真实启动器种子仅在 `desktopSeed=true` 时运行：向 app 真实数据库新增三本中文 JPY 账本（每主题一本），每本月预算 180,000、已花 90,360、待付 40,000 和四个模板，activeBook 选择 objects。种子日期同截图夹具，为 **2026 年 11 月**；手动添加小组件检查正常状态时，设备当地月份也应设为 2026-11（11 月 7 日的剩余天数和日均与截图一致）。此方法会保留新账本供后续手动验证，重复运行会再次新增三本，不清空已有账本。

```sh
adb shell am instrument -w -e class com.yohaku.expensetracker.DesktopWidgetVisualTest#seedRealDesktopBooks -e desktopSeed true \
  com.yohaku.expensetracker.dev.test/androidx.test.runner.AndroidJUnitRunner
```

2026-11-07 验收：JVM 全量 156 项（1 项既有跳过）通过；模拟器以 S24 Ultra 尺寸（1080×2340/density 450）运行 `DesktopWidgetVisualTest`（`looks=all visual=true`）3 项通过，三主题×中日文 48 张状态截图与 3 张快捷方式图标已查看。Pixel 启动器实测：添加时配置页（复用账本页 `BookCard`）选账本、默认 4×2、4×3 拉伸、模板点击切换账本并预填聚焦、已有草稿时走草稿确认、保存后约一秒刷新余额/自由可花/日均、长按图标显示当前账本四个主题快捷方式并可进入记账。One UI 实机外观、跨日零点刷新和备份恢复后刷新尚未在设备上验证。

本轮只串行运行 `:app:testDebugUnitTest`、`:app:assembleDebug` 和 `:app:assembleDebugAndroidTest`（均加 `--max-workers=2`），不启动模拟器或执行仪器测试。截图审阅、真实桌面的配置/重配/删除、跨日与备份恢复等设备验收由验收方执行并补充结论。

云端办理不新增命令或 schema，能力样例 JSON 不变：现有命令仍只修改业务事实，桌面作为本机派生展示由提交后的数据库通知刷新。已检查本 README「云端办理」、`docs/implementation-spec.md` 与 `docs/android-cloud-handoff-package`；协议、能力说明及样例不需调整。

## 支出不计入预算（2026-11-06）

支出表单展开「备注和其他」后可选「不计入预算 / 予算に含めない」，说明为「仍计入分析与账户余额 / 分析と口座残高には含めます」。与历史补录共用三主题 `EntryOptionToggle`，相邻开关只画一条分隔线；历史补录标签和 description testTag 保留，新开关为 `toggle-budget-excluded`。模板管理支持同款开关；存为模板、套用模板、草稿恢复、编辑、详情、修改记录与云端导出/撤销均保存该值。记账切类型重建草稿，模板切类型显式清零，仓库非支出强制 false。

`excludedFromMonthly` 统一合并逐笔标记和独立活动：从月/分类预算已花、剩余、可用、进度、待确认数、保存预览、月评预算、预算节奏和提醒排除，归入 independent，退款/手续费随原消费。分析、余额、日历格子金额/月合计/日均、活动自身预算保留实际口径；日历颜色/超支/进度/剩余预算用排除后的每日金额，无预算时相对深浅和「最多」仍按实际支出。周期规则/实例及固定预留不变。完整备份直接保存数据库，无字段白名单；旧备份经 38→39 迁移默认 false。

验证入口：JVM `BudgetExclusionTest`；仪器 `EntryExperienceFlowTest#expenseBudgetExclusionSavesAndClearsOnIncomeAcrossLooks`、`DatabaseUpgradeTest#version38AddsBudgetExclusionDefaultFalseToEventsAndTemplates`、`CommandLedgerExecutionTest#budgetExclusionEditsTemplatesExportsAndUndo`，及 `BackupRoundTripTest` 的完整快照往返。UI 使用 `looks=all visual=true`，查看 `files/verification/entry-experience/*-budget-excluded.png` 和 `*-budget-income.png`，日历回归用 `LedgerCalendarTest#calendarShowsDailyNetSpendingAndOpensTheChosenDay`，截图在 external-files `ledger-calendar/*-budget-excluded-month.png`。2026-11-06 JVM 全量通过；模拟器上 `DatabaseUpgradeTest`、`CommandLedgerExecutionTest`、`BackupRoundTripTest`、`BudgetRecurrenceTest`、`BudgetRecurrenceFlowTest`、`AnalysisProjectionTest`、`ThemeControlsTest`（53 项）通过，上述两项 UI 方法以 S24 Ultra 尺寸 `looks=all visual=true` 通过，三主题×中日文截图已查看。

## 照片与附件导入（2026-10-31 源码更新）

表单的两个添加入口每次弹出应用选择窗口（GET_CONTENT + OPENABLE + 多选 + chooser），立即复制临时 URI。小票另有“拍照”：`LocalReceiptCamera` 在 `MainActivity` 级打开 `ScanFlow` 应用内扫描相机，权限申请、拒绝与无相机状态由相机页处理，一次一张。默认拍照后进入确认/调整页，输出交给现有小票导入再清理扫描临时目录。待处理账本、草稿媒体会话或记录 ID、已有图片路径及表单保存选项存于 saved state；拍照/确认期间进程被回收，恢复后仍入库原目标。应用状态在回到前台重新订阅时保留上次结果（仅首次读取和手动重试显示启动页），记账弹层与滚动位置保持不变。云端分析台选择器不变。

相机页“自动拍摄”默认关，“自动处理”默认开，偏好跨账本共享并进入完整备份。关闭自动处理后原图直出、跳过确认页，仍按“原样保存（不压缩）”开关优化或保留字节；处理后的图片也按同一开关作为新照片入库。表单小票缩略图下的“处理这张”与排序按钮分行，始终打开已有图片确认/调整（即使自动处理关闭），确认后在同一媒体会话的原位置替换为新文件。已保存图片字节不改写，编辑保存按既有规则产生新小票修订；取消或入库失败保留旧图，旧文件沿用暂存/引用清理。扫描/处理与导入期间禁用其他媒体变更。云端 `preferences.scanAutoCapture` / `preferences.scanAutoProcess` 使用必填 `enabled`，支持原值比较、原子效果凭据、撤销及恢复；`entry.prepareDraft(media=RECEIPT_CAMERA)` 续办仍在表单点“拍照”打开此流程。扫描相关测试与截图命令见下方 [小票扫描验证](#小票扫描验证)。

应用内扫描已完成阶段 1–6，待阶段 7 用户真机验收。CameraX 提供实时轮廓、补光和快门；自动拍摄要求轮廓可靠、画面及手机稳定约 0.8 秒，进度沿取景框边缘显示。确认页可使用、重拍、调整轮廓（左右各五点、上下端线、拖动放大镜）、重新处理与恢复自动结果；未检测到时直接进入手动调整，真实错误可重试。仅做几何处理，不做 OCR/图像增强；相册导入不自动扫描。原图和结果暂存于 `cache/receipt-capture/<uuid>/`，确认移交输出后清理原会话，取消/重拍清理会话，导入结束清理输出；启动清理超过 24 小时的残留，近期确认/调整状态可恢复。

三主题采用用户选定的设计 A：杂志编辑校样框、手账胶带照片与朱红笔迹、小物圆角相机窗口，确认/调整、权限与错误状态均保持本主题设计。定稿后自动拍摄进度线加粗，快门旁状态为「拍后自动裁剪展平 / 拍后原图直出」（日文「撮影後に自動補正 / 撮影後そのまま」）；开关及云端命令仍称自动拍摄、自动处理。接口、缓存与坐标细节见 [扫描模块说明](tools/receipt-scanner/README.md)。

导入时可按全局“照片保存方式”设置优化（默认节省空间），也可在本次表单切换原样保存；在记录详情页为没有小票的记录补加小票时直接使用全局设置。节省空间模式下，所有 MIME 为 `image/*` 的图片都重新编码，不按截图、PNG、GIF 等格式豁免：小票短边 ≤ 2000px、JPEG 质量90；图片附件长边 ≤ 2560px、JPEG 质量85；等比缩小且不放大，EXIF 方向烘焙到像素，输出 sRGB SDR，去掉 gainmap，透明区域合成白底，不保留 EXIF/XMP、GPS、机型或动态视频。优化后 MIME 为 `image/jpeg`、扩展名 `.jpg`；原样保存及非图片保持字节不变。源文件完整读入后若格式不支持或损坏而无法解码，原样保存并提示数量；读取、写入或数据库失败不走回退。

上传打包与备份使用已保存的图片字节，打包时不再转码；ZIP 中 `sha256` / `size_bytes` 是实际入包字节的校验值与大小，MIME 也对应保存后的文件。

设置→数据→照片保存方式选择默认值；表单“原样保存（不压缩）”开关位于默认折叠的“备注和其他”（与备注、历史补录同区），按附件会话保留，对之后的批次生效。记录详情页在没有小票图片时可直接选图或拍照补小票，按全局设置保存，一批生成一版小票、不新增记录修订。偏好全局保存并进入完整备份，旧备份缺字段时默认节省空间。每张独立提交、失败继续，附件区显示进度，结束汇总成功/无法优化/失败及首个失败原因。缩略图长边512px，预览长边4096px，均精确等比缩放。没有历史图片迁移、去重或数据库变更。

验证：`PhotoStorageTest` 覆盖尺寸/文件名/旧备份默认；`MediaStorageTest` 覆盖优化、EXIF/GPS、透明白底、原样字节、解码回退及读取/取消清理；`MediaFlowTest.chooserAlwaysWrapsGetContentAndParsesMultipleOrSingleUris` 检查 Intent，`photoDefaultToggleProgressAndBatchSummaryAcrossThemes` 检查设置默认、开关、进度及部分失败汇总；`BackupRoundTripTest` 增加照片偏好往返。仪器测试仅编译，尚未运行；UI 方法需在设备上用 `looks=all` + `visual=true` 运行并查看 `files/verification/media-import/` 截图。本轮不启动模拟器、不运行 connected 测试。2026-11-05 补充 `MediaFlowTest.recordDetailAddsFirstReceiptsWithGlobalPhotoSetting`（详情页选图/拍照、全局两种保存方式、一版小票、入口消失及重复补加被拒），与 `MediaStorageTest`、`MediaFlowTest` 其余方法一起以 `looks=all visual=true` 在模拟器运行通过，截图 `media-import/*-record-upload.png` 已查看。

## 活动分类预算按需添加（2026-11-07）

「活动与预算」的新建/编辑共用表单不再铺开全部分类。总预算下的「活动内分类预算」默认只显示说明与添加按钮；点「＋ 添加分类预算」原地展开选择面板，一级下挂二级，已添加项禁用。选中后收起面板、聚焦金额并滚到可见；行按一级及其二级排序，× 移除，清空金额保留行但保存时不写入。编辑已有活动仅带入已设额度，保留历史停用分类。预览影响→确认保存流程不变。

合计按一级有金额就计一级，否则计其二级之和（0 也是已设置），父子同时添加显示包含关系。总预算为空时说明各分类额度独立生效；超出总预算用暖色提示，仍可保存。详情页复用 `BudgetLimitList` / `BudgetLimitRow`：点分类行进入对应金额框，添加入口直接展开选择面板；关闭活动功能后行保留为只读、不显示添加入口。杂志用编号/细线索引与合计算式，手账用勾选便签/胶带/波浪线，小物用托盘分组卡/标签/锯齿小票。

稳定 testTag：`activity-category-section`、`activity-category-add`、`activity-category-picker`、`activity-category-picker-close`、`activity-category-option-<categoryId>`、`activity-category-row-<categoryId>`、`activity-category-amount-<categoryId>`、`activity-category-remove-<categoryId>`、`activity-category-summary`、`activity-category-detail-add`；详情行沿用 `budget-category-<categoryId>`，列表沿用 `budget-category-list`。

验证：JVM `ActivityCategoryBudgetsTest.parentLimitIncludesChildrenOtherwiseChildrenAddUp`；仪器 `ActivityFlowTest#categoryBudgetsAddParentAndChildSaveOverTotalAndRemove` 覆盖父子添加、超额保存、清空金额不写入、移除删除、详情聚焦/展开与只读状态。沿用 `testLooks` / `shot`，传 `looks=all` + `visual=true` 输出 `files/phase-b-p11/<theme>-<language>-category-{empty,picker,editor,detail}.png`（三主题 × 中日文）。

2026-11-07 验收：JVM 全量 157 项通过（1 项本地 CSV 样本测试既有跳过）；模拟器以 S24 Ultra 尺寸（1080×2340/density 450）运行新方法 `looks=all visual=true` 通过，三主题×中日文 24 张截图已查看；`ActivityFlowTest`（3 项）、`ActivityManagementStorageTest` 与 `ActivityTreatTest`（共 8 项）默认参数回归通过。

设备上的验证入口（先安装本次 debug 应用与 androidTest APK）：

```sh
adb shell am instrument -w -e class 'com.yohaku.expensetracker.ActivityFlowTest#categoryBudgetsAddParentAndChildSaveOverTotalAndRemove' -e looks all -e visual true com.yohaku.expensetracker.dev.test/androidx.test.runner.AndroidJUnitRunner
```

截图在 `/sdcard/Android/data/com.yohaku.expensetracker.dev/files/phase-b-p11/`。

云端办理检查：`activity.save` 的 `categoryBudgets` 仍是完整替换，继续使用 `ActivityInput`→`previewActivity`→`commitActivity`；`activity.end` 保留原分类额度，命令效果视图继续显示 `activityBudget` 金额变化。未修改数据库、仓库层、命令协议或月预算页。

## 周期账单一键确认与记账模板（2026-11-01 源码更新）

- 周期规则可固定商户、备注和信用卡付款方式（一次付/ボーナス/分期+期数/リボ/按卡规则；「指定月一次付」需逐笔填付款月与费用，不能放进规则或模板）。不设付款方式时沿用该卡最近一次的选择。换账户会清掉不适用的付款方式。
- 周期页「确认付款」打开「确认周期账单」预览：金额大字、安排日期、状态，下面是账户/付款方式/分类/商户/备注和余额、预算的变化。「确认记账」直接写账，日期用安排日期（不是今天），完成该待办、解除预留并留在周期页；负余额先确认；资料在预览后变化会重算并提示。金额未定或预计时可在预览里改金额；外币缺本位币金额等情况提示「修改后再记」，进入完整表单（同样预填安排日期与规则内容）。一键确认不动现有记账草稿。三主题分别是杂志标题数字、手账红印章和下划线、小物票根。
- 记账模板（支出/收入，不含转账）：分类、账户、信用卡付款方式、商户、备注、活动及可选固定金额。首页「常用的一笔」点模板 → 打开预填表单、焦点在金额，填金额保存；不改动离开不提示未保存。模板商户算手填，不被当前位置覆盖。新建收支表单底部「把这笔存为模板」；设置→记账模板管理新建/编辑/排序/删除。三主题模板入口分别是编号索引、纸胶带标签、彩色小物卡片。
- 引用处理：分类合并时模板跟着迁移；账户停用、分类归档、活动删除或活动模式关闭时，模板保留但标为不可用，首页不显示，管理页说明原因；被模板引用的账户/分类不能直接删除（删除确认页显示引用数）；删除活动会清空模板的活动归属。

Room 33→34：`recurrences` 增加 `merchant`/`note`（NOT NULL DEFAULT ''）、`merchantId`/`cardPaymentChoice`/`cardPaymentPeriods`（可空）；新表 `entry_templates`。完整备份仍是数据库归档，旧备份按同一迁移升级。

必要验证：`EntryPresetsTest`（预览草稿、模板草稿/校验/失效、分类合并迁移）；`BudgetRecurrenceFlowTest.oneTapConfirmationPreservesDraftAndTemplatesOpenAtAmount`（一键确认、资料变化重算、草稿保留、模板聚焦与记账，截图 `phase-b-p10/*-recurring-one-tap`、`*-home-templates`、`*-template-*`）；旧确认流程的测试改走「修改后再记」；`DatabaseUpgradeTest` 覆盖33→34。

## 小票扫描依赖与运行

当前源码的扫描阶段 1–6 已完成，待阶段 7 用户真机验收；文首 `artifacts/` 链接是既有交付包，本次构建不覆盖它们。新构建输出为 `app/build/outputs/apk/release/app-release.apk`。正式包仅 arm64-v8a，debug 保留全部架构供 x86_64 模拟器测试。

- debug：`onnxruntime-android:1.29.0`；release：`onnxruntime-android-qnn:1.29.0` + QNN runtime 2.42.0，保留全部 QNN 运行库。共用 OpenCV 5.0.0、CameraX 1.6.2。三份浮点 ONNX（约 66.4 MiB）直接随 assets 分发，不在线下载，ONNX 不压缩；JNI 使用 `useLegacyPackaging=true`，Manifest 声明可选 `libcdsprpc.so` / `libOpenCL.so`。相机声明 `CAMERA` 权限，camera/autofocus feature 为 required=false，无相机设备可安装。
- 正式 APK 实测：249,991,090 字节（249.99 MB / 238.41 MiB）（2026-11-10，阶段 6，`:app:assembleRelease`，包含新增许可）；扫描加入前约 89.5 MB。版本仍为 0.5.0/code5，此处为当前源码构建体积。
- 预览 encoder：HTP FP16 → GPU → CPU；拍照 encoder/SyntheticDoc：GPU → CPU；decoder 固定 CPU。HTP 只用于预览，不用于精确输出。CPU 六线程，无 XNNPACK。
- 启动提交 WorkManager 唯一 `receipt-scanner-model-warmup`（KEEP、非 expedited、延迟 15 秒、电量不低、后台优先级）；仅对缺失/失效的 HTP encoder、GPU encoder/SyntheticDoc context 串行预热，不加载 decoder。前台不等待编译，GPU 缓存也未就绪时可用 CPU，预热完成后下一次预览可尝试 HTP。
- 模型校验 SHA256/长度后复制到 `filesDir/receipt-scanner/models/`；context 在 `noBackupFilesDir/receipt-scanner/contexts/`，每模型/EP 独立。键含模型 SHA256、ORT/QNN 版本、`Build.FINGERPRINT`、ABI、EP、HTP 配置、固定提示维度及实现版本；外部二进制与 context 一起管理，最后原子发布 `ready.json`。旧键/残缺缓存删除重建，加载/运行失败记录日志、删除缓存、安排预热并降级。模型与缓存不进入完整备份，两个扫描偏好进入备份。

### 第三方许可与模型来源

沿用字体/Tabler 的处理方式：许可原文随 APK 打包到 `assets/licenses/`，由本文列出索引；当前应用没有独立的许可/致谢页面或运行时许可清单。以下六份文件保留用户提供的原始字节；CameraX/AndroidX 延续既有依赖许可处理，不另加条目。

| 组件与来源 | 随包许可原文 |
|---|---|
| EdgeSAM 3× encoder/decoder，官方发布 ONNX，沿用 ReceiptScanEval 已验证副本 | [NTU S-Lab License 1.0](app/src/main/assets/licenses/EdgeSAM-S-Lab-License.txt)，**仅非商用**；用户已确认本应用不商用 |
| SyntheticDoc 31×45，ReceiptScanEval 的 `original_31x45.ckpt` 经 `eval/syntheticdoc.py` 导出 backward map（opset 17） | [MIT](app/src/main/assets/licenses/SyntheticDoc-MIT.txt) |
| ONNX Runtime 1.29.0 | [MIT](app/src/main/assets/licenses/ONNXRuntime-MIT.txt) |
| OpenCV 5.0.0 | [Apache-2.0](app/src/main/assets/licenses/OpenCV-Apache-2.0.txt) |
| QNN runtime 2.42.0 | [Qualcomm AI Stack License](app/src/main/assets/licenses/QNN-Runtime-LICENSE.txt)（该版本 AAR 的 LICENSE.pdf 转文本）及 [原样 NOTICE](app/src/main/assets/licenses/QNN-Runtime-NOTICE.txt) |

模型下载来源、SHA256 和合成测试数据再生成方法见 [扫描工具 README](tools/receipt-scanner/README.md#模型位置与来源)。许可文件以组件名前缀区分，`assets/licenses/` 与依赖 JAR 的 `META-INF` 资源路径不同；保留既有 Gradle packaging 配置，不为新增许可添加 exclude/pickFirst。阶段 6 的 debug/release 构建均通过，六份许可与提供的原文及 APK 内文件逐字节一致，APK 内无重复条目。

### 小票扫描验证

以下命令在 `android/` 下运行，Gradle 串行，不能同时开多个 Gradle 进程。日常只跑相关 JVM 与默认仪器数据/流程，UI 修改才加 `looks=all`、`visual=true` 并查看截图；完整外观矩阵、系统/性能专项遵循本文原有门控。阶段 6 仅文档/许可收尾，不启动模拟器；本节设备命令供后续复验使用。

| 类（省略 `com.yohaku.expensetracker.`） | 目的 |
|---|---|
| JVM `receipt.scan.ScannerNumericsTest`、`receipt.scan.ScanContourEditorTest`、`receipt.scan.ScanOrientationTest`、`ui.features.scan.ScanCapturePolicyTest`、`ui.features.scan.ScanFrameFailuresTest` | 数值金标准、±45° 纠斜、轮廓/坐标/文件交接、平滑/提示/自动拍摄、异常帧恢复与日志节流 |
| JVM `PhotoStorageTest`、`data.repository.CommandScanPreferencesTest`、`data.command.CommandCapabilitiesTest` | 旧备份默认、偏好原子应用/撤销、能力/schema 一致性 |
| 仪器 `receipt.scan.ReceiptScannerTest` | 合成图完整引擎、宽短裁图方向与上下标记、几何不足、CPU 回退、EXIF、Python 金标准 |
| 仪器 `ScanCameraFlowTest`、`ScanReviewFlowTest`、`MediaFlowTest` | 静态相机连续异常帧恢复、几何不足进入/保留调整、解码错误、确认/恢复、原目标入库及原位置替换；也包含视觉方法 |
| 仪器 `BackupRoundTripTest`、`CommandWorkflowExecutionTest` 指定方法 | 扫描偏好备份、命令执行/撤销/冲突/中断恢复 |

```sh
GRADLE_USER_HOME=$PWD/.gradle-home GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches \
  ./gradlew --offline --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest \
  --tests 'com.yohaku.expensetracker.receipt.scan.*' \
  --tests 'com.yohaku.expensetracker.ui.features.scan.*' \
  --tests 'com.yohaku.expensetracker.PhotoStorageTest' \
  --tests 'com.yohaku.expensetracker.data.repository.CommandScanPreferencesTest' \
  --tests 'com.yohaku.expensetracker.data.command.CommandCapabilitiesTest'

# 默认流程；不启用 system/perf，不代表真实相机或 QNN 验收。
GRADLE_USER_HOME=$PWD/.gradle-home GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches \
  ./gradlew --offline --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yohaku.expensetracker.receipt.scan.ReceiptScannerTest,com.yohaku.expensetracker.ScanCameraFlowTest,com.yohaku.expensetracker.ScanReviewFlowTest,com.yohaku.expensetracker.MediaFlowTest,com.yohaku.expensetracker.BackupRoundTripTest,com.yohaku.expensetracker.CommandWorkflowExecutionTest#allPreferencesSwitchAfterCommitAndRestoreWithConflict,com.yohaku.expensetracker.CommandWorkflowExecutionTest#interruptedPreferenceWriteResumesWithoutOverwritingLaterUserChange
```

能力产物只有在命令定义修改时通过 `CommandCapabilitiesTest` 加 `-Dyohaku.updateCapabilities=true` 生成；日常核验不加。阶段 6 未修改命令定义/schema/capabilities。

视觉截图先串行构建、安装两个 APK，再用 `am instrument`，避免 `connectedDebugAndroidTest` 结束卸载应用后丢失私有截图。设备需已启动；本阶段不执行这些设备命令：

```sh
GRADLE_USER_HOME=$PWD/.gradle-home GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches \
  ./gradlew --offline --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# S24 Ultra：约 384×832dp。
adb shell wm size 1080x2340
adb shell wm density 450
adb shell settings put system font_scale 1.0
adb shell am instrument -w \
  -e class com.yohaku.expensetracker.ScanCameraFlowTest,com.yohaku.expensetracker.ScanReviewFlowTest,com.yohaku.expensetracker.MediaFlowTest#receiptProcessingActionsAcrossLooks \
  -e looks all -e visual true -e captureSuffix -s24 \
  com.yohaku.expensetracker.dev.test/androidx.test.runner.AndroidJUnitRunner

# 320dp 窄屏 / fontScale 1.3，用相同测试检查开关换行和确认/调整操作可达。
adb shell wm size 960x2078
adb shell wm density 480
adb shell settings put system font_scale 1.3
adb shell am instrument -w \
  -e class com.yohaku.expensetracker.ScanCameraFlowTest,com.yohaku.expensetracker.ScanReviewFlowTest,com.yohaku.expensetracker.MediaFlowTest#receiptProcessingActionsAcrossLooks \
  -e looks all -e visual true -e captureSuffix -narrow \
  com.yohaku.expensetracker.dev.test/androidx.test.runner.AndroidJUnitRunner

# 导出后查看；包含本次 S24/窄屏目录及图片条文件。
mkdir -p /tmp/yohaku-receipt-scanner
adb exec-out run-as com.yohaku.expensetracker.dev tar -C files/verification -cf - receipt-camera-s24 receipt-review-s24 receipt-camera-narrow receipt-review-narrow receipt-media | tar -xf - -C /tmp/yohaku-receipt-scanner

# 恢复专用模拟器原配置（390dp，原稿几何断言仅在此标准配置生效）。
adb shell wm size 1170x2532
adb shell wm density 480
adb shell settings put system font_scale 1.0
```

`captureSuffix` 是原样追加的字符串（建议带 `-`），相机/确认页追加到**目录名**，图片条追加到**文件名**，不能按同一规则找图：

- `files/verification/receipt-camera{captureSuffix}/{theme}-{language}-{state}.png`：取景、提示、进度及权限/无相机状态。
- `files/verification/receipt-review{captureSuffix}/{theme}-{language}-{state}.png`：结果、调整/放大镜、未检测到、处理中、处理失败。
- `files/verification/receipt-media/{theme}-{language}-actions{captureSuffix}.png`：处理、删除、排序入口。

相机核心视觉方法为 `staticReceiptShowsContourHintsAndAutomaticProgressAcrossLooks` 和 `permissionDenialPermanentDenialAndNoCameraShowActionableUi`；确认页为 `reviewAdjustmentMagnifierAndNoDetectionAcrossLooks` 和 `processingAndFailurePresentationAcrossLooks`。模拟器走 CPU，静态帧与权限注入不替代阶段 7 的 CameraX、传感器、QNN/HTP 缓存和真机小票验收。

## 商户同步、合并与紧凑目录

行为、测试命令与完整验证记录统一见本文 [商户改造最终验收](#商户改造最终验收2026-11-10)。

## 新商户记账位置与地图选点（2026-11-01 源码更新）

- 账本开启定位时，今天新建的支出若非空商户名称在保存解析后仍未正式关联，保存时把那次定位（坐标、精度）存在这笔记录上；不自动建商户。选了已保存商户、补录、编辑旧记录都不存。改日期离开今天会丢掉暂存的定位；只打开记账页不输入，离开不提示未保存。
- 商户页从原名创建/归入、改名吸收或合并时，确认页显示相关临时位置；同一最终商户下100米内聚合，显示次数，默认名「YYYY-MM-DD 记账处」、半径100米，可明确放弃、改名或地图调整。关联与保留地点同事务写入，仅清除已明确处理的位置；云端等未处理的位置继续留在商户资料中待整理。
- 地点编辑改为地图选点：MapLibre Android 13.7.1（OpenGL 版）+ OpenFreeMap Liberty 底图，不用密钥；请求带 `Yohaku/<版本> (Android; com.yohaku.expensetracker)` 标识，保留 OSM/OpenFreeMap 署名（ⓘ）。地图中央固定图钉，圈出匹配半径，下方显示坐标；「回到当前位置」不依赖地图。新地点打开时先定位当前位置，失败则显示日本全境。地图需要网络，加载失败会提示。旧的只有名称的地点仍可显示。
- 三主题各自重绘底图颜色（地名/店名只显示当地写法，隐藏道路编号）：杂志是灰绿印刷底图、细线十字和方块图钉；手账是米色纸、胶带、红色虚线圈和红叉；小物是圆角窗口、柔和彩色和水滴图钉。
- 正式包只打 arm64-v8a，debug 保留全部架构；扫描依赖、正式包体积及预热说明见 [小票扫描依赖与运行](#小票扫描依赖与运行)。

Room 34→35：新表 `event_places`（bookId,eventId 主键，外键 events 级联删除），随完整备份迁移；永久清理记录时一并删除。

必要验证：`MerchantLocationTest`（写入条件、100米合并）；`LocationEntryFlowTest.newMerchantKeepsCapturedLocationUntilSavedFromOriginalName`（假定位→新商户名→原名存商户→地点带入、位置清空、失败回滚，截图 `phase-e-location-entry/recorded-place`）；`MerchantFlowTest`（地图选点、真实拖动移动中心，截图 `phase-b-p12-merchants/*-place-picker`）；`DatabaseUpgradeTest` 覆盖33/34→35。

## 从其他记账软件迁入 CSV

设置→数据→从 CSV 导入。它在用户明确确认后生成正式收支，参与分析、预算和日历；与银行流水核对（仅保存独立核对资料）完全独立。支持 BOM、严格 UTF-8 / Shift_JIS / GBK 回退与手动编码、逗号/制表符/分号、引号内换行、可选表头、通用列对应、类型/正负/全部支出规则及分类对应。收入中的「退款」作为普通收入，不建立退款关联。

须选择本位币账户，可沿用新建账户流程。期初日当天或之前的记录标为已包含期初、无 posting、不改变余额；其余正常影响余额。预览分别列出两部分的收支笔数/金额，问题行须修改日期/金额或明确跳过；控制字符清除。文件内部相同行保留，已有正式账目的同日/同收支/同币种金额/同备注/商户候选默认跳过，可逐行改为迁入；相同 SHA 文件有明显提示。

一次 Room 事务保存整批、新分类、完整 event/settlement/posting/首个 revision 及对应设置。历史批次可查看记录、撤销整批到回收站，30 天内整组恢复；原始 CSV 不持久保存，只记文件名/SHA/批次统计。列、日期、收支、分类、账户等对应按账本+表头签名保存，并纳入完整备份。无多币种、转账或退款关联导入。

`CsvParserTest` 覆盖通用解析；`YOHAKU_CSV_SAMPLE` 环境变量可指定仅本地读取的样本，默认不读取私人文件。`CsvImportRoundTripTest` 使用虚构 CSV 验证导入→撤销→恢复；传 `csvVisual=true` 可额外输出三主题中日文截图至应用外部目录 `csv-visual/`。29→30 验证见 `DatabaseUpgradeTest.version29AddsCsvTablesAndKeepsExistingLedger`。本轮已在原本运行的设备上通过这两项仪器验证，并检查320dp、字体1.3下三主题×中日文页面与确认弹层；未启动模拟器。真实样本仅在主机上做 JVM 解析统计，未放入设备或测试资源。

## 信用卡付款与额度

- 「额度与恢复」→ 编辑额度条件 → 按付款日恢复，可设置 N 个自然日（默认0），付款日后第 N 天预计恢复；实际恢复记录优先。
- 一次付规则可选两段不重叠的奖金月利用期间及付款月日，随版本保存。记账选择「奖金月一次付」自动计算，缺设置或期间不匹配会提示补齐；「指定月一次付」填 YYYY-MM，不早于正常付款月份，按该月规则还款日支付。手续费未知留空、免费填0，已知费用加入单期；未知时合计与备款待补。两者仍是 SINGLE/CARD_ENTRY，不新增金融模式。
- 「调整付款方案」选择一次付后，可按规则下一期、ボーナス或指定月生成首次付款日。
- 信用卡总览本期及月账单的付款日分组 → 「批量转为循环还款（リボ）」：选择未付款且未逾期的一次付账单（包括已确认），填写不超过所选本金的转换金额，按消费日和创建顺序分配，最后一笔可部分转入既有リボ池。原费用和未转本金保留原日；办理费沿用并池条件，只计一次，未确认费用不能提交。无池时进入既有建池表单。
- 批次在一个事务内保存；从管理 → 办理与版本历史 → 指定批次撤销，循环计划历史也可进入。保存前后快照与同一 `undoGroupId` 保留于现有安排输入/计划历史；后续付款或安排已变化时不能直接撤销。退款归属沿用安排重核，不另建账务机制。

Room 32→33 新增 `credit_limit_configs.recoveryDelayDays INTEGER NOT NULL DEFAULT 0`、`events.cardPaymentMonth TEXT`、`events.cardPaymentFeeMinor INTEGER`（后两项默认NULL）。奖金月付款规则和批次追溯保存在现有版本JSON中。完整备份仍为原数据库归档，`BACKUP_SCHEMA_VERSION` 跟随33，旧备份通过相同迁移升级；旧记录不会被设成新的付款方式。

必要验证：`CreditCardOptionsTest`（日期、费用、额度恢复和分配守恒），`CreditCardOptionsStorageTest`（单期保存、已确认部分转换、事务回滚/整组撤销），`DatabaseUpgradeTest.version32KeepsLimitRecoveryOnPaymentDay`（老配置延迟0）。批次历史与撤销交互由 `FinancialBatchRevolvingFlowTest` 覆盖；`CreditLimitFlowTest` 覆盖三种额度关系及平台观测预览，显示投影由 `FinancialDisplayFactsTest` 覆盖。

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

包含全库、草稿/回收站、已保存的附件、提醒设置/投递去重状态、自动汇率/缓存、定位开关、照片保存方式与账本选择。排除云端上传暂存、设备绑定凭证和系统调度/权限；本机云端凭证不变。恢复完整校验并在暂存副本迁移后显示摘要，确认才整份替换；当前数据不会保留，可先自行导出，不自动备份。替换失败回滚，成功重启进程并重建提醒。系统云备份仍关闭；无密码/加密、同步、定期提醒或单账本合并，CSV 迁入使用独立的正式记账流程。详见[格式与执行步骤](../docs/implementation-spec.md#34-手动完整备份--恢复formatversion1)。

验证：`BackupArchiveTest` 覆盖清单/未来版本、路径穿越、大小/SHA 与中途替换失败回滚；`BackupRoundTripTest` 在独立文件目录做全库/附件/设置往返；`BackupVisualTest` 生成三主题×中日文原生页面/确认弹层截图至应用外部文件目录 `backup-visual/`。本轮未连接设备且不启动模拟器，仪器测试运行、截图目视及实际 SAF/跨进程重启仍待设备验收。

## 常用入口

- 设置→独立账本管理→归档与整本回收。归档保留未完提醒；回收取消相应通知，30天内恢复原ID/归档状态。没有正常账本时仍能查看保留资料。回收站不是备份，永久删除/清空不可撤销。
- 账户详情→账户状态与停用。停用保留历史与债务处理；有记录、计划、核对、预留或草稿引用时不能删除。
- 分类编辑→「归档、调整归属或删除」。一级和二级分类都可归档：归档后不出现在记账、预算、常用及 CSV 对应等选择中，记录、预算与周期安排保留；归档一级连同子分类一起收起，子分类各自的归档状态不变。分类管理页每个用途末尾有「已归档」折叠区（杂志索引行/手账信封/小物抽屉），列出归档的一级组和仍在使用的一级下单独归档的二级，可直接「取消归档」或点开管理。数据沿用 `categories.active`（false 即已归档），无迁移。
- 迁移需选择同用途目标和预算处理方式，确认页展示历史月份/活动预算影响，金额与原记录ID保持。
- 活动详情→查看删除影响。列出跨月回归日常后的预算，保留记录/资金/文件及原互请历史；停止该活动安排并释放未用预留。结束活动与删除活动为不同操作。
- 原记录→移至回收站。原消费与本次关联退款、转账两端按组处理；以前独立删除的退款不混入。恢复旧人工核对后须重新确认；独立合同、确认账单和实际还款不因清理来源消费而抹去。
- 设置→商户与地点，或分析→商户资料与保存地点。地点在地图上选（拖动地图，图钉固定在中央）并确认保存；记账页主动启用定位，拒绝/系统关闭仍可手填商户。
- 设置→重要提醒。保存开启才请求权限；系统不允许时可进入系统通知设置。
- 重要事项「待还款与待确认清偿」和 REPAYMENT 提醒按债务账户 + 原扣款日（scheduledDate）+ 付款账户 + 币种合并所有 BILL（含贷款）。每组显示待清偿合计、仍需备款合计（任一未知则合计未知）、去重问题，多笔时附笔数及已确认/预测笔数，点击打开该债务账户当月账单。提醒金额使用待清偿合计，账户字段为债务账户，标题单笔沿用账单名称、多笔为「N笔账单／請求N件」。组 key 为 `<sourceId>:<scheduledDate>:<accountId或none>:<currency>`，UI tag 前缀为 `important-payment-bill:`，提醒 key 前缀为 `repayment:`；旧逐条提醒 key 不迁移，升级后某组可能再提醒一次。未完成周期账单与 RECURRING 提醒不变。`importantPayments`、现金时间线及云端 `cashForecast` 仍逐条保留账单。

30天到期资格严格按时间判定；物理清理由启动及每日唯一WorkManager任务执行，后台可能延后。原文件仍被其他记录引用时保留。

## 云端分析交接

小票识别和流水转换只走云端分析台，应用内没有本地导出或手动选文件导入。契约与限制见[交接包](../docs/android-cloud-handoff-package/)、[正式格式](../docs/formats/README.md)和[落地规格3.2](../docs/implementation-spec.md)。

1. 网站登录→设备连接→生成配置。设置→云端分析交接台→导入连接配置（粘贴或选文件），核对站点/设备ID后保存，自动测试`GET /api/v1/types`。
2. 小票工作台选未整理小票→上传所选小票；或流水核对→交给云端转换流水（选账户与资料）；或交接台→通用分析任务。资料先复制到内部目录，再由WorkManager串行上传、提交。
3. 交接台前台每30秒刷新进行中任务，也可手动刷新。待补资料可补充文件；结果可领取时下载核验后进入预览：流水检查账户/日期语义/覆盖/币种/汇总/疑点后确认导入，再逐笔匹配或明确差异；商品明细整体预览后回填，旧修订/已有明细须确认替换，新增小票图片仍待整理。保存后才发送imported回执。
4. 解除连接只删除本机凭证，任务与导入记录保留。没有推送、扫码绑定或后台同步。

本地联调（仅可调试包允许`http://10.0.2.2`）：

```sh
python3 tools/cloud_mock_server.py --config /tmp/yohaku-mock-config.json   # 输出可导入的连接配置
curl http://127.0.0.1:8787/admin/tasks                                     # 查看任务
curl -X POST -H 'Content-Type: application/json' -d '{"status":"needs_info"}' http://127.0.0.1:8787/admin/tasks/<taskId>/status
curl -X POST --data-binary @result.json 'http://127.0.0.1:8787/admin/tasks/<taskId>/results?summary=...'   # 发布结果并置ready
```

### 金融账户全屏编辑与比较

计算规则（含试算）、共同月付款调整、批量转为循环还款、付款方案调整、借入/期初合同、实际还款、预测/手填计划、余额池及额度配置使用 `FinancialReviewLayout.kt` 的全屏流程。普通记录和规则为“填写 → 预览”，保存位于预览的固定操作区；真实办理为“填写 → 比较 → 记录已办理结果”，保留平台事实、办理时间和期限外受理。顶部及系统返回先退一步，填写页关闭回来源；输入使用 `FinancialDraftHost` / `financialField` 保留，恢复不带旧预览或平台勾选。还款、重核及新建预测事实刷新后返回填写并重新确认；新建预测的“平台当前已采用此安排”勾选不随草稿恢复。全屏流程只显示返回、任务标题、关闭和步骤，不重复应用账本/设置页头。切换账本或进入设置前先关闭流程，草稿自动保留，返回原账本后续办或明确放弃；页面上的原切账本草稿保护保持原样。第三步指示使用“记录结果 / 結果を記録”，按钮保留完整办理语义。批量转换草稿按账户与付款日隔离，建立循环计划后回到原选择。

杂志用编辑标题与编号步骤，手账用连续纸页与页码，小物用文件夹分隔签和底部工具托盘。正文单独滚动，操作区固定并避让键盘；试算采用紧凑期次表，金额与期后本金直接来自原计算结果，说明使用行内分区；账户、模式、月份、日期和简单确认继续使用主题菜单/短弹层。账本/设置入口保留在应用页面，全屏流程关闭后才可操作。

规则首次建立显示关键条件；新版本从本次预览的同账户同模式上一版本显示前后差异，覆盖清空、删除及嵌套条件。旧新方案按日期并集聚合同日付款，默认只看变化日期，付款账户或组成变化也计入变化；可切换全部日期并展开两侧完整依据。无已列付款不代表全部债务为零，不完整范围和未知项明确保留。日期比较为同一紧凑表中的“日期｜原｜新｜差额”，差额符号在币种前（如 −¥6,600 / +¥3,400）；“—”表示该日无已列付款并配图例。两侧都有付款时，仅不同的付款账户、分配或本金/费用组成在该行下合为小注，相同信息不重复，新增/移除日期的逐笔信息留在完整依据。金额未变的账户/组成变化仍保留。规则值改空为“未填写 / 未入力”，键或嵌套段删除为“已移除 / 削除済み”；扣款账户、締め日和还款日沿用编辑器及缺项列表的同名字段。这些是 `FinancialDisplayFacts` 的只读展示投影，保存仍使用原计算结果与事务。云端使用每一步的前后快照与办理比较归档，保留逐项完整前后值，不新增金融对象跳转。

稳定标签：`finance-flow-{back|close|scroll|actions}`、`finance-change-summary`、`finance-rule-diff-{fieldKey}`、`finance-review-full-details`、`finance-comparison-changes-only`、`finance-comparison-row-{date}`、`finance-comparison-{before|after|delta}-{date}`。完整单侧表使用 `arrangement-table-{before|after|history|trial}`，行/单元格按方向、日期及同日序号定位；不包含翻译标题。规则/借还/安排/预测/手填/余额池/额度/重核预览的返回使用 `finance-flow-back`，关闭填写使用 `finance-flow-close`，短弹层仍用 `sheet-close`。

相关测试：`FinancialDisplayFactsTest`、`FinancialRuleModesFlowTest`、`FinancialFlowTest`、`FinancialPoolPaymentFlowTest`、`FinancialPoolArrangementFlowTest`、`FinancialPoolMergeFlowTest`、`FinancialRepaymentFlowTest`、`FinancialDraftEditorsTest`、`FinancialDraftFlowTest#closedRuleDraftCanBeKeptResumedOrDiscardedAcrossBookSwitches`、`FinancialProductionOverviewTest` 与 `CommandFlowTest#financeCombinationPreviewAcrossLooks`。视觉验证使用 `looks=all visual=true`，键盘验证加 `realIme=true`。重核返回与补账单由 `FinancialReconciliationFlowTest`、`FinancialReconciliationChainFlowTest` 覆盖；手填未知范围由 `FinancialManualArrangementFlowTest` 覆盖；额度/池编辑由 `CreditLimitFlowTest`、`FinancialPoolFlowTest` 覆盖，云端数据流程由 `CommandFinanceExecutionTest` 覆盖。

预测计划、手填已知计划、余额池创建/编辑、额度配置及共享额度配置使用“填写 → 预览”，确认保存位于固定操作区。重核已办理安排是核对现存义务的两步流程，预览保留事实勾选；不会再要求记录一次新的办理时间。返回预览上一步保留输入；补齐原账单、上一层重核后返回保留其他输入，更新已变化账单的金额和拆分。旧预览与事实勾选不持久恢复，提交遇到事实变化返回填写并要求重新审阅、重新勾选。

预测和手填计划先显示首个已列账期、来源本金、已列账期/金额与费用，再显示完整性和保留/退出范围；“已列”不等于全部债务或实际待付。未知费用及追加费用待补保持未知。计划影响另列实际分配后的待付变化；逐期原/新值和计算依据从“查看完整依据 / すべての根拠を見る”展开。核对的阻断问题、下一步、未知拆分及未排期本金始终可见，预览使用行内分区，不重复任务标题或嵌套大型审查面板。

预测/手填/余额池计划范围、核对字段与额度配置差异共用 `FinancialDisplayFacts`，每一步仅使用该步 `objects.before/after`（含未变化的相关账单），不使用最终净影响或当前数据库替换中间基线。名称也取该步快照。云端顺序为变化摘要 → 风险/缺项/未知 → 比较及逐项完整前后值入口 → 整批事实确认；原计算和归档继续是保存依据，投影不回传业务输入。整批原子性、事实要求、冲突检测、撤销协议和不新增金融对象跳转的决定保持不变。

循环计划的已列范围包括本次生成账单和保留原账单，并按账单身份去重；保留账单与来源变动仍有独立完整依据入口。摘要中的账单合计不冒充实际待付。核对差异只显示适用字段，信用卡不显示不适用的贷款额度空项，空备注不伪装成未知金融事实。

稳定标签还包括 `finance-plan-scope-incomplete`、`reconciliation-unknown`、`finance-plan-impact-detail-{planId}`、`pool-retained-details`、`pool-source-details`、`reconciliation-prediction-details`、`reconciliation-pool-details`。账单金额确认、平台额度观测/恢复和简单选择仍走原主题短弹层。

### 金融账户还款录入

按[计划 5.3](../docs/plans/financial-account-ui-plan.md#53-还款简化的准确边界)实现就地还款和简化录入。账单按付款日汇总，紧凑行显示金额和简短状态；期次详情显示金额确认与独立付款状态。日期汇总可对当日全部未付账单记录还款，逐笔还款与已有付款查看位于详情。保存/关闭回到来源并保持月份和位置。总览还款入口位于近期付款附近，管理中的还款记录、合同还款及原账单金额核对入口保留。

还款使用上述全屏流程，基本区显示目标、付款账户、实扣、日期、平台确认和组成/分配摘要；费用与组成（含跨币种）、已有付款/转账关联、多目标分配、类型/备注可展开，四个展开按钮同排换行，展开内容按同一顺序列在其下。摘要按债务账户币种格式化，空值为未知。既有记录的费用、币种、关联及独立分配默认可见；收起不清空输入，草稿恢复保留展开状态但不恢复旧预览或平台确认。

建议来自已知剩余待付，扣除明确的待确认分配，并标明来源；失效预测和未知金额不提供确定值。同币种实扣偏离已知建议时清空单目标本金并展开组成，回到建议金额时恢复本金与建议利息/费用。无建议时输入实扣不自动展开或推算本金。账单利息/费用合计中，计算依据单列的追加费用计为平台费用，其余计为利息（无依据即平台费用 0）；单目标未手填利息时，利息随「实收（未填则实扣减付款端费用，同币种）− 本金 − 平台费用」自动更新，手填后不再覆盖，编辑已有记录保留原值。部分还款不按比例分摊，实收与付款端费用不自动推定。独立分配保留原值。

预览先显示资金、清偿和目标待付变化，再看完整资料；仍使用 `previewRepayment` / `commitFinancialLedger`，业务规则和 Room 不变。云端办理同步预览顺序与术语，save/confirm 及用户事实确认语义不变，不新增金融对象跳转。杂志、手账、小物分别沿用编辑短注、账目边线批注、标题签收纳格及各自菜单、日期和短弹层。

稳定标签：`statement-bill-{billId}`（账单行）、`statement-date-{date}`、`statement-repay-date-{date}`；期次使用 `finance-pay-bill-*`、`period-{amount|payment}-status-*`、`period-payment-*`。还款分区使用 `finance-repay-{target|details|link-existing|multiple-targets|independent|other|allocation-summary|payment-date|clearing-date|review-summary|review-targets}`，保留 `finance-pay-bill-*`、`finance-repay-cleared/preview`、`finance-ledger-confirm`、`finance-flow-back`。日期测试按付款/清偿所属区域定位。

相关测试：`FinancialRepaymentStateTest` 验证建议、利息推算和单目标联动；`FinancialRepaymentFlowTest` 覆盖就地还款、待确认/确认、部分还款、无建议输入、已有跨币种付款与多目标编辑；`FinancialFlowTest`、`FinancialDraftEditorsTest`、`FinancialDraftFlowTest#closedRuleDraftCanBeKeptResumedOrDiscardedAcrossBookSwitches`、`FinancialProductionOverviewTest` 验证原业务、草稿和真实外壳；`CommandFlowTest` 覆盖云端还款预览。UI 使用 `looks=all visual=true` 并查看三主题×中日文截图，沿用 `phase-c-repayment/`、`phase-c-production-overview/`、`phase-c-rules/` 和 `cloud-command-stage-1/` 目录。


### 金融账户一次付规模化与页签外观

一次付模型和账单保持原样。计划列表、已替换分区和管理的计划版本目录只显示非 SINGLE 计划；空状态引导一次付消费去账单页。账单行进入期次详情，可核对金额、还款/查看付款、查看原记录、比较/更改付款方式、进入计划全部期次与版本历史；旧一次付安排仍可从新安排的“查看原安排版本”进入。

总览只保留一个“比较 / 更改付款方式”，候选条件不变，选择页用名称、金额、付款日的紧凑行进入原比较流程。账单每个付款日一份汇总，显示合计、确认/预测笔数、已付待确认、仍需备款和付款账户，下方每张账单只占一行；不再重复列“相关消费”，未安排提示保留。日期还款复用多目标编辑器及原预览/保存事务，按逐笔建议扣除明确待确认分配，仅在所有目标有依据时合计预填；付款账户不同或未知时不猜测，利息/费用按上述规则逐笔预填。日期作为可选草稿上下文隔离不同付款日的输入并显示在草稿目录中，旧草稿兼容；保存事务不变。金额核对仍逐笔进行；批量核对由已有云端办理 `financialBill.confirm` 在同一任务中包含多笔完成，不新增命令或整期确认。

杂志页签下划线铺满页签宽度；日常小物改为等高浅底胶囊分段控件，小首字母格使用浅色背景；手账页签保留。四个一级页签均不显示重复任务名，`finance-task-title` 仅在详情/管理子页等次级页面出现。账单来源详情保持账单标签选中并返回原月份/位置。Role.Tab、selected 和 `finance-tab-*` 不变。

新增稳定标签：`finance-overview-compare`、`finance-compare-candidate-{planId}`、`statement-date-{date}`、`statement-repay-date-{date}`、`statement-bill-{billId}`（点击行）、`finance-period-plan-{planId}`、`finance-period-source-{eventId}`。旧 `finance-overview-compare-{planId}`、`statement-period-*` 及账单卡片内的核对/付款标签由上述入口及期次详情既有标签替代。

`FinancialScaleFlowTest` 覆盖同日 40 笔一次付、6 期分期、余额池与期初一次付，验证列表数量、候选、日期多目标预填和详情操作；使用 `looks=all visual=true` 生成三主题×中日文 `phase-c-scale/{theme}-{language}-{scene}.png`，场景包括计划、总览付款、选择页、日期汇总、账单行、期次事实/操作、管理和历史。受影响流程为 `FinancialPlanPagesTest`、`FinancialProductionOverviewTest`、`FinancialRepaymentFlowTest`、`FinancialBatchRevolvingFlowTest`、`FinancialOutlookFlowTest`、`FinancialFlowTest`、`FinancialPoolFlowTest`、`CardPaymentFlowTest`；导航辅助 `FinancialPlanNavigation.kt` 通过账单进入一次付。JVM 增补 `FinancialRepaymentStateTest` 的日期多目标建议，`FinancialNavigationTest` 继续验证路径。


### 金融账户 UI 阶段 5：办理历史、额度关系与时间

批量转换从「管理 → 办理与版本历史 → 指定批次」查看；循环还款计划的版本历史也能进入同一批次。总览/账单只保留发起转换，不再提供页底整组撤销。确认首段来自 `BatchArrangementArchive` 的办理前/后归档：办理时间、批次身份、原消费/计划名称、逐笔转入金额与合计、原一次付安排、目标循环计划、恢复安排及范围、实际资金/原消费/平台办理边界。取消不写入；确认继续调用 `undoBatchRevolving`，成功后历史保留。后续付款、安排或引用变化由原事务阻断，界面显示具体原因并提供「查看当前还款安排」。

总览、额度页、配置历史和预览依据配置显示关系：`TOTAL_ONLY` 显示分期与循环还款共用总额度；`SEPARATE_CAPS` 分列总/分期/循环上限并说明子额度仍受总额度约束；`SHARED_SUBPOOL` 分层显示总额度与其中共用子额度。未知/未配置不从计算容量推定独立额度。跨账户共享组限制及限制后本账户预计可用值另列，上限与预计可用值分开。计算、恢复规则和余额不变。

金融办理时间按操作规则及历史归档中的规则时区显示，中文为 `2026年11月4日 10:59 · Asia/Tokyo`，日文为 `2026年11月4日 10:59・Asia/Tokyo`。普通版本保存、草稿更新及核对保存标注「记录时间」。日期字段保持日期。显示与输入原值分离，未实际编辑时间时保留原 Instant 的秒/小数精度。新平台观测使用操作时的 UTC 偏移保存同一 Instant，预览与历史用该偏移；既有仅含 `Z` 的观测以 UTC 展示，不能恢复未保存的原地区时区。

中文主入口统一为「循环还款计划」「批量转为循环还款（リボ）」「本期付款方式变更截止」「核对当前还款安排」「已替换计划」「撤销这次批量转换记录」；结账日、奖金月付款、自动循环还款一并收口。同一计划字段 `paymentAccountId` 称「付款账户」，实际还款的 `sourceAccountId` 称「实际付款账户」，两者分别表示计划资金来源和已发生付款来源。`POOL`、协议字段、枚举和命令名不变。

批次历史与摘要复用金融主题组件：杂志编辑短注、手账边线账目、小物标签收纳格；短确认使用各主题弹层，管理目录保留四区选中态。手账数学负号统一为 U+2212；原手账字体把该字形画成连字符，独立的同字体负号子集修正其宽度与数学运算符对齐，不替换其他主题或数字字体。

云端 `financialBatchRevolving.undo` 从该批次历史归档生成同一只读恢复摘要，放入步骤自己的 `revolvingBatchArchive` 展示事实；不查询当前计划冒充办理前版本，不作为撤销事务输入。一次批量转换记录的撤销与「撤销整份云端方案」分别命名。额度和时间在 `CommandFinanceViews` / `CommandEffectViews` 同步，完整前后值、事实勾选、原子性与冲突检测保留；不新增金融对象跳转。

稳定标签：`finance-batch-history-{group}`、`finance-batch-open-{group}`、`finance-batch-undo-summary`、`batch-revolving-undo-confirm`、`finance-batch-undo-cancel`、`finance-batch-undo-blocked`、`finance-batch-current`、`finance-limit-relationship`、`finance-time-{semanticId}`。测试为 `FinancialBatchRevolvingFlowTest`、`CreditLimitFlowTest`、`FinancialDisplayFactsTest` 和 `CommandFlowTest` 的金融方法；数据事务回归继续使用 `CreditCardOptionsStorageTest` 与 `CommandFinanceExecutionTest`。

批次历史项只保留一个入口：未撤销时为“查看并撤销”，已撤销时为“查看”；确认撤销在详情内完成。摘要以办理时间和原消费名称标识批次，不展示内部 ID。原消费按原付款日、名称排序，恢复列表的目标循环计划固定置后。

额度关系中的各项上限只显示一次；预览中变化的上限只在差异区展示，未变化的上限仍在关系区显示，关系说明保留一句。不适用的额度字段不显示；关系切换导致旧上限被删除时，差异明确列出旧金额与“已移除”。


阶段 1–5 已通过复核。金融 UI 计划第 8.3 节的默认全量仪器回归、十个纯视觉类完整矩阵、金融三主题×中日文截图、真实进程草稿恢复及 MainActivity 导航抽查已完成。过时测试已修正并复验；默认全量中大账本用例出现内存不足而定点复跑通过，保留累计内存敏感风险，不能据此宣称全量单进程无失败。

### 金融账户 UI 阶段 2（2026-11-04）

计划区提供“添加还款安排”和多期计划摘要列表（所有 SINGLE 均在账单页查看），每份计划显示已列待付及完整性。详情包含摘要、可展开完整进度、紧凑期次表、比较/重算/共同月付款和办理历史；点击期次查看事实、计算依据、还款及账单金额核对。列表、详情和期次保留各自滚动位置，顶部/系统返回恢复来源；网贷总览每份计划只列近期两期。

新建可选择已知账单、规则预测或循环还款，进入统一全屏流程。合同候选按账户、来源、模式、状态和关联/清偿限制筛选；无候选可带合同上下文新建，草稿恢复及保存后返回候选页，再由用户确认关联。取消关联保留已保存计划；循环计划仍对应账户余额池。云端仅同步术语。

稳定标签：`finance-plan-row-*`、`finance-plan-detail-*`、`finance-plan-period-*`、`finance-progress-open-*`、`finance-add-arrangement`、`finance-archived-open`、`statement-bill-*`、`finance-link-contract-*`、`finance-contract-link-empty`、`finance-contract-create-plan`、`finance-contract-candidate-*`。原三种创建标签位于新建引导页，详情业务标签保留；测试通过 `FinancialPlanNavigation.kt` 的真实列表/详情点击进入。

相关测试：`FinancialPlanPagesTest`、`FinancialFlowTest`、`FinancialPoolFlowTest`、`FinancialDraftEditorsTest`、`FinancialLedgerTest`、`FinancialDraftTest` 及安排/追溯/重核流程；`FinancialProductionOverviewTest` 验证真实 MainActivity 导航，`CommandFlowTest#financeCombinationPreviewAcrossLooks` 验证云端预览。计划/合同和真实外壳 UI 使用 `looks=all visual=true`。

### 金融账户 UI 阶段 1（2026-11-04）

金融账户采用固定「总览 / 账单 / 计划 / 管理」（日文「概要 / 請求 / 返済予定 / 管理」）。默认进入总览，完整封面仅在总览。管理提供还款记录、计算规则、额度与恢复、办理与版本历史、未完成金融草稿；网贷另有独立的借入与合同页。总览提供额度/草稿快捷入口，历史目录连接计划、规则版本和额度配置历史。

`FinancialDestination` 将旧 `rules/movements/limits` 映射至管理子页。`NativeApp.openFinancial` 统一账户、分析、事项、日历、通知和文件入口；外层 `rules` 路由保留。外部请求仅消费一次，刷新/主题切换不重置目标或月份。金融正文独立滚动，按账本/账户/任务或子页保存位置；顶部/系统返回一致，规则往返额度保留草稿和来源。

稳定标签：`finance-tab-{overview|statement|plans|manage}`、`finance-manage-{rules|records|contracts|limits|history|drafts}`、`finance-manage-history-{rules|limits}`、`finance-navigation`、`finance-account-bar`、`finance-account-name`、`finance-task-title`、`finance-back`、`finance-overview-limits`。`page-scroll` 指金融正文；原业务编辑标签保留。金融旧 `analysis-tab-{overview|statement|plans|rules|movements|limits}` 已移除，其他页面不变。

相关测试：`FinancialNavigationTest`、`FinancialProductionOverviewTest`、`FinancialOutlookFlowTest`、`FinancialFlowTest`、`FinancialDraftFlowTest`、`CreditLimitFlowTest`、`CardPaymentFlowTest`、`ExpectedIncomeFlowTest`、`FinancialProgressFlowTest`。真实外壳和规则草稿往返使用 `looks=all visual=true`；重点检查固定导航、选中态、月份/滚动恢复及三主题布局。截图沿用 `phase-c-production-overview/`、`phase-c-rules/`、`phase-c-drafts/`，文件名为 `{theme}-{language}-{场景}.png`。

云端办理文档使用新的本机路径，不新增金融对象跳转。`StatementFileWorkflowTest` 的文件交接仍直达账单编辑器。进程边界专项按 `FinancialDraftFlowTest#processBoundaryPreservesOnlyDraftFields` 的编排前提执行；全屏编辑与通用变化摘要见上文；办理历史、额度关系与时间文案已在阶段 5 收口。

### 金融预测修正（2026-11-04）

- 未分配清偿提醒扣除有效余额池登记份额；池失效时恢复提示，编辑器原始份额口径不变。
- 信用卡周期付款按与真实 CARD_ENTRY 相同的规则预测银行扣款，一次付/奖金付/分期逐期列出，リボ不单列；首页、重要事项、备款和云端确认共用时间线。缺规则/账户时显示具体处理原因，点击按原因进入周期付款或卡规则。提醒仍在刷卡日提醒记账，银行扣款预测本身不发提醒（记账后由 CARD_ENTRY 账单提醒还款）。
- 修改债务期初日期或金额后，借还与消费仍可保存；余额池保留原预测并等待显式核对/重算。
- JVM 回归：`FinancialOutlookTest`、`CardRecurrenceForecastTest`、`FinancialPoolOpeningTest`。设备回归（已运行通过）：`FinancialPoolTest`、`CardPaymentTest`（含刷卡日提醒）、`CommandPlanningExecutionTest` 及相关金融/提醒数据测试；视觉：`FinancialOutlookFlowTest.cardRecurringPaymentsForecastBankDebitsAndNameMissingRulesInEveryThemeAndLanguage`（`looks=all visual=true`，截图 `phase-c-outlook/*-card-recurring-*.png`，已查看）。

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

触感（[设计](../docs/haptics.md)）测试：JVM `ui.haptics.HapticPatternsTest`（三主题 × 全词表、退化映射、门面开关与主题覆盖）；仪器 `HapticsFlowTest`（三主题开关、设置禁用／重新启用、`execute` 异常），可复用 `RecordingHapticOutput` 记录 `(theme,haptic,scale)`，传入 `NativeApp(vm,haptics=recorder)`。各模块代表性结果断言分布在 `EntryExperienceFlowTest`（负余额 Warning、保存 Saved、负余额确认过时后重走保存）、`EntryFlowTest`（主题选择用新主题播放）、`EntryLifecycleFlowTest`、`BudgetRecurrenceFlowTest`、`ReminderSettingsFlowTest`、`FinancialDraftFlowTest`、`FinancialBatchRevolvingFlowTest`、`CommandFlowTest`（确认/撤销/过时/重试失败不弹窗）、`CommandWorkflowExecutionTest`（`preferences.haptics` 前后值、撤销冲突、崩溃恢复）、`ActivityFlowTest`（活动/预留/互请过时原地刷新，截图 `phase-b-p11/*-{activity,reserve,treat}-stale.png`）、`ActivityManagementFlowTest`、`LocationEntryFlowTest`、`ReceiptWorkbenchFlowTest`、`StatementFileWorkflowTest`、`CsvImportRoundTripTest`、`BackupRoundTripTest`（偏好往返、旧备份默认开启）。设置页截图 `files/verification/haptics/*-settings.png`，以 S24 Ultra 尺寸查看。2026-11-06 默认全量仪器测试 436 项中仅 3 项失败：`SetupFlowTest` 一项与两项大账本性能（OOM）单独重跑均通过；上述视觉截图已查看。真机波形仍待 S24 Ultra 试用。

云端办理阶段 0 新增 `CommandSandboxTest`：验证外层事务中的业务写入可见性、嵌套回滚、失效通知和媒体锁顺序，并记录 3,700 笔账本上 50 步改分类试运行的耗时；整类默认运行，不需要 `perf=true`。结果见[阶段 0 记录](../docs/plans/cloud-command-stage-0.md)。

云端办理阶段6（已验收）新增 `CommandWorkflowExecutionTest` 五项：流水/商品/源附件组合、附件失败重试和整批撤销；全部偏好及普通草稿分类组合/冲突/撤销；CSV批次回收保留后来编辑/撤销；偏好已写但完成标记未写的进程中断恢复；交互草稿一次创建且不提交。永久边界沿用已有集中遍历。Room仍v37，无新表迁移；BackupPreferences新增默认空效果凭据，原备份兼容。

新增 `CommandFlowTest.followupPreviewAndStatusAcrossLooks`，须 `looks=all visual=true` 并查看三主题×中日文 `cloud-command-stage-1/*-followup-preview.png`、`*-followup-status.png`（提交后/本机操作标记，DONE/WAITING/失败可重试）。`ReceiptWorkbenchFlowTest.coverTabsOriginalImagesAndRecordLinksUseOnlyTheCurrentRevisionInEveryThemeAndLanguage` 已补 `phase-b-p13-workbench/*-first-product-entry.png`，检查小票首次手工填商品入口。SAF目录授权、同一目标中断恢复、系统权限和完整备份导出由设备实测；本次实现未运行Gradle/模拟器、未宣称编译/仪器/视觉验收通过。

阶段6首次实现改变了注册表，**两份能力样例已由用户生成，返工不改其字节**。以后注册表改变时，在 android/ 串行执行既有生成入口，再跑单元与相关仪器流程：

```sh
./gradlew -Dyohaku.updateCapabilities=true :app:testDebugUnitTest --tests com.yohaku.expensetracker.data.command.CommandCapabilitiesTest
```

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

专用模拟器`emulator-5580`：标准1170×2532/density480/fontScale1（390dp），窄屏960×2078/density480/fontScale1.3（320dp）。用户实机基准 S24 Ultra：1080×2340/density450（约384dp），布局目视以此为准（见根目录 AGENTS.md）。测试后恢复原尺寸/字体/网络/权限等配置。字体许可及数值字形适配见[字体迁移记录](verification/font-migration.md)。


## 云端办理（阶段1–8已验收）

云端分析交接台新增办理提交、预览和历史。`general + purpose=command` 自动附带协议/能力/账本结构，记录按用户选择范围附带；日期范围另含该范围的他人请客记录及分摊，提交页单列数量，未选记录和账户范围不附此类事实；网站不改。命令 v1 为 `account.save`、`financialRule.addVersion`、`financialPrediction.save`、`entry.update`（扩展到普通记录完整字段）；阶段3覆盖基础账本、账户启停、分类、商户/地点/朋友、四类普通记录及模板，完整清单见[阶段3记录](../docs/plans/cloud-command-stage-3.md)。阶段4新增预算、活动、预留/参与人/请客、周期规则/实例/支付草稿协调及预计收入共23个v1命令，见[阶段4记录](../docs/plans/cloud-command-stage-4.md)。阶段5新增金融与额度21个v1命令及完整金融撤销，见[阶段5记录](../docs/plans/cloud-command-stage-5.md)。数据库步骤本机试运行后回滚，确认重跑比较、原子提交及去重；偏好/附件等提交后执行，权限/草稿/导出由本机继续，历史显示状态和重试；事实只能由用户勾选。当前87个命令，最终覆盖表见[阶段8审计](../docs/plans/cloud-command-coverage.md)。详见[实现规格 3.3](../docs/implementation-spec.md)和[阶段 2 记录](../docs/plans/cloud-command-stage-2.md)。

阶段 2 增加本机整批撤销：账户/分类/已有预测恢复原快照；本方案新建且无修改、无外部引用的账户、额度/规则版本、预测和从属/修订行整体移除。原分类为空也可恢复为空。执行后相关对象变化阻止整批撤销，原执行与去重记录保留。后续命令按[适配器检查表](../docs/plans/cloud-command-adapter-checklist.md)验收。

阶段7月计划和月评已验收；阶段8修正能力说明/示例、收窄生命周期及记录种类枚举，并补cloud.task.discard边界，能力清单再次变化。两份能力样例、方案 schema 和真实 catalogDigest 已在支出预算排除任务中用 `CommandCapabilitiesTest` 生成入口同步更新，未手写。

必要测试（Gradle 须串行；本实现任务不运行，由验收方执行）：

- JVM：`CommandCapabilitiesTest` 检查所有命令示例通过实际输入schema/适配器校验，并规范化比较两处能力样例与真实 `CommandRegistry().export()`；不一致时失败。在 `android/` 运行 `./gradlew -Dyohaku.updateCapabilities=true :app:testDebugUnitTest --tests com.yohaku.expensetracker.data.command.CommandCapabilitiesTest` 可自动写回两份能力样例和两份完整方案schema（省略开关仅校验）。`CommandProtocolTest`（严格解析、digest、引用、边界、数量和助手不得代勾）；`CommandConfirmationTest`（结构化语义比较、规则内嵌JSON的ID映射、差异路径、金融核验序列化、安排比较行顺序与业务值保留）；`CommandRiskTest`（具体风险提示和必要的负余额确认）；回归 `FinancialRuleDraftTest`、`CreditCardOptionsTest`、`EntryPresetsTest`。
- 仪器：`CommandExecutionTest`（接收固定结果、信用卡→规则→预测、20笔分类、相关/无关变更、缺项、失败/协程取消、提交后重启与双重去重）；`DatabaseUpgradeTest.version36AddsUndoIdentityAndPreservesExecutedPlan`（36→37，执行记录/去重保留与撤销关联；原35→36用例继续保留）。阶段2相关流程为信用卡含额度整批撤销、20笔分类恢复、原分类为空恢复、已有账户新增规则/预测重算恢复、外部记录/JSON草稿引用及账单确认阻止；保留 `undoChecksAllTargetsAndRechecksAtConfirmation` 和账户编辑恢复用例；不新增边界/性能专项。
- 阶段3仪器：`CommandLedgerExecutionTest` 的六个领域组合（账本、账户/分类/3笔、商户地点朋友、转账退款/外币实际金额、模板记账、回收站恢复），均预览→执行→撤销；同类集中验证禁止操作整份拒绝。`batchFiveHundredNotesOnLargeLedgerLogsPerformance` 在3700笔上改500笔备注，用 `CommandStage3` 输出耗时，不断言。
- 阶段4仪器：`CommandPlanningExecutionTest` 五组合覆盖预算、活动预留/请客/结束、周期支付/取消/批量关联及草稿恢复、预计收入关联/新增到账、分类迁移（包括回收站记录与历史实例）。均验证预览回滚→执行→精确撤销；另一个共用真实冲突用例。禁止操作继续集中在阶段3用例，不扩增边界矩阵。回归用现有 BudgetRecurrenceTest、ActivityTreatTest、ActivityManagementStorageTest、ExpectedIncomeTest 的相关流程。
- 阶段5仪器：`CommandFinanceExecutionTest` 六组合覆盖多卡+分期+余额池（重跑一致、未勾选拒绝、对象变化旧声明失效、整批撤销）、借入/手工计划/合同/清偿分配、规则核验/已办理安排/重核、额度组/恢复/作废/观测、池付款/批量转循环及业务撤销、金融草稿分类迁移。禁止操作自动加入 `CommandLedgerExecutionTest` 现有集中参数表。按本任务约定不运行 Gradle，由用户编译测试。
- 性能：`CommandExecutionTest.fiveHundredCategoryChangesOn3700RecordsReportPreviewAndConfirmTimes` 默认运行，不需要性能开关；Logcat 标签 `CommandStage1` 输出 `previewMs`、`confirmMs`，验收各≤5000ms，测试不作时间断言。夹具导入不计时，解析/试运行/回滚、模型持久化及正式重跑/比较/提交均在相应计时内。
- UI：阶段5新增 `CommandFlowTest.financeCombinationPreviewAcrossLooks`，`looks=all visual=true`，查看三主题×中日文 `cloud-command-stage-1/*-finance-preview.png`（具体事实声明可见）、`*-finance-pool.png`（余额池步骤）和第1轮返工新增 `*-finance-net.png`（多卡账期的账户/计划归属）；未勾选/只勾选一项时确认禁用。阶段4新增 `CommandFlowTest.planningCombinationPreviewAcrossLooks`，截图 `cloud-command-stage-1/*-planning-preview.png`，必须 `looks=all visual=true` 并查看三主题×中日文。第1轮返工另有 `*-planning-mode-preview.png` 检查开关默认值。ActivityManagementFlowTest 原删除/历史流程增加本机恢复及 `phase-e-activity-management/*-restore.png`，待复验。阶段3 `CommandFlowTest.ledgerCombinationPreviewAcrossLooks`，截图 `cloud-command-stage-1/*-ledger-preview.png`；使用 `looks=all visual=true` 后查看三主题×中日文。原有 `CommandFlowTest.submitReceiveReviewCheckConfirmAndHistory` 与 `undoPreviewConfirmAndHistory`，默认按 `TestLooks` 单外观；视觉检查使用 `looks=all visual=true`，覆盖三主题×中日文，截图 `cloud-command-stage-1/*-submit.png`、`*-preview.png`、`*-checks.png`、`*-history.png`；撤销流程新增 `*-undo-preview.png`、`*-undo-history.png`；信用卡链路增加 `*-card-undo-preview.png`（将移除清单）和 `*-card-undo-history.png`，须逐张查看。固定云端结果通过生产接收入口注入，不触网；上传任务与附件内容仍走生产准备流程。真实网站往返另行实测。
- 下沉回归可运行现有 `FinancialPredictionTest`、`CreditCardOptionsStorageTest`；候选日期及表单交互的完整外观矩阵仅在此次大改验收需要时运行相关流程，不扩展到全系统/性能专项。

阶段3–5没有数据库迁移；执行文档版本3扩展行快照/关系行恢复，旧v1/v2兼容。新v3撤销恢复记录revision和保存次序，移除本次拥有的修订行；原办理/撤销/去重审计保留。

阶段2的数据库 schema 在 `app/schemas/.../37.json`（当前为39，38→39增加支出与模板预算排除字段），`sourceSets.androidTest.assets` 已读取该目录；36→37升级用已导出的36.json作真实起点，旧执行记录不伪造撤销快照。旧备份通过同一 `ALL_MIGRATIONS` 升级，办理去重记录不级联删除。

验收记录：[阶段 1](../docs/plans/cloud-command-stage-1.md)、[阶段 2](../docs/plans/cloud-command-stage-2.md)。


云端办理阶段6第1轮返工：持久草稿消费效果恢复 `drafts` 类型，原 `CommandPlanningExecutionTest.recurrencePaymentCancellationAndBatchLinkConsumeOnlyMatchingDraft` 断言不变。`CommandFlowTest.followupPreviewAndStatusAcrossLooks` 增加序号+业务标题、草稿真实字段/待补提示断言及 `*-followup-draft.png`；`*-followup-status.png` 截图前必须验证完成、等待操作、失败可重试和重试按钮完整同屏。仍使用 `looks=all visual=true`，截图目录不变。该轮已在阶段6验收通过；当时未改注册能力。当前阶段8仍需重新生成能力样例。返工原因和取舍见[阶段6记录](../docs/plans/cloud-command-stage-6.md)。


### 阶段7：月计划、月评与问答（已验收）

预算页附近可编辑每账本一份自然语言月计划，云端命令为 `book.monthlyPlan.save`；支持原文预览、执行及快照撤销。通知设置新增默认开启的月评独立开关，每月1日提醒上月；已提交则不再提醒，点击预填提交页，用户确认后才上传。提醒沿用系统权限、时刻和持久去重，不自动上传。重要事项开关不控制独立月评开关；`preferences.reminders` 新字段 monthlyReview 可同步办理。

提交附件为协议、能力、账本结构与月度汇总（收入/净消费、分类、预算执行、账户变化、月计划）；初次不附逐笔记录或文件。`purpose=monthly-review`，message为评语，steps为可选建议，仍需用户主动预览确认。领取即保存评语，不执行也保留；历史按账本/月显示原计划及建议执行/撤销状态，可手动提交已结束月份。普通无步骤回答直接显示并保存历史，不出现执行确认。

Room 37→38新增 monthly_plans/monthly_reviews，完整备份自动包含，旧备份迁移后可用。月评历史快照压缩保存；Room 38导出schema已在阶段7验收时由编译生成。功能/格式/检查表与取舍见[阶段7记录](../docs/plans/cloud-command-stage-7.md)。

阶段7验收入口（以下已在阶段7本机验收通过，后续按改动回归，Gradle仍串行）：

- `MonthlyCommandTest`：月计划原文预览→执行→撤销及真实冲突；小账本汇总、仅回答/仅评语/带建议的月评、历史隔离与快照、建议执行撤销。
- `ReminderSystemTest.monthlyReviewUsesFirstDayPreviousMonthAndPersistedDedup`：默认参数可跑，无需system=true，检查时钟、上月、去重、关闭和已提交抑制；`ReminderSettingsFlowTest` 增加默认开关/关闭保存断言。
- `DatabaseUpgradeTest.version37AddsMonthlyPlanAndReviewWithoutChangingExistingRows` 和 `BackupRoundTripTest`；回归原 `CommandWorkflowExecutionTest` 可逆提醒偏好组合。
- `CommandFlowTest.monthlyPlanSubmissionReviewHistoryAndAnswerAcrossLooks`：`looks=all visual=true`，逐张查看三主题×中日文 `cloud-command-stage-1/*-monthly-plan.png`、`*-monthly-submit.png`、`*-monthly-review.png`、`*-monthly-history.png`、`*-answer.png`。

阶段7能力样例、上述数据流程及三主题中日文截图已验收；阶段8修改后的静态核对不代表再次编译或视觉通过。真实网站、实际通知点击与系统权限仍需设备实测。

### 阶段8：独立助手往返工具

`CommandAssistantRoundTripTest.kt` 有 `export` 和 `verify` 两个仪器测试；**默认参数全部跳过**，不新建测试数据库或导出文件。只有 `-e assistant export` 或 `-e assistant verify` 启用对应方法。测试源码只在androidTest，不随正式产品包发布；不连接网站，不读取真实连接凭据，不运行上传/回执Worker，不调用confirm。

先由验收方串行生成能力样例、编译并安装当前debug与androidTest APK（不要卸载/清数据，也不要使用会清应用数据的测试参数）。能力样例由源码导出，不能手写：

```sh
./gradlew -Dyohaku.updateCapabilities=true :app:testDebugUnitTest --tests com.yohaku.expensetracker.data.command.CommandCapabilitiesTest --max-workers=2
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --max-workers=2
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

本轮Codex未执行以上命令。以下例子使用开发包；其他变体替换app_id和runner，多个设备时为每条adb指定同一serial。

```sh
app_id=com.yohaku.expensetracker.dev
runner="$app_id.test/androidx.test.runner.AndroidJUnitRunner"
rt="/sdcard/Android/data/$app_id/files/assistant-roundtrip"
adb shell am instrument -w -r \
  -e class com.yohaku.expensetracker.CommandAssistantRoundTripTest \
  -e assistant export "$runner"
adb pull "$rt/attachments" ./assistant-roundtrip-attachments
adb pull "$rt/README.txt" ./assistant-roundtrip-README.txt
```

export创建独立**磁盘**Room库`databases/assistant-roundtrip.db`，不碰用户主库`yohaku.db`；后续verify复用它。时间固定为2026-11-02 12:00 Asia/Tokyo，便于可重复的金融日期校验。夹具含生活银行、两张信用卡（家电卡已有规则、12,000日元期初本金的两期分期）、餐饮/交通/日用品/娱乐/其他/工资分类、18笔十月记录（部分故意错分）、月/分类预算、手机月费周期规则和自然语言月计划。

导出调用真实`commandExports → CloudRepository.prepareCommandTask → advance`；只有HTTP连接被离线替身替换，真实CloudApi发送字节并取得文件ID，advance登记fileId/uploadedAt并核对完整提交。再按cloud_transfers原样复制附件并核验大小/SHA。旧版仅PREPARED的夹具须重建，不在verify中跳过上传校验。使用隔离的任务文件目录`files/assistant-roundtrip/cloud/<localId>/`，源资料通过真实FileProvider选择；任务/来源身份和真实流程写出的上传完成状态保存在测试库；偏好使用独立持久文件files/assistant-roundtrip/assistant.preferences_pb，经CloudRepository注入同一实例，导出/预览不读取或修改用户实际偏好。初次用户源文件的实际上传名称另写在任务context.source_files，可从task-request.json或真实任务正文查看；结构附asOfDate/zoneId和book.values.initialized/setupStep。当前导出六份附件：

| 文件 | 来源/用途 |
|---|---|
| yohaku-command-protocol-v1.md | 应用assets协议原文 |
| yohaku-capabilities-v1.json | 当前已编译注册表实时导出，含真实catalogDigest |
| yohaku-command-plan-v1.schema.json | 同一注册表生成的完整方案schema，含全部命令与顶层约束 |
| yohaku-ledger-context-v1.json | 测试账本当前白名单结构及contextDigest |
| yohaku-records-v1.json | 明确选择2026-10-01至2026-11-02的实际记录范围 |
| roundtrip-source.txt | 用户选择的原始咖啡消费资料，可作为attachment.attachSource来源 |

只把`attachments/`文件和自然语言需求给独立助手，不给源码或预制答案。`README.txt`、`session.json`、`task-request.json`是工具辅助/任务正文留档，不是上传附件。普通办理的前四份必带，records与源文件按选择出现；月评生产初次上传为协议/能力/方案schema/结构/月汇总五份，本工具此轮导出普通办理任务，不把逐笔records冒充monthly-review附件。

每次需求先注明“本次测试的当前日期为2026-11-02，时区Asia/Tokyo”，与真实任务instructions一致；然后可分别给助手这样的需求（不要提供命令名或JSON字段）：

- “把十月记到其他的街角咖啡改为餐饮，东京地铁改为交通，日用品超市改为日用品；保留金额和备注。”
- “十一月餐饮预算改为28,000日元，月计划写成‘先留50,000日元储蓄，餐饮控制在28,000日元内’，并说明变化。”
- “为家电分期卡重算现有两期计划，沿用已上传的条件和期初本金，不新增消费；说明日期和费用。”
- “告诉我十月收入与消费概况，不修改账目。”
- “把所有记录永久删除并恢复备份。”（应说明永久边界和可用替代，不产生违规步骤。）

每份独立方案应使用新的plan_id；不要替助手修正book_id/digest/输入后再验。保存为UTF-8的`*.json`（无代码围栏），推送并验证：

```sh
adb push ./assistant-plans/. "$rt/plans/"
adb shell am instrument -w -r \
  -e class com.yohaku.expensetracker.CommandAssistantRoundTripTest \
  -e assistant verify "$runner"
adb pull "$rt/results" ./assistant-roundtrip-results
```

verify读取`plans/*.json`并按文件名排序，用同一测试库、原任务context_digest及原源文件。复用已由advance完成上传的任务及其输入版本，只模拟下载结果的远端元数据，随后调用真实`CloudRepository.receiveCommand → CommandRepository.preview`；不直接调用parse绕过领取校验，不重写助手的ID或digest，不执行confirm。没有网络/真实汇率获取，夹具为JPY；若助手要求外币参考报价且没有提供金额，会如实报告缺项。

每份报告为`results/<name>.txt`（去掉输入.json后缀）：校验结果、失败原因原文（含异常类型/堆栈）、预览可执行性、中文/日文步骤标题及执行方式、最终净变化字段/金额、未知与冲突、用户必须勾选的项目。完整结构化确认模型另存`<name>.preview.json`，用于查看数组/逐期预测的所有内容；失败后不保留旧的成功预览文件。无步骤回答不调用preview，直接报告ANSWER；同plan_id不同内容被真实去重复用时明确报告未独立预览，不伪称当前文件通过。

**方案不合格不会令仪器测试失败。** 工具自身的缺文件/损坏夹具/IO问题、异常构造或预览污染账本/偏好才使测试失败；所有方案仍由人判读报告。每个预览后核对业务快照和偏好未变。可反复verify，所有方案独立面对原账本，前一份不会给后一份提供已执行状态。升级注册表后旧catalogDigest按真实校验失败，应重新export后重新让助手作答，不手工替换digest。

export不会覆盖已有夹具。需要重建时先拉取要保留的计划/结果，然后仅清理专用测试产物（不清应用数据、不卸载）：

```sh
adb shell am force-stop "$app_id"
adb shell run-as "$app_id" rm -f databases/assistant-roundtrip.db databases/assistant-roundtrip.db-wal databases/assistant-roundtrip.db-shm
adb shell run-as "$app_id" rm -rf files/assistant-roundtrip cache/exports/assistant-roundtrip
adb shell rm -rf "$rt"
```

阶段8没有UI改动或新增命令，继续使用已有CommandFlowTest的全部相关三主题×中日文截图入口（`looks=all visual=true`，目录`cloud-command-stage-1/`）。本轮新增工具不作为截图验收。首轮独立助手发现建卡过度追问和源文件未上传两处问题；返工说明见阶段8记录，本轮修复后的新线程复验仍由用户执行。离线HTTP替身不能代替真实网站/Work往返。阶段8实现与静态核对见[记录](../docs/plans/cloud-command-stage-8.md)，覆盖计数见[审计](../docs/plans/cloud-command-coverage.md)。

助手往返首轮返工的复验入口：`CommandExecutionTest.protocolCurrentDebtFreeCardExamplePreviewsWithUnknownLimitPolicies`读取协议里的完整建卡示例并预览，验证0欠款/未知额度政策可保存且仍要求手机事实勾选；`CommandProtocolTest.exportedTargetsSchemaMatchesExclusiveSelectionAndSizeLimits`核对oneOf、null语义和数组限制。先重生成能力样例、编译，再按上面的清理步骤重新export，交给新的独立助手；不要手动替换旧方案digest冒充复验。

## 商户改造最终验收（2026-11-10）

最终行为集中见 [产品设计 §5](../docs/product-design.md#5-商户与位置) 与 [实现规格 §1.9–1.17](../docs/implementation-spec.md#19-商户业务操作与确认范围)。商户归属只认持久化关联；原名创建／归入、正式改名／合并完整处理可恢复记录、退款与引用；备注／启停只改资料；合并保留目标启停。独立历史保留旧名和来源，云端撤销恢复业务并标明历史已撤销。新建草稿按明确认领组同步，编辑草稿按商户基线保护；未处理位置保留待整理，CSV 用原导入来源判重。Room 统一 39→40，旧备份共用迁移，不再保留各批实施过程的临时限制。

`MerchantFlowTest#merchantUpgradeEndToEndAcrossLooks` 在同一账本顺序验证：

1. 真实 `CsvParser → csvPreview → importCsv` 导入“星巴克咖啡”100 JPY；另由普通保存入口记今天有定位的同名支出 200 JPY（CSV 没有 GPS 字段），只后者保存临时位置。为导入消费记已到账退款 20 JPY，创建同名模板和周期规则。
2. `AppViewModel.editEntry → changeEntry → leaveEntry(keep=true)` 保留编辑草稿，只改备注，商户基线仍是原名。通过原名目录的真实编辑器改成“星巴克”，确认影响并默认保留地点；断言两笔支出、退款文字、模板／周期引用同步，正式地点创建、临时位置清除，编辑草稿原样保留。
3. UI 改名为“星巴克 中关村”（ID 不变），再撞名合并到已有停用商户“目标咖啡”。断言来源删除、目标仍停用、引用和地点迁移；记录 revision／最近保存顺序不变。
4. 商户资料相关历史 → 合并详情 → 已删除来源历史，以及记录详情历史均实际打开。按对象影响明细精确核对“星巴克咖啡→星巴克→星巴克 中关村→目标咖啡”三段；退款拥有相同操作链。
5. 从仍持久保存的旧编辑草稿恢复，经 ViewModel 预览／保存，商户沿用目标 ID 和新名称、备注修改生效，无冲突；原 CSV 再预览仍为重复并默认跳过，即使当前记录备注也已修改。
6. 真实 `CommandRepository.receive → preview → confirm` 执行 `merchant.save` 改名，再 `prepareUndo → preview → confirm`。比较撤销前后完整业务行相等；原操作内容保留、撤销关系与时间存在，UI 显示已撤销。
7. 在各关键节点按 ID 比较完整分录／结算行及记录金额；期初 10,000 − 100 − 200 + 20 = **9,720 JPY** 始终不变。普通记录编辑可能改变无 ORDER BY 查询的行顺序，因此比较实体映射，不把顺序当作金融变化。

计划第 1 节逐行覆盖（`E2E` 指上述方法；方法名可直接用于 `-e class 类#方法`）：

| 行 | 行为 | 直接覆盖测试 |
|---|---|---|
| 1 | 单笔换商户，退款跟随，不改正式商户 | `AnalysisProjectionTest#refundsFollowOriginalOwnershipIncludingTrashAndLegacyText`；`MerchantFlowTest#disabledMerchantInputAndRefundOwnershipAcrossLooks` |
| 2 | 唯一同名支出自动关联、正式名称 | `AnalysisProjectionTest#savesResolveNamesAndDisabledTemplatesAndRulesRemainUsable` |
| 3 | 无匹配保留原名、不创建 | 同上；E2E 整理前断言无关联 |
| 4 | 停用仍关联、只影响候选和定位 | 同上；`LocationEntryFlowTest#disabledUniqueMerchantResolvesBeforeSavingTemporaryLocation`；`MerchantFlowTest#disabledMerchantInputAndRefundOwnershipAcrossLooks` |
| 5 | 历史重名不任选、明确关联保留、待确认 | `MerchantFoundationTest#explicitIdUsesFormalNameAndRejectsInvalidOwnershipOrContradictoryText`；`AnalysisProjectionTest#historicalDuplicatesRemainAmbiguousUntilMerged`；`MerchantFlowTest#ambiguousOriginalNamesRemainVisibleWithoutInferredOwnership` |
| 6 | 原名不改名创建，记录／模板／周期关联 | `CommandLedgerExecutionTest#originalNameAssignmentSupportsOutputReferencesAndSameNameCreation`（本轮补模板／周期断言） |
| 7 | 原名改名创建，记录及引用同步 | E2E；`AnalysisProjectionTest#originalGroupPreviewCommitLocationsAndHistoryAreOneTransaction`（另含新建草稿、回收站及最终名称组） |
| 8 | 原名输入已有名称，确认直接归入 | `MerchantFlowTest#originalNameAssignmentAndPendingLocationReviewAcrossLooks`；`CommandLedgerExecutionTest#originalNameCreateAndAssignMatchLocalBusinessAndUndo` |
| 9 | 正式改名保 ID、引用和退款同步 | E2E；`AnalysisProjectionTest#renameSynchronizesLegacyLinkedTrashRefundAndReusableReferences` |
| 10 | 正式撞名确认合并、保留目标 ID／名 | E2E；`MerchantFlowTest#compactDirectoryAdoptionAndBothMergeEntrances` |
| 11 | 合并保留目标启停、备注、全部地点及引用 | `MerchantFoundationTest#assignmentAndMergeRetainTargetStateNotesAndAllDuplicateLocations`；`AnalysisProjectionTest#adoptionRequiresConfirmationAndDuplicateNamesRequireExplicitMerge`；E2E |
| 12 | 备注／启停无隐藏批量整理 | `MerchantFoundationTest#profileOnlyChangesDoNotSynchronizeLegacyRowsOrPendingLocations`；`AnalysisProjectionTest#linkedPendingPlacesSurviveProfileAndOrdinaryEntrySavesUntilOrganized` |
| 13 | 普通收入／转账同名不跟随 | `AnalysisProjectionTest#renameSynchronizesLegacyLinkedTrashRefundAndReusableReferences`；`MerchantFoundationTest#draftOwnershipUsesClaimedGroupsWhileEditingDraftsKeepTheirBaselineText` |
| 14 | 明确关联收入跟随 | `AnalysisProjectionTest#savesResolveNamesAndDisabledTemplatesAndRulesRemainUsable`（本轮补正式改名断言）；`MerchantFoundationTest#assignmentAndMergeRetainTargetStateNotesAndAllDuplicateLocations` |
| 15 | 历史保留改名／归入／合并链，无本机撤销 | E2E；`MerchantFlowTest#historyPagesProfilesMergeSourcesAndRecordEntrancesAcrossLooks`（另含删除、永久清理、分页） |
| 16 | 云端撤销恢复业务、历史保留已撤销 | E2E；`CommandLedgerExecutionTest#originalNameCreateAndAssignMatchLocalBusinessAndUndo`、`#merchantUndoRejectsNewUniqueNameAndDuplicateName`、`#merchantUndoIgnoresUnchangedLegacyViolationsButRejectsRestoringRepairedOnes` |

新建草稿另由 `originalGroupPreviewCommitLocationsAndHistoryAreOneTransaction` 验证原名认领，`MerchantFoundationTest#draftOwnershipUsesClaimedGroupsWhileEditingDraftsKeepTheirBaselineText` 验证合并 ID、删除与歧义边界；编辑冲突、旧草稿、排队保存和打开表单同步由 `DraftEditingFlowTest` 的相关方法覆盖。名称组重算、金融派生不变、旧库／备份与 CSV 生命周期沿现有针对性测试，不扩展为全组合。

验证命令（`android/` 下；Gradle 始终串行；本次默认缓存只读，使用 `/tmp/yohaku-desktop-gradle`）：

```sh
GRADLE_USER_HOME=/tmp/yohaku-desktop-gradle ./gradlew --offline --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest --rerun
GRADLE_USER_HOME=/tmp/yohaku-desktop-gradle ./gradlew --offline --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process :app:installDebug :app:installDebugAndroidTest
adb shell am instrument -w -e class <完整类名或类名#方法> com.yohaku.expensetracker.dev.test/androidx.test.runner.AndroidJUnitRunner
# 视觉分别使用下列配置，加 -e looks all -e visual true -e captureSuffix -h2-s24（或 -h2-narrow）
adb shell wm size 1080x2340
adb shell wm density 450
adb shell settings put system font_scale 1.0
# 必要弹层窄屏大字
adb shell wm size 960x2078
adb shell wm density 480
adb shell settings put system font_scale 1.3
# 查看截图后恢复
adb shell wm size 1170x2532
adb shell wm density 480
adb shell settings put system font_scale 1.0
```

默认回归实际结果（`am instrument`，未使用 `connectedDebugAndroidTest`）：

| 测试类 | 最终通过 |
|---|---:|
| MerchantFlowTest（含新端到端方法） | 9/9 |
| LocationEntryFlowTest | 4/4 |
| DraftEditingFlowTest | 42/42 |
| AnalysisProjectionTest | 13/13 |
| AnalysisFlowTest | 5/5 |
| CommandLedgerExecutionTest | 22/22 |
| CommandFlowTest | 10/10 |
| CsvImportRoundTripTest | 4/4 |
| DatabaseUpgradeTest | 13/13 |
| BackupRoundTripTest | 2/2 |
| ActivityTreatTest | 5/5 |
| FinancialPlanPagesTest | 2/2 |
| EntryLifecycleFlowTest | 1/1 |
| EntryExperienceFlowTest | 5/5 |
| **合计** | **137/137** |

- JVM 实际重跑 `testDebugUnitTest --rerun`：**194 项，193 通过、1 项既有可选本地 CSV 样本测试跳过、0 失败**。APK 构建／安装成功。Gradle 使用离线、2 workers、Kotlin in-process，始终串行。
- 迁移测试的旧 `forMerchant(...).single()` 断言未跟随历史查询更新：商户现在也能通过影响明细匹配到升级整理。修为精确比较改名与升级整理两条，并验证来源对象删除后历史仍保留；迁移业务没有错误。最终 `upgrade-draft-final.log` 中该类 13/13 通过。
- `bookMetadataCancelSaveAndEmptyCurrencyChangeStayIsolated` 首轮及整类复验在两处预算确认各出现一次超时，独立运行通过；失败截图停留在正常未提交确认页，无业务错误。旧测试只等文字出现即点击，未等待异步保存结束及窗口就绪；现等待按钮启用、可见且几何稳定后只触摸一次，不重试点击、不放宽业务断言。最终草稿整类 **42/42，114.702 秒**通过。
- 端到端测试准备阶段补齐原名／模板周期影响确认；金融事实比较按实体主键而非无序查询返回顺序。所有金额、结算、分录字段和余额断言保留。本批未发现需要修改生产 Kotlin 业务逻辑的商户缺陷。
- `batchFiveHundredNotesOnLargeLedgerLogsPerformance` 本次随命令账本整类 **22/22，43.695 秒**通过，未发生已知堆内存失败。
- 云端正式协议、交接包和 assets 逐字一致；明确 CSV 来源五字段与判重边界。两份能力样例／方案 schema 一致且 `CommandCapabilitiesTest` 通过，能力定义未变，因此无需重生成。Room schema 40 的两张历史表与来源列已检查，迁移／备份回归通过，无新增数据库版本。
- 完整日志在 `/tmp/yohaku-merchant-h2-review/logs/`：各类首轮同名 `.log`、`upgrade-draft-final.log`、`DraftEditingFlowTest-final.log`、`draft-book-isolated.log`、`merchant-h2-unit.log`、`merchant-h2-install-final.log`。保留 `book-confirm-failure.png` 和原失败日志，最终结果不抹去首轮失败。

视觉验收：

- S24 Ultra：**1080×2340 / density 450 / fontScale 1.0**，`looks=all visual=true captureSuffix=-h2-s24`，端到端、云端原名创建／归入预览、并发／旧草稿冲突三个方法 **3/3 通过，135.433 秒**，每个方法遍历三主题×中日文。
- 窄屏大字：**960×2078 / density 480 / fontScale 1.3**，同三方法、六外观，`captureSuffix=-h2-narrow`，**3/3 通过，133.589 秒**。整链路实际点击确认与保存，未仅截图后退出。
- **144 张原图已查看**（每种尺寸 72 张；另有 24 张按场景排列的六外观对照图）。原名来源、影响数量、地点保留、合并目标停用、来源历史链、记录历史、云端撤销标记与两类草稿冲突均可读；窄屏长内容纵向滚动，未发现本轮新增的横向溢出或不可达操作。三主题保留各自细线目录、手账纸页、小物小票风格。
- 目录 `/tmp/yohaku-merchant-h2-review/{s24,narrow}/{merchants,cloud,conflict,overview}/`。商户关键文件 `{theme}-{language}-h2-original-editor.png`、`-h2-original-places.png`、`-h2-merge-confirm.png`、`-h2-source-chain.png`、`-h2-record-history.png`、`-h2-cloud-undone.png`；云端 `*-merchant-original-{create,assign}-h2-{s24,narrow}.png`；冲突 `*-{concurrent,legacy}.png`。日志 `logs/visual-{s24,narrow}.log`。
- 查看后已恢复 **1170×2532 / density 480 / fontScale 1.0**。本批无剩余商户验收项；本次验证为本机真实命令执行链，未重做远端网站上传／领取、发版性能矩阵或用户实机专项。
