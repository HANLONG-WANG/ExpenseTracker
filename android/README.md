# 余量 Android · 阶段E 0.5.0

原生 Kotlin / Compose 本地记账应用。阶段E P21–P25的实现与本轮可执行验收完成，交付0.5.0/code5。用户明确选择本轮交付APK，用户实机检查保持未验。准确状态见[阶段E记录](verification/phase-e-status.md)与[UI01–UI27验收](../docs/phase-e-acceptance.md)。历史范围见[开发计划](../docs/plans/development-plan.md)、[阶段A](verification/phase-a-status.md)、[阶段B](verification/phase-b-status.md)、[阶段C](verification/phase-c-status.md)、[阶段D](verification/phase-d-status.md)。

## 安装包

- [正式APK 0.5.0](artifacts/yohaku-release-0.5.0.apk) · [SHA-256](artifacts/yohaku-release-0.5.0.apk.sha256)：包名`com.yohaku.expensetracker`，固定正式签名。
- [开发APK 0.5.0](artifacts/yohaku-dev-0.5.0.apk) · [SHA-256](artifacts/yohaku-dev-0.5.0.apk.sha256)：包名`com.yohaku.expensetracker.dev`，沿用阶段A–D开发证书。
- Android 10+（minSdk29）、target/compile SDK37、versionCode5、Room v29（新增云端交接表，迁移28→29）。三主题、中日文按账本保存。
- 开发包可覆盖原开发版本。正式包独立安装，与开发包并存，不自动转移开发包资料。覆盖升级不要先卸载；卸载或清除应用存储会删除账本与内部附件。无云端备份。
- 当前仅专用API36模拟器验证，用户实机未连接。精简实机检查、密钥位置和完整安装升级说明见[发行说明](RELEASE.md)。

```sh
adb devices
adb -s <serial> install -r artifacts/yohaku-dev-0.5.0.apk
adb -s <serial> install -r artifacts/yohaku-release-0.5.0.apk
```

历史交付：[D 0.4.0](artifacts/yohaku-phase-d-0.4.0.apk)、[C 0.3.0](artifacts/yohaku-phase-c-0.3.0.apk)、[B 0.2.0](artifacts/yohaku-phase-b-0.2.0.apk)、[A 0.1.0](artifacts/yohaku-phase-a-0.1.0.apk)。这些历史包不包含阶段E的真实通知/定位和完整资料管理。

## 功能范围

- 空白设置、独立账本、两级支出/收入分类、现金/银行/交通IC/信用卡/贷款账户；期初债务不重复计收入/消费。
- 收支、历史补录、原ID编辑/修订、按账本草稿及进程恢复。原币/账户币/统计币、预计与实际、部分/分次退款、同币转账/异币换汇、两端独立日期/在途及明确费用。
- 自然月/分类预算、周期待办、确认或关联已有记录、活动预算/资金预留、多人互请；分析模块排序、分类/商户/活动筛选与下钻。
- 用户配置的一次付/分期/リボ/固定贷款/循环贷款规则，生效版本、计息/费用/舍入/时区及逐期计划；缺项不假设零费用，特殊算法使用平台已知手动金额，无机构预设。
- 信用卡消费付款方式、自动リボ/余额池、总/子/共享额度；实际借还、部分/提前/跨币种付款、待平台清偿/未知拆分。纯试算不修改正式账，确认办理保留原账单和历史。
- 预计收入及实际到账/关联、按实际资金账户的逐日预测、基础/含预计收入两情景、预算与资金缺口分列、付款日历及重要事项。其他账户资金和信用额度不自动抵消扣款账户缺口。
- Frankfurter v2参考汇率、来源日期/持久缓存、自动开关/手动刷新/离线手填；请求仅含币种/日期，不上传账目，参考金额不改写实际账。
- 真实附件/小票多选、内部副本、图片预览、音频播放、外部只读打开；每个文件严格小于100 MB。
- 正式流水JSON整体校验/预览、一对一核对、独立人工差异、按类型正式补记；小票多图ZIP/原字节校验、商品JSON回填/替换/修订与商品编辑；导入不自动写账，无OCR/银行连接。
- 五类重要事项的真实通知、非精确AlarmManager调度、持久去重、修改撤销、重启/升级/时区变化重建；金额优先展示，点击进入所属账本和目标月份。用户保存开启时才申请通知权限，拒绝仍能记账。后台时机受Android及厂商省电策略影响。
- 主动前台单次定位、保存地点匹配、默认100米、精度/唯一候选控制自动填入；多个候选手选，手改/清空/旧记录优先。离开前台取消，不申请后台位置权限或记录轨迹。
- 云端分析交接：导入网页生成的连接配置（Keystore加密），把所选小票原图ZIP或银行资料交给私人分析台；中断后按同一份内部文件整份重传（无断点续传）、领取结果核验大小/SHA后走原有预览确认，回执独立重试。
- 整本归档/回收/恢复/永久清理；账户停用/启用/无引用删除；分类停用/改父级/明确迁移；活动删除影响与历史保留；记录关联组回收/恢复/永久清空及文件最后引用清理。

没有账户登录、云同步、备份或应用锁；云端交接只上传用户逐次选中的资料。未知金额保持未知，未确认清偿不假结清。产品包不含测试种子、评审入口或测试验证器；格式帮助中的正常/错误样例仅供明确导出后转换使用。

## 常用入口

- 设置→独立账本管理→归档与整本回收。归档保留未完提醒；回收取消相应通知，30天内恢复原ID/归档状态。没有正常账本时仍能查看保留资料。回收站不是备份，永久删除/清空不可撤销。
- 账户详情→账户状态与停用。停用保留历史与债务处理；有记录、计划、核对、预留或草稿引用时不能删除。
- 分类编辑→分类状态与归属管理。迁移需选择同用途目标和预算处理方式，确认页展示历史月份/活动预算影响，金额与原记录ID保持。
- 活动详情→查看删除影响。列出跨月回归日常后的预算，保留记录/资金/文件及原互请历史；停止该活动安排并释放未用预留。结束活动与删除活动为不同操作。
- 原记录→移至回收站。原消费与本次关联退款、转账两端按组处理；以前独立删除的退款不混入。恢复旧人工核对后须重新确认；独立合同、确认账单和实际还款不因清理来源消费而抹去。
- 分析→商户资料与保存地点。手填坐标或“使用当前位置”后仍须确认保存；记账页主动启用定位，拒绝/系统关闭仍可手填。
- 设置→重要提醒。保存开启才请求权限；系统不允许时可进入系统通知设置。

30天到期资格严格按时间判定；物理清理由启动及每日唯一WorkManager任务执行，后台可能延后。原文件仍被其他记录引用时保留。

## 文件工作流

1. 设置→银行/信用卡流水核对→导入标识说明→保存JSON。交给助手按[正式格式](../docs/formats/README.md)转换为`yohaku.statement` v1，名称不能代替UUID。
2. 系统选JSON→检查账户/日期语义/覆盖/币种/汇总/疑点→确认导入。逐笔匹配或明确差异；补记进入普通/金融表单，已有草稿必须继续或明确放弃。
3. 小票工作台多选原图组→导出ZIP→系统保存。将实际ZIP交给助手生成`yohaku.receipt-items` v1。
4. 导入商品明细→整体预览→确认回填。旧修订/已有明细须确认替换；新增原图仍待整理，商品合计不改原记账金额。
5. 格式帮助可保存说明、四份schema及样例ZIP。格式为UTF-8 JSON/version1，任一字段无效或不支持版本整份拒绝，显示字段路径。手动文件路径不联网；云端交接见下节。

## 云端分析交接

契约与限制见[交接包](../docs/android-cloud-handoff-package/)和[落地规格3.2](../docs/implementation-spec.md)。

1. 网站登录→设备连接→生成配置。设置→云端分析交接台→导入连接配置（粘贴或选文件），核对站点/设备ID后保存，自动测试`GET /api/v1/types`。
2. 小票工作台选未整理小票→交给云端整理；或导入流水页→让云端转换原始流水（选账户与资料）；或交接台→通用分析任务。资料先复制到内部目录，再由WorkManager串行上传、提交。
3. 交接台前台每30秒刷新进行中任务，也可手动刷新。待补资料可补充文件；结果可领取时下载核验后进入原有预览，保存后才发送imported回执。
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
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease
```

仪器测试需要实际兼容设备。真实权限/GPS、IME、跨进程、闹钟/重启、网络服务不能无前置地整类混跑，默认跳过不算通过。专项方法/参数见各测试源码和[阶段E记录](verification/phase-e-status.md)；历史进程/IME依据见A/B/C记录。独立`media-verifier`模块仅提供系统DocumentsProvider和跨UID只读验证，需单独安装，绝不打入产品APK，完成后卸载`com.yohaku.verification`。

专用模拟器`emulator-5580`：标准1170×2532/density480/fontScale1（390dp），窄屏960×2078/density480/fontScale1.3（320dp）。测试后恢复原尺寸/字体/网络/权限等配置。字体许可及数值字形适配见[字体迁移记录](verification/font-migration.md)。
