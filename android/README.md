# 余量 Android · 阶段B

原生 Kotlin / Compose 应用，阶段A P01–P07与阶段B P08–P13已完成。完整应用按[开发计划](../docs/plans/development-plan.md)继续实施；阶段C–E尚未完成。

## 安装包

[阶段B APK 0.2.0](artifacts/yohaku-phase-b-0.2.0.apk) · [SHA-256](artifacts/yohaku-phase-b-0.2.0.apk.sha256)

历史交付：[阶段A 0.1.0](artifacts/yohaku-phase-a-0.1.0.apk)。

- Android 10及以上（minSdk29），包名 `com.yohaku.expensetracker.dev`，versionCode2，Room v11。
- 已签名的开发包，约74.9MiB；已在API36专用模拟器实际安装和冷启动，未连接用户实机。
- 新安装从空白欢迎设置开始，没有预建分类或流水。测试数据和设计稿不随APK分发。
- 同包同签名覆盖安装保留本地数据；不要为更新先卸载。卸载会删除本地账本。未来正式包 `com.yohaku.expensetracker` 与开发包独立，正式签名/完整交付按P25处理。

将APK传到手机后，用系统文件管理器打开安装；系统要求时允许该文件管理器安装应用。也可使用ADB，先确认目标设备：

```sh
adb devices
adb -s <serial> install -r artifacts/yohaku-phase-b-0.2.0.apk
```

以上安装命令在 `android/` 目录执行。旧的 `yohaku-phase-a-preview-20261014.apk` 是历史审查快照，使用本节的新交付包。

## 已完成范围

- 三套独立主题与中文/日语；页面、菜单、日历、弹层分别保留其主题表达。偏好按账本保存，切换保留输入。
- 空白四步设置；独立账本创建、切换和资料编辑；现金、银行、信用卡、网贷、交通IC账户及原币期初。
- 两级支出/收入分类，一级未细分、搜索、常用、最近及管理；记账先选分类，无账户可创建后续写。
- 收入/支出、商户和备注、日期、历史补录；流水筛选、详情、原ID编辑和修改记录；保存后主页、账户及基础分类汇总一致。
- 按账本保存草稿、继续/放弃、进程恢复、取消编辑；基础30天软删、影响预览与原ID恢复。
- Room v3、v1/v2非破坏升级、事务和账本关联约束；资金与债务分列，不将信用额度当现金。私有数据目录，无云备份/同步、应用锁或提前位置/通知授权。

基础月预算与首页余量可用；完整分类/活动预算、周期预留、分析模块选择及风险属于后续阶段。退款/转账/换汇、信用卡分期/リボ和贷款计算、账单核对、小票/附件、实际通知/定位以及完整归档/管理也按P08–P25实现。此包不代表这些能力已完成；未完成能力不提供假结果或无效提交入口。

## 建议验收路径

1. 欢迎设置创建账本，资金/债务步骤可跳过；检查没有自动分类或流水。
2. 点中央记账，创建一级“餐饮”和二级“咖啡”，选择二级后进入表单；没有账户时创建现金账户，期初10000。
3. 保存支出680，查看账户余额9320；详情编辑为700，原记录仍一笔、余额9300；取消未保存修改应保留原值。
4. 另记收入并检查资金增加、月预算不自动增加；从流水按商户、金额、父/子分类和日期筛选。
5. 创建第二账本，切换检查资料与主题/语言独立；保留未保存草稿后切回续写。停止应用再打开应恢复草稿。
6. 删除一条已保存记录，查看影响及余额，进入设置回收站恢复，检查原ID与资金恢复且不重复记账。
7. 切换三主题与中文/日语；在较大系统字号及键盘展开时检查输入、保存、关闭和返回可达。

杂志数字使用已记录的必要许可字体适配，布局与基线对照及具体字形差异见[字体迁移记录](verification/font-migration.md)。其余主题字体使用原许可资源和同主题完整字库，图形按原SVG迁移。

## 构建与验证

JDK17、AGP9.3.3、Gradle Wrapper9.5.1、Compose BOM2026.09.00；compile/target SDK37，SDK平台 `android-37.0`。SDK由本机 `local.properties` 或 `ANDROID_HOME` 指定，不提交本机路径。

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

阶段A历史验证：构建/金额测试/lint成功。常规仪器回归105项中唯一失败是旧金额文本定位；保留失败日志，修正后六组合专门复验通过。默认参数跳过的真实IME与跨进程序列不计作通过，另有实际专项证据。完整需求映射、三主题/中日文原稿对照、标准/320dp大字及专项结果见[阶段A最终审查](verification/phase-a-status.md)。

跨进程测试 `ProcessRecoveryTest` 必须按seedSetup→force-stop→verifySetupAndSeedDraft→force-stop→verifyDraftAndCleanup分别执行，传 `-e processRecovery true`；最终三次不同PID恢复及验证库清理均通过。真实IME分别使用 `SetupFlowTest#realKeyboardKeepsSetupSaveReachableInEveryThemeAndLanguage` 与 `DraftEditingFlowTest#realKeyboardLeavesSaveReachableAcrossThemesAndLanguages`，传 `-e realKeyboard true`，启用真实键盘并在结束后恢复配置。已有标准和320dp/1.3字体成功结果，普通跳过不能代替这些检查。

当前专用模拟器 `emulator-5580`。2026-10-15核对物理尺寸为1080×2400、物理密度420、现有密度覆盖480，`wm size reset` 后宽度为360dp。390dp基准须显式设1170×2532/density480/fontScale1；320dp大字须显式设960×2078/density480/fontScale1.3。不能仅凭reset命令称为390dp。模拟器和用户实机结论分别记录，不把模拟器验收称为实机验收。


P13真实系统选择和外部只读验证使用独立测试模块 `media-verifier`。它提供无用户数据的DocumentsProvider及不同UID的读取验证器，不被打包进产品 `app` APK。构建/安装 `media-verifier-debug.apk` 后，运行 `MediaFlowTest` 可检查系统多选、真实媒体操作及外部只读交接；结束后卸载 `com.yohaku.verification`。当前验证日志和剩余验收范围见 `verification/phase-b-status.md`。

阶段B草稿进程恢复使用`PhaseBProcessRecoveryTest`的三个独立步骤（须传`-e phaseBRecovery true`），每步之间执行`am force-stop`；PID变化和持久库/媒体核对用于区别于仅重建Compose界面。顺序为`seedTransferDraft`、`resumeTransferAndSeedActivityDraft`、`resumeActivitySaveOnceAndCleanup`。只在专用模拟器执行，最后一步清理专用库/媒体并恢复原选中账本；证据见阶段B状态。

预算与周期持久化恢复使用`PlanningProcessRecoveryTest`（须传`-e planningRecovery true`），依次单独运行`seedStoppedRulesAndBudget`、`resumeStoppedRulesAndReactivateThroughUi`、`verifyReactivatedFactsAfterAnotherProcessAndCleanup`，步骤间执行`am force-stop`。使用专用数据库和固定日期，验证三主题中日文的规则撤下/恢复、主动取消、付款事实、预算与稳定待办ID；最后一步清理专用库并恢复选中账本。此不验证未保存表单的进程恢复。

当前阶段B分析的未来视图展示已保存的周期待办、活动资金预留及未完成转账；按所选月份生成待办，不自动记账。金融风险预测、预计收入情景和账单核对仍未完成。阶段B APK已交付，完成结论与验证范围以[阶段B状态](verification/phase-b-status.md)为准。

## 阶段B操作验收

1. 记录USD原价与JPY账户预计金额，在详情确认实际入账；检查原预计保留、余额与原消费月同步更新。部分退款先留待到账，实际确认后再退剩余原币；USD100/JPY15000实际退14800时原月净消费剩200。
2. 从资金账户进入转账，选择同币或异币两端、手填汇率与明确费用，分别记录两端日期和实际状态；在途时收款余额不增加，确认到账后不再次扣转出余额。
3. 设置自然月与父子分类预算、周期规则；翻未来月份只生成待办，确认新消费或关联已有消费后解除对应预留。收入不自动增加预算。
4. 主动启用活动，设置期间/预算/月预算排除和资金预留；本人整单消费只记一次，别人付款仅形成活动内份额。结束释放剩余预留，历史仍可查看和修正。
5. 首次分析主动选择模块，再排序/固定关注；从父类、未细分、商户或同期变化下钻，核对日期范围和原消费/退款。保存商户地点或从朋友名册进入同一活动双方明细。
6. 系统多选普通附件与多张小票，保存后删除源文件，内部副本仍可打开；图片可缩放、音频可播放/拖动、其他格式交给支持该格式的外部应用。编辑移除/排序后取消应保留原资料，小票独立维护修订。

0.2.0构建、6项单元测试及lint通过；当前合并24项数据/迁移验证通过，各页面三主题中日文的标准、320dp大字及必要真实IME/进程恢复证据见阶段B状态。最终APK在API36模拟器覆盖安装与生产MainActivity冷启动通过，与阶段A证书一致；未连接用户实机。覆盖更新使用上方install -r，保留原开发包，不先卸载。

真实参考汇率服务、金融预测/还款、文件核对/导入导出、通知与定位、正式发行签名仍在阶段C–E范围，阶段B不会显示虚构成功结果。
