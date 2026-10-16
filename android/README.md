# 余量 Android · 阶段D

原生 Kotlin / Compose 应用。阶段A P01–P07、阶段B P08–P13和阶段C P14–P18的实现、验收与交付见[开发计划](../docs/plans/development-plan.md)、[阶段B状态](verification/phase-b-status.md)和[阶段C状态](verification/phase-c-status.md)。阶段D P19–P20实现正式文件协议、银行卡流水核对、小票ZIP与商品回填，验收依据见[阶段D状态](verification/phase-d-status.md)。后续P21–P25继续系统提醒/定位、完整资料管理与发行交付。

## 安装包

[阶段D APK 0.4.0](artifacts/yohaku-phase-d-0.4.0.apk) · [SHA-256](artifacts/yohaku-phase-d-0.4.0.apk.sha256)

历史交付：[阶段C 0.3.0](artifacts/yohaku-phase-c-0.3.0.apk) · [阶段B 0.2.0](artifacts/yohaku-phase-b-0.2.0.apk) · [阶段A 0.1.0](artifacts/yohaku-phase-a-0.1.0.apk)。

- Android 10及以上（minSdk29），compile/target SDK37，开发包名 `com.yohaku.expensetracker.dev`，versionCode4，Room v25。
- 已签名开发包，约86.9MiB，与阶段A/B/C证书一致。覆盖更新保留账本与本地资料，**不要先卸载**；卸载会删除本地数据。正式发行包及固定正式签名按P25处理。
- 新安装从空白设置开始，没有预建分类、流水或测试资料。测试APK、模拟器数据与截图不打入产品包。
- API36专用模拟器验证；用户实机未连接，不能据此声称实机验收通过。

将APK传到手机，用系统文件管理器打开；系统要求时允许该文件管理器安装应用。也可在 `android/` 目录执行：

```sh
adb devices
adb -s <serial> install -r artifacts/yohaku-phase-d-0.4.0.apk
```

## 可用范围

- 三套独立主题、中文/日语，按账本保存偏好；空白设置、独立账本、两级分类、现金/银行/信用卡/贷款/交通IC账户。
- 收支、历史补录、商户、筛选/详情、原ID修改与修订、独立草稿和进程恢复、基础删除/恢复。
- 原币/账户币/统计币、预计与实际金额、部分退款、同币转账及跨币换汇、两端实际日期和在途确认。
- 自然月/分类预算、周期待办与实际执行、活动预算和资金预留、多人互请；可选分析模块、分类/商户下钻。
- 系统多选附件和小票、应用内副本、图片/音频及外部只读打开，小票排序与修订。
- 用户配置的一次付/分期/リボ/固定贷款/循环贷款规则，生效版本、日期/端点/费用/舍入/时区，缺项与不支持状态，手动已知账单；没有金融机构预设。
- 信用卡消费付款方式、自动リボ与账户余额池、逐期本金/费用/合计/期后本金、确认账单优先；总/子/共享额度及实际恢复依据。
- 固定合同四种还款方式及循环借入；本金、净到账、预扣和另付费用分开，借入不算收入。实际付款与平台清偿分开，支持部分/提前/跨币种付款、原记录关联、未知拆分。
- 原/新安排比较、期限与费用、独立确认已办理、部分转出/转池/同池月付调整、来源更正后的重核，保留原账单、实际流水和不可覆盖版本；纯试算不写正式账。
- 预计收入手记及到账新建/关联；按真实资金账户的逐日预测、基础/含预计收入两情景、预算与资金缺口分列、付款日历、重要事项及实际处理入口。
- 固定Frankfurter v2参考汇率服务、币种/日期查询、持久缓存、自动查询开关/手动刷新、离线保留和手填。请求只含币种/日期，不上传账目；参考金额不改写实际账。

- 正式银行卡JSON的标识导出、整体预览、一对一资金端核对、独立人工差异、类型补记及实际入账/账单确认；原始文件可另存与删除，导入不自动写账。
- 真实小票多图ZIP、按小票分包、原字节校验、商品JSON整体回填、独立商品编辑；旧修订/重复替换需确认，新增原图仍待整理。格式帮助可保存完整说明/schema/样例ZIP。

未知金额保持未知，未确认清偿不假结清，其他账户余额和信用额度不自动抵消扣款账户缺口。特殊机构算法不冒充支持，可使用平台已知的手动逐期金额。

尚未完成：P21系统通知及提醒设置、P22定位、P23完整资料归档管理、P24/25最终全应用审查和正式发行。当前“重要事项”为应用内列表，不申请通知权限。无金融机构连接、登录、云同步、备份、应用锁或OCR。

## 验收路径

1. 新建账本与资金/信用卡或贷款账户；已有债务按原币期初补充，检查没有再次计收入或消费。
2. 进入金融详情配置规则；缺项先查看提示，也可输入已知逐期账单。试算后保存，核对每期本金、费用、合计和依据。
3. 录入信用卡消费并选择付款方式，查看账单月份、余额池和额度；确认平台账单后修改规则，原确认历史应保留。
4. 新建固定/循环借款，例如本金10000、净到账9700、预扣300；检查本金债务10000、资金增加9700、费用300一次。
5. 从账单记录实际还款；平台未确认时源资金已扣、债务仍待清偿，确认后只减少明确分配的本金/费用，不再次扣款。跨币种分别填写实付与实收。
6. 比较分期/リボ或调整月付款，返回修改不改变正式计划；只有独立确认平台已办理后才改变未来义务，历史与实际资金保留。
7. 添加预计收入，从首页重要事项/未来风险查看两个情景；预计收入不增加实际余额。确认新到账或关联已有收入后，不应重复入账。
8. 从风险入口转入资金或记录实际收入；核对扣款账户风险变化。切换账本、三主题与两语言，重要事项应只显示当前账本资料。
9. 在设置查询参考汇率，选择币种/日期、关闭自动后手动刷新；断网保留来源日期和缓存，也可手填实际金额。

## 构建与验证

JDK17、AGP9.3.3、Gradle Wrapper9.5.1、Compose BOM2026.09.00。SDK由本机 `local.properties` 或 `ANDROID_HOME` 指定。

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:testDebugUnitTest :app:lintDebug
```

设备测试按对应测试类与专项参数运行；默认跳过真实IME/跨进程测试不能当作通过。阶段C的计算、数据库/迁移、标准390dp、320dp大字、必要真实IME和独立进程恢复范围及最终结果见[阶段C状态](verification/phase-c-status.md)。真实HTTPS记录与确定性UI测试分别标注，离线测试不冒充在线服务验证。字体许可和数字字形适配见[字体迁移记录](verification/font-migration.md)。

## 专项测试说明

跨进程测试 `ProcessRecoveryTest` 必须按seedSetup→force-stop→verifySetupAndSeedDraft→force-stop→verifyDraftAndCleanup分别执行，传 `-e processRecovery true`；最终三次不同PID恢复及验证库清理均通过。真实IME分别使用 `SetupFlowTest#realKeyboardKeepsSetupSaveReachableInEveryThemeAndLanguage` 与 `DraftEditingFlowTest#realKeyboardLeavesSaveReachableAcrossThemesAndLanguages`，传 `-e realKeyboard true`，启用真实键盘并在结束后恢复配置。已有标准和320dp/1.3字体成功结果，普通跳过不能代替这些检查。

当前专用模拟器 `emulator-5580`（API36）：阶段C最终基准为1170×2532/density480/fontScale1，即390dp；窄屏为960×2078/density480/fontScale1.3，即320dp。测试后恢复标准尺寸、字体与键盘设置。未连接用户实机。


P13真实系统选择和外部只读验证使用独立测试模块 `media-verifier`。它提供无用户数据的DocumentsProvider及不同UID的读取验证器，不被打包进产品 `app` APK。构建/安装 `media-verifier-debug.apk` 后，运行 `MediaFlowTest` 可检查系统多选、真实媒体操作及外部只读交接；结束后卸载 `com.yohaku.verification`。当前验证日志和剩余验收范围见 `verification/phase-b-status.md`。

阶段B草稿进程恢复使用`PhaseBProcessRecoveryTest`的三个独立步骤（须传`-e phaseBRecovery true`），每步之间执行`am force-stop`；PID变化和持久库/媒体核对用于区别于仅重建Compose界面。顺序为`seedTransferDraft`、`resumeTransferAndSeedActivityDraft`、`resumeActivitySaveOnceAndCleanup`。只在专用模拟器执行，最后一步清理专用库/媒体并恢复原选中账本；证据见阶段B状态。

预算与周期持久化恢复使用`PlanningProcessRecoveryTest`（须传`-e planningRecovery true`），依次单独运行`seedStoppedRulesAndBudget`、`resumeStoppedRulesAndReactivateThroughUi`、`verifyReactivatedFactsAfterAnotherProcessAndCleanup`，步骤间执行`am force-stop`。使用专用数据库和固定日期，验证三主题中日文的规则撤下/恢复、主动取消、付款事实、预算与稳定待办ID；最后一步清理专用库并恢复选中账本。此不验证未保存表单的进程恢复。

## 阶段D文件操作

1. 设置→银行/信用卡流水核对→导入标识说明→保存JSON。将此标识和银行原始资料交给助手，按[正式格式说明](../docs/formats/README.md)转换为 `yohaku.statement` v1；名称不能代替UUID。
2. 选择JSON，检查账户、日期语义、覆盖区间、币种、笔数、汇总及疑点，再确认导入。重复文件打开已有资料；重叠资料须明确确认。按候选处理差异，或先选类型再进入普通记账/金融表单保存。仅导入不改变余额。
3. 小票工作台选择待整理图片组→导出→选择系统目录。将实际ZIP交给助手，按包内manifest及格式说明生成 `yohaku.receipt-items` v1。不要把格式样例当成当前账本资料。
4. 工作台“导入商品明细”或原记录小票区的导入入口→选择JSON→检查图片关联、原币商品合计/差额和未知字段→确认回填。旧修订或已有明细需要再次确认；原记账金额保持用户输入。
5. 回到原记录查看或编辑商品。新增图片仍待整理；取消文件选择、预览或替换不会覆盖已有商品。设置→正式文件格式与导入标识可保存说明、四份schema及样例ZIP。

四类正式格式均为UTF-8 JSON、版本1；不支持的版本或任一字段错误会整份拒绝，并显示字段路径。工作流只操作本地文件，无OCR、自动上传或银行连接。
