# 阶段E最终验收

验证环境：专用API36模拟器，标准390dp/fontScale1与320dp/fontScale1.3。三主题分别为magazine、journal、objects，语言为中文与日语。用户手机未连接；用户明确选择本轮交付APK、实机项保持未验。模拟器结论不等同于实机结论。

## UI01–UI27覆盖索引

下表整理各阶段最终审查和阶段E新增状态。沿用的冻结稿对照没有因汇总而重新宣称逐像素复测；字体数值适配、正式业务扩展和系统安全区差异见各视觉记录。历史过程日志中的未验事项，以对应文件开头或末尾的最终审查结论为准。

| UI | 三主题/中日文覆盖与必要状态 | 证据 |
| --- | --- | --- |
| 01 | 空白设置、账户/已有债务、跳过、长文、真实键盘、进程恢复 | A：SetupFlow/Presentation、ProcessRecovery |
| 02 | 品牌、底部导航、账本切换、未保存、返回、系统栏 | A：Shell/ThemeControls/SystemWindow；E11库页 |
| 03 | 预算/预留/日均、资金、已知/未知安排、低样本 | B：HomePlanning；C14/30 |
| 04 | 支出/收入两级树、搜索/最近/新建、编辑、预算 | A：Entry/DraftEditing；B：category系列；E12分类管理 |
| 05 | 原币/账户币、编辑/草稿、附件、定位全部状态、手填优先 | A/B：Entry/DraftEditing；E02/11定位 |
| 06 | 筛选/详情、原ID修改/修订、退款历史、回收关联预览 | A：Record；B：refund-history；E06/12 |
| 07 | 部分/分次/待到账/实际到账、原消费月、取消预览 | B：RefundFlow、refund-history-rows标准/窄屏 |
| 08 | 同币/异币、三种费用、两端日期/在途、全宽日历、草稿 | B：TransferFlow及真实进程恢复；D实际确认 |
| 09 | 模块排序、分类/商户下钻、时间/活动筛选、趋势/预算 | B：analysis系列标准/窄屏、预算最终审查 |
| 10 | 基础/预计收入、账户缺口、未知、实际超支、处理入口 | C27/30：FinancialOutlook/ProductionOverview |
| 11 | 活动预算/跨月/预留/参与者/结束；删除影响及历史 | B：ActivityFlow；E05/10/12 |
| 12 | 金额/比例互请、朋友历史、别人付款、停用保留 | B：friend-actions/directory/history；E10 |
| 13 | 商户原文/地点、候选/定位中/拒绝/无候选、停用 | B：MerchantFlow；E02/10/11 |
| 14 | 五类账户、原币资金/债务、快捷转账、期初/停用 | A/B账户；C26/31；E04/12 |
| 15 | 信用卡账单/余额池、逐期、确认/预计、总/子额度 | C25–29/31三主题中日文标准/窄屏 |
| 16 | 五模式规则、版本/生效、缺项/不支持/手动、验算 | C25/28：规则字段、来源与手动替代 |
| 17 | 原新逐期比较、费用/压力、试算/办理、历史保留 | C29及池安排系列；纯试算不发生资金 |
| 18 | 固定/循环贷款、借入/净到账/费用、已有债务/逐期 | C26/28/31及FinancialLedger/Prediction |
| 19 | 部分/提前/跨币还款、未清偿/未知拆分、实际日期 | C3–24/31：真实表单、IME、连续办理 |
| 20 | 周/月/年周期、预计收入、确认/关联、付款日历 | B预算/周期与进程恢复；C14/30/32 |
| 21 | 标识→系统文件→整体校验→核对/差异→正式补记 | D：StatementFileWorkflow标准/窄屏与真实系统交接 |
| 22 | 多选、内部副本、图片/音频/外部只读、失败 | B：MediaFlow与独立UID验证器；D原文件另存 |
| 23 | 多图ZIP、JSON校验/回填、替换/修订、商品编辑 | D：ReceiptFileWorkflow标准/窄屏及字节校验 |
| 24 | 五类事项、空/非空、权限/计划、真实通知全部目标 | C32；E07–09/11：标准/窄屏及实际系统卡片 |
| 25 | 设置、偏好/独立账本、格式帮助、归档/恢复/无正常账本 | A/B设置；D格式ZIP；E03/10/11 |
| 26 | 原ID删除/恢复、影响预览、管理/迁移/永久清理 | E03–12：存储/系统/冷启动/30种弹层锚点 |
| 27 | 独立主题控件/菜单/弹层、全宽日历、空/失败/未授权/未保存 | A通用控件与IME；B/C/D实际流程；E09/11/12 |

详细证据：[阶段A](../android/verification/phase-a-status.md)、[阶段B](../android/verification/phase-b-status.md)、[阶段C](../android/verification/phase-c-status.md)、[阶段D](../android/verification/phase-d-status.md)、[阶段E](../android/verification/phase-e-status.md)。原稿及实现位置见[UI实现对照](ui-implementation-map.md)。

## T01–T17业务与持久化索引

这些是固定输入与独立预期值的已有代表用例；最终运行结果另列，不把静态索引当作本轮已通过。

| 用例 | 验证位置与判断 |
| --- | --- |
| T01 | LedgerPersistence、ActivityTreat、独立偏好/金融/核对测试；A/B进程恢复及E10三次真实冷启动，无跨账本事实。 |
| T02 | LedgerPersistence、DraftEditing、EntryLifecycleStorage/Flow：原ID、一次Posting、取消不写、关联组恢复与附件。 |
| T03 | CurrencyTransactions与EntryFlow：原币/预计/实际分离，原ID实际确认；D正式流水交接。 |
| T04 | CurrencyTransactions、EntryFlow退款链：15000消费/14800退款→原月净额200；B最终退款历史。 |
| T05 | BudgetRecurrence、HomePlanning、BudgetPace：自然月余量/预留、独立活动和确认后不重复；B最终预算审查。 |
| T06 | TransferTransactions/Flow：异币两端/独立日期、在途、三种费用位置；B实际草稿跨进程。 |
| T07 | ActivityTreat/Flow：6000三人、10000×30%/6000×50%；别人付款无本人资金Posting。 |
| T08 | FinanceCalculation与FinancialPrediction：3333/3333/3334本金、用户明确的费用与尾差。 |
| T09 | FinanceCalculation与PredictedSchedule：明确18%/30天/365日规则→1479/11479/90000，不作机构默认。 |
| T10 | FinanceCalculation与余额池实际账务：100000×15天+80000×15天、约定端点及舍去→1331。 |
| T11 | FinancialLedger/Storage、FinancialReconciliation系列：10000/9700/300，部分/提前实际付款、旧确认保留。 |
| T12 | CashTimeline、FinancialOutlook、ExpectedIncome：账户独立缺口、收入两情景、信用额度不当现金。 |
| T13 | StatementReviews/Storage、StatementFileWorkflow：同日同额唯一占用、部分区间/单侧、不因导入写账。 |
| T14 | FileWorkflowStorage、ReceiptFileWorkflow：原图ZIP/ID/校验值、旧修订替换、重复与差额，不改原账。 |
| T15 | MediaStorage/Flow与独立UID验证器：原源消失后内部副本、音频/外部只读、取消与严格100MB限制。 |
| T16 | E07/08 ReminderSystem/SystemHandoff：实际闹钟、权限、修改取消、重启/升级重建及全部真实卡片目标。用户手机未验。 |
| T17 | DatabaseUpgrade及各域实际旧schema迁移；A/B/E10真实进程终止后ID/文件/草稿保留。本轮真实D→E升级及独立正式RC→最终递增升级，旧列/原ID/余额/文件字节保留，外键完整。 |

## 最终执行结果

- 本轮用户明确选择交付APK，手机项目保持未验。API36专用模拟器完成可执行验收；P21–P25据此收口，不宣称用户手机或所有Android版本/厂商已验。
- 300个普通仪器方法最终通过：首轮324条（283通过/17失败/24显式跳过），修正后17→5→2逐步定向复验关闭全部失败；没有重跑已通过的全部业务。另两条真实IME方法在320dp/字体1.3、各三主题中日文通过，33.646秒；本轮共302个不同方法有效通过。
- 保留22个显式外部步骤的既有证据：SystemHandoff六阶段E07/08；管理恢复三阶段E10；A、B、预算各三阶段真实进程恢复；C12金融草稿两次真实进程；C8真实HTTPS；E08通知权限；E11权限状态。普通回归排除的ForegroundLocationSystemTest、ReminderSystemTest、LocationEntryFlowTest沿用E02–E11分别设置权限/GPS/实际通知/受控状态的证据。默认跳过和排除均未算作新通过。
- 三主题与双语言的标准/窄屏覆盖依据见上方UI矩阵。新增五页旧锚点复验共864个位置/高度比较：主页最大1.211dp（整页位置2dp/控件高度1dp），账户、账户详情、账本、记账分别最大0.889/0.889/0.495/0.162dp。明确扣除正式新增控件区域，CSV为归一化旧锚点，不称新整页原始像素完全一致。P23正式弹层30种状态最大0.865dp。
- 51项JVM零失败/跳过；Debug/Release Lint零错误、66/64警告；最终开发/正式/测试包构建、相关变更复验与文档差异检查通过。实测离线期间系统无默认网络，核心账务、预算/活动、金融与真实文件链路通过。
- 实际开发0.4.0/code4→0.5.0/code5、Room25→28；实际固定正式签名RC code4→最终code5。原旧列、原ID、500消费/9500余额、附件原字节和外键完整。正式RC为本轮E候选，未冒充历史正式发行。开发原资料恢复；最终正式APK另行全新安装、生产入口空白启动，六类产品表均0。
- 最终正式包不可调试/无testOnly/禁用备份；无测试DEX、验证器、HTML或初始化样例账目。格式帮助样例是用户主动保存的协议资料，不是产品种子。

[本轮完整结果与各次原始日志](../android/verification/phase-e-status.md)、[结构化测试汇总](../android/verification/phase-e-final-results.json)、[实际升级与空白安装](../android/verification/phase-e-upgrade/)、[五页布局比较](../android/verification/phase-e-final-page-layouts/comparison-summary.json)、[窄屏真实键盘](../android/verification/phase-e-final-narrow-ime/)。

## APK与保留未验

| 包 | 版本与安装方式 | 产物 |
| --- | --- | --- |
| 正式 | 0.5.0/code5，独立包名com.yohaku.expensetracker，固定正式证书，空白安装 | [APK](../android/artifacts/yohaku-release-0.5.0.apk) · [SHA-256](../android/artifacts/yohaku-release-0.5.0.apk.sha256) |
| 开发 | 0.5.0/code5，com.yohaku.expensetracker.dev，覆盖阶段A–D开发包 | [APK](../android/artifacts/yohaku-dev-0.5.0.apk) · [SHA-256](../android/artifacts/yohaku-dev-0.5.0.apk.sha256) |

- [ ] 用户手机安装/覆盖与自己的账本、附件验证。
- [ ] 用户手机通知允许/拒绝、金额、点击、重启及厂商后台策略。
- [ ] 用户实际环境GPS精度、权限/位置关闭及保存地点匹配。

这些项目按用户选择保持未验。系统通知使用非精确调度，后台/强制停止受Android限制；应用内事项与手工记账仍可使用。安装、签名密钥持久保存、升级与卸载行为及精简手机步骤见[发行说明](../android/RELEASE.md)。
