# ExpenseTracker 发布前完整 UI 审查与软件测试记录

## 文档约定

- `SRC-*`：阶段 1（纯源码与契约对照）发现；本阶段只记录，不修复。
- `RUN-*`：阶段 2（仅 agent-device 黑盒测试）发现。
- `REG-*`：阶段 4（仅 agent-device 修复后回归）新发现；按要求只记录，不修复。
- 严重度：`P0` 无法继续/数据或安全灾难；`P1` 发布阻断；`P2` 明显功能或体验缺陷；`P3` 轻微一致性问题。
- 状态：阶段 1/2 新问题统一先记为 `待修复`；阶段 3 的完成情况单独记录在 `FullTestRepairProgress.md`。

每条问题都同时写明“项目现状”和“正确需求”，使修复者无需重新通读四份 UI 契约即可确定修改方向。契约基线：

1. `Android记账软件_UI设计系统与实现契约_v1.0.md`
2. `android_ledger_ui_tokens_v1.json`
3. `android_ledger_screen_contract_v1.yaml`（215 个页面/运行态）
4. `UI需求追踪矩阵_v1.csv`（REQ-001—REQ-090）

## 阶段 1：纯源码 UI 契约审查

状态：已完成。审查范围包含应用外壳、设计系统、全部 feature UI、附件/位置、迁移流程、小组件、三语资源、无障碍/隐私语义与 UI 测试覆盖；本阶段未使用 agent-device、未修复生产问题。

覆盖结论：已逐项覆盖合同中的 215 个 screen、646 个 required state、90 条追踪需求和 JSON 的 434 个标量 token；同时复核 top-level/全屏/sheet/dialog/bottomSheet presentation、全部生产 destination 接线和所有 feature/Glance 的可见文本与交互边界。`validate_p04_ui.py` 通过（434 token、215 route、646 state、23 阶段需求），`git diff --check` 通过。P34 唯一真实失败对应 `SRC-001`；257 项合同/mutation 测试唯一失败对应 `SRC-002`。其余过期阶段校验器的问题集中记录为 `SRC-017`。本节共记录 22 条待修复问题。

### 发现清单

#### SRC-001｜P1｜贷款实际还款选择的计划期次未写入账本

- 状态：待修复
- 覆盖：`REC-019` 贷款还款、`LOA-009` 贷款还款详情；需求章节 12.18.5，追踪需求 REQ-041。
- 项目现状：`LoanPayment` 会展示计划期次选项，`LoanFeatureState.selectedScheduleInstallmentNumber` 也会随用户选择更新，并且未选择时保存校验失败；但 `AppRootViewModel.submitLoanPayment()` 构造 `LoanComponentAllocationDraft` 时将 `installmentNumber` 固定传为 `null`。因此用户明确选择的期次仅停留在 UI 状态，保存后被静默丢弃；仓库自带的 P34 UI 闭环校验也因此失败（缺少生产写入标记）。
- 正确需求：实际还款表单必须允许将本金、利息、手续费、罚息分配关联到用户选定的计划期次；保存请求中的每条相应 `LoanComponentAllocationDraft` 都必须携带 `selectedScheduleInstallmentNumber`，保存后的 `LOA-009` 详情应显示相同期次分配。合计仍必须等于付款账户支出，本金不得超过剩余本金，不得用无提示的 `null` 降级为“未指定期次”。
- 风险：UI 与持久化结果不一致，会破坏计划/实际差异、还款详情和后续贷款计算，属于发布阻断的数据正确性问题。
- 证据：`feature/liabilities/.../LoanScreens.kt` 的期次选择行；`app/.../AppRootViewModel.kt` 中 `submitLoanPayment()` 将 `LoanComponentAllocationDraft(..., null, ...)` 写死。

#### SRC-002｜P2｜流水详情合同 mutation 测试仍依赖已废弃资源键，导致全量合同套件假失败

- 状态：待修复
- 覆盖：`JRN-007` 交易详情；测试契约章节 16.3、16.6。
- 项目现状：生产 `DetailScreen` 已通过当前资源 `p15_journal_edit` 与 `p15_journal_refund_action` 展示并接线“编辑交易”和“创建退款”；但 `test_p15_journal_contracts.py::test_detail_must_keep_edit_and_refund_actions` 仍尝试替换旧键 `p15_journal_create_refund`。该键只存在于资源文件、已不在生产 Composable 中，因此测试在 mutation 前的 `assertIn` 即失败，全量 `scripts/tests/test_*_contracts.py` 结果为 257 项中 1 项失败。
- 正确需求：发布门禁应针对当前生产实现验证 `JRN-007` 同时保留并调用编辑与退款动作，例如分别 mutation `actions.onEdit(...)`、`actions.onRefund(...)` 或当前资源键；测试本身必须先在未变异仓库通过，再证明移除任一关键动作会失败。不得依赖已经被替换的旧文案键产生假红灯。
- 风险：CI/发布前验收无法得到可信的全绿基线；真正的动作回归也可能因测试只盯旧字符串而漏检。
- 证据：`scripts/tests/test_p15_journal_contracts.py` 的旧 marker；`feature/journal/.../JournalDestination.kt` 当前 `DetailScreen` 的真实编辑/退款按钮。

#### SRC-003｜P1｜多个实体选择字段以“点击后循环下一个值”代替契约选择器

- 状态：待修复
- 覆盖：共享 `SelectorField` 策略；`REC-015`、`REC-024`、`BUD-005`，并影响退款、批量录入和预算调整的正确数据选择。对应契约 8.17、9.4，REQ-034、REQ-049、REQ-060。
- 项目现状：以下可见选择字段点击后直接在 ViewModel/Controller 中调用 `selectNext*`、`nextCategory` 或 `cycleRow`，没有显示候选列表、当前选择上下文或搜索：
  - `REC-015`：收款账户、实体卡、分类、商户、项目、目标分别调用 `selectNextRefundAccount/Card/Category/Merchant/Project/Goal()`；
  - `BUD-005`：预算调整来源与目标分类调用 `selectNextAdjustmentSource/Target()`；
  - `REC-024`：互请活动、位置、分期计划和退款原交易调用 `BatchEntryController.cycleRow()`，页面只显示泛化“已关联/未关联”，无法知道下一次点击会选中谁。批量行的分类/账户/卡/商户/项目虽已有可搜索选择器，不在此缺陷范围；
  - `REC-013/020/021/022`：专项交易的转出与转入账户调用 `SpecializedTransactionPolicy.selectAccount()`，每点一次直接改到下一个活跃账户；
  - `IMP-003/004`：导入字段映射与源实体值映射的“更改/循环”按钮分别调用 `cycleImportFieldMapping()` 与 `cycleImportEntityMapping()`，不展示可选目标。
- 正确需求：实体选择不得以无界循环代替选择界面。2–8 个固定单选且无需搜索时使用底部面板或分段按钮；可能超过 8 个、需要搜索或可创建的实体使用全屏可搜索选择页；分类始终使用完整分类网格；可清空字段提供明确“无”。选择项必须显示图标/名称/必要状态，退款原交易还需显示可退金额和相关上下文，批量行复杂关系需让用户明确选择目标而不是猜测点击次数。
- 风险：候选较多时用户无法到达或确认目标，点击一次就静默改变财务关系，极易造成错误账户、分类、预算迁移、退款归属、分期或互请数据，属于发布阻断的交互与数据正确性问题。
- 证据：`RefundRootDestination.kt` 的六个 `selectNextRefund*`；`BudgetState.kt` 的 `selectNextAdjustment*`；`BatchRecordScreens.kt`/`BatchEntryController.kt` 的 `CycleReference`/`cycleRow`；`SpecializedTransactionState.kt::selectAccount()`；`ImportWizardScreen.kt` 与 `ImportController.kt` 的两类 cycle mapping。

#### SRC-004｜P1｜信用账户账单时区被限制为写死的 6 个地区

- 状态：待修复
- 覆盖：`CRD-002` 信用账户设置、信用账单归属；契约 8.18、9.4、12.16，REQ-030、REQ-036、REQ-082。
- 项目现状：`CreditZoneChooser` 只搜索 `Asia/Tokyo`、`UTC`、`Europe/London`、`America/New_York`、`America/Los_Angeles`、`Australia/Sydney` 六项，且使用底部面板；`AppRootViewModel.selectCreditZone()` 同样拒绝该硬编码集合之外的值。与此同时，首次启动与全局语言地区设置已使用 `ZoneId.getAvailableZoneIds()`，说明信用模块的限制不是平台能力限制。
- 正确需求：时区必须使用全屏可搜索列表，候选来自系统完整、稳定排序的 IANA `ZoneId` 集合，显示 ID 与本地化名称；必须能保存任意有效时区。信用账单归属时区与交易发生时区是两个独立字段，不能因候选白名单过小而被迫使用错误地区。
- 风险：六个候选之外的用户无法正确配置账单日/到期日边界，可能造成跨日交易账单归属、到期判断和信用分析错误。
- 证据：`CreditScreens.kt` 与 `AppRootViewModel.kt` 中重复定义的 `CREDIT_SUPPORTED_ZONES` 六项列表及白名单校验。

#### SRC-005｜P1｜多处日期选择器的空值默认日取自设备时区而非账本时区

- 状态：待修复
- 覆盖：`ACC-004`、`ACC-007`、信用设置/还款、`REC-027`、`INS-005`、贷款日期、周期日期、互请日期；契约 8.18、10.3、13.6，REQ-030、REQ-071、REQ-082。
- 项目现状：账户、信用、分期、贷款、自动化和互请的多个 picker helper 在草稿日期为空或解析失败时直接调用 `LocalDate.now()`；该重载使用设备默认时区。应用明明已通过 `LedgerTheme.timeZone` 提供账本时区，但这些回退没有使用它。典型位置包括 `AccountsScreens.kt`、`CreditScreens.kt`、`InstallmentScreens.kt`、`LoanScreens.kt`、`AutomationScreens.kt`、`SettlementScreens.kt`。
- 正确需求：所有财务日期默认值、月份边界与 Material 日期选择器初始日必须从账本时区计算，例如 `LocalDate.now(LedgerTheme.timeZone)`（或由注入的账本 Clock/当前草稿提供），设备时区只可用于首次建议且不能覆盖账本设置。选择结果仍应按日期而非瞬时时间保存，避免 UTC 转换造成跨日。
- 风险：设备时区与账本时区不同时，午夜附近打开空日期字段会默认到前一天或后一天，进而错误影响账单归属、预算月份、计划期次、检查点和统计口径。
- 证据：生产 UI 中 9 处无参数 `LocalDate.now()`；这些模块同时已经可以访问中央账本时区。

#### SRC-006｜P2｜交易详情强制显示空区块并以硬编码破折号占位

- 状态：待修复
- 覆盖：`JRN-007` 交易详情；契约 12.8、13.6，REQ-066。
- 项目现状：`DetailScreen` 无条件添加“用户录入”“位置”“账户影响”“预算和统计”“汇率证据”“关系”等区块；`DetailSection` 在值列表为空时渲染硬编码 `"—"`。因此与当前交易无关的区块仍占据页面空间，附件为空时也显示相同占位符。该字符还绕过三语资源。
- 正确需求：与交易有关但为空的区块必须隐藏；只有确有值时才显示对应 Section，并提供可到达的详情/动作。若某个必须保留的区块确需空态，应使用本地化、可解释的“暂无……”文案，而不是没有语义的硬编码破折号。已移入回收站时继续按契约隐藏不再生效的账户影响。
- 风险：简单交易详情被大量无意义空卡片拉长，用户无法区分“确实无数据”与“加载/实现缺失”，三语和 TalkBack 语义也不完整。
- 证据：`JournalDestination.kt::DetailScreen` 的无条件 `item { DetailSection(...) }` 以及 `DetailSection`/附件区的 `LedgerText("—")`。

#### SRC-007｜P1｜流水详情金额格式化失败时直接暴露最小单位整数

- 状态：待修复
- 覆盖：`JRN-007` 交易详情的账户影响、修订记录与金额显示；契约 10.1、10.4、13.6，REQ-005、REQ-066。
- 项目现状：`JournalDestination.kt::formattedMoney()` 正常时使用 `LocaleCurrencyFormatter`，但格式化失败后退回到 `"${currency.value} $minor"`。这会把应用内部的最小单位值当成主单位文本展示，例如 12345 分变成“CNY 12345”，且不符合地区数字与货币格式。该 helper 被账户影响和修订记录路径共用。
- 正确需求：所有可见金额必须按货币小数位和当前 Locale 格式化，并带币种或符号；格式化失败时必须显示本地化的“金额不可用”类安全占位，不得展示 raw minor value。数值转换失败也不得伪装成有效金额。
- 风险：罕见货币目录或数据异常会将金额放大 10–1000 倍显示，使用户对账和修订历史产生错误判断。
- 证据：`feature/journal/.../JournalDestination.kt` 的 `formattedMoney()` failure branch，及其在 `localizedAccountEffect()` 和修订字段本地化中的调用。

#### SRC-008｜P2｜可增长且需搜索的大候选集普遍使用底部面板或表单内联展开

- 状态：待修复
- 覆盖：共享 `SelectorField` 选择策略，影响 `ACC-003`、`CRD-002/007`、`JRN-006`、`PRJ-002`、`GOL-002`、`AUT-005`、`CAT-002`、`PLC-002`、`SETG-003` 等；契约 8.17、9.4、13.1。
- 项目现状：以下候选数可轻易超过 8 且已经需要搜索的选择器，仍被放在最高 520dp 的 `LedgerBottomSheet` 中：账户法定币种、设置时区、分类默认账户/卡/商户/父类、地点可选商户、周期的固定地点、信用还款账户/账单、流水批量编辑选项。更多页面则直接把整个动态候选集展开在主表单：项目的关联目标、目标的关联账户、互请活动的项目/付款人/收款人/本机账户、贷款合同/分项/收付款账户、分期购买交易、周期模板，以及快捷模板编辑器中的分类/主次账户/卡/商户/项目/目标/互请/地点。分类处理目标、商户合并的来源/目标、地点合并目标也会把全部实体铺在风险操作页。候选数增长时会把主表单无限拉长，且没有统一搜索/创建路径。
- 正确需求：只有 2–8 个、无需搜索的固定单选才可用底部面板或分段按钮；候选可超过 8、需要搜索或允许新建时，必须进入契约化的全屏搜索选择页，包含标题、自动获焦搜索、当前选中、兼容性/状态说明、空态与可选“无”/新建动作。大字号和 320dp 宽度下不得用固定高度底部面板承载大列表。
- 风险：数据量增长后查找和确认目标困难，键盘、大字号与底部操作会进一步压缩可见行，形成多个页面的一致性和无障碍问题。
- 证据：`AccountsScreens.kt`、`CreditScreens.kt`、`InstallmentScreens.kt`、`LoanScreens.kt`、`P33SettingsScreens.kt`、`ReferenceManagementScreens.kt`、`AutomationScreens.kt`、`SettlementScreens.kt`、`JournalDestination.kt`、`ProjectGoalScreens.kt` 中的可搜索 sheet/表单内联 choices。

#### SRC-009｜P2｜多个固定枚举字段点击后直接跳到下一选项

- 状态：待修复
- 覆盖：共享选择器策略，影响 `LOA-004/005/008`、`INS-002`、`CRD-002`、`JRN-006/012`、`AUT-001/006`、`ANA-004/007/013`、`ANA-011`；契约 8.17、9.4、13.2。
- 项目现状：多个 `SelectorField` 的点击动作不打开选项，而是立即改变当前值：贷款计划修订、利率类型、还款频率、提前还款策略与舍入方式；分期舍入方式；信用到期规则；批量编辑统计性质/是否计入预算；自动化排序与规则；分析度量、维度、粒度、对比、排序、异常类型；消费地图模式、权重、聚合、呈现和各筛选维度。用户无法在操作前知道有哪些选项，也无法一次到达目标。
- 正确需求：2–3 个高频固定选项使用分段按钮或并列 `ChoiceRow`；4–8 个单选使用显示全部候选的底部面板。当前值必须明确选中，选择只能在用户点击具体候选后发生；不得将“点击字段”解释为隐式 next/cycle。对影响财务计算的到期、利率、舍入和统计口径应附必要说明。
- 风险：隐式循环使得选项集不可发现，误触即改值，TalkBack 用户也听不到可选集和位置；会直接影响利息、还款计划、预算及统计结果。
- 证据：`LoanScreens.kt` 的 `nextFrequency/nextPrepaymentPolicy/nextLoanRounding`；`InstallmentScreens.kt::nextRoundingMode`；`JournalDestination.kt` 的 index cycle；`AnalysisState.kt`、`AnalysisController.kt`、`ConsumptionMapController.kt` 与 `P27AnalysisScreens.kt` 的全系列 `onCycle*`。

#### SRC-010｜P2｜用户选择的全局日期格式几乎没有应用到业务页面

- 状态：待修复
- 覆盖：`SETG-003/005` 语言、地区与日历设置及全应用所有日期显示，包含 `WGT-002`；契约 10.3、13.6，REQ-082。
- 项目现状：设置页可保存 `LOCALE_DEFAULT / YEAR_MONTH_DAY / DAY_MONTH_YEAR / MONTH_DAY_YEAR`，但该值仅被设置预览和少量 ViewModel 文本使用。账户、信用/分期/贷款、预算/项目/目标、退款/专项交易/批量录入、互请、自动化、分析、流水、引用管理等页面各自直接调用 `DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)`，无法读到用户覆盖值。桌面小组件的信用到期日更是固定生成 `yyyy-MM-dd`。
- 正确需求：应将保存的日期格式作为与 Locale、账本时区同级的中央 presentation context，所有可见日期、日期区间、筛选摘要、图表/表格、历史卡片、表单选中值和小组件统一使用它。时间部分继续用 locale API，TalkBack 使用完整可读格式；不改变底层 canonical 日期值。
- 风险：设置页表面上可修改但业务界面无反应，容易在日/月顺序上误读交易、到期日和还款计划。
- 证据：`AppRootViewModel.kt::formatSettingsDate()` 的局部使用，与各 feature `*Screens.kt` 中数十处独立 `localized()` helper；`LedgerGlanceWidget.kt::toDisplayDate()` 的固定 ISO 输出。

#### SRC-011｜P2｜桌面小组件的数字、日期与金额失败边界未纳入中央格式化规则

- 状态：待修复
- 覆盖：`WGT-002` 配置预览及九类 Glance 小组件；契约 10.1、10.3、10.4、12.31、13.6、15.11，REQ-072、REQ-082。
- 项目现状：Glance 目标进度通过整数拼接 ASCII `"%"`，不按 Locale 格式化；信用到期日固定为 ISO；小组件本体 `formattedAmount()` 直接调用 `Currency.getInstance()` 且无失败保护，异常币种会使组件渲染失败。配置活动的 `previewAmount()` 遇到格式化失败又会把 raw minor 整数当成金额显示，造成“配置预览有值，真实组件崩溃”的不一致。
- 正确需求：配置预览与 Glance 本体必须共用经验证的币种元数据、Locale 金额/百分比和用户日期格式；隐藏时仍显示固定隐私占位，格式失败时显示本地化不可用状态，不崩溃也不暴露最小单位整数。
- 风险：用户可见到错误进度/日期/金额，罕见货币或损坏快照还可使桌面组件无法更新。
- 证据：`widget/.../LedgerGlanceWidget.kt` 的 `goalProgress()`、`toDisplayDate()`、`formattedAmount()`；`WidgetConfigurationActivity.kt::previewAmount()` 的 raw integer fallback。

#### SRC-012｜P2｜一周起始日设置只被保存，没有任何业务日历消费它

- 状态：待修复
- 覆盖：`SETG-005` 日历设置及所有周视图/周期日期控件；契约 13.6，REQ-082。
- 项目现状：`SettingsWeekStart` 提供“跟随地区/周一/周日”，`AppRootViewModel.updateSettingsWeekStart()` 也会持久化到 proto；但全库对 `weekStart`/`SettingsWeekStart` 的引用只存在设置状态映射和选项页，没有任何日历网格、周期边界、预算/报表周维度或日期选择流程读取该值。`SETG-005` 虽有 `Preview` 卡片，但其中只显示由日期格式生成的单个日期字符串；切换周一/周日不会改变该预览，用户无法确认周起始设置的含义或效果。
- 正确需求：一周起始日与日期格式应进入中央日历 presentation context；`LOCALE_DEFAULT` 依 Locale 解析，明确的周一/周日覆盖应作用于日历控件列顺序、周标题与任何以周为单位的 UI 边界，但不得改写账本中的交易日期。`SETG-005` 的 Preview 应同时让日期格式与周起始日的效果可见，例如使用随选择变化的一周标题/小日历。
- 风险：用户的明确地区覆盖完全无效，日历视图和周报表可以与用户习惯错一整周的起点。
- 证据：全库搜索 `SettingsWeekStart`/`weekStart` 只命中 `P33SettingsScreens.kt`、`AppRootViewModel.kt` 和设置 action 分发，无任何业务 UI 使用点。

#### SRC-013｜P1｜“金额默认隐藏/全局隐藏”只对标准金额组件生效，大量文本和表格仍泄露实际金额

- 状态：待修复
- 覆盖：全局金额可见性，尤其 `ACC-001/005`、`BUD-*`、`PRJ-*`、`GOL-*`、`JRN-007/008/009`、`REC-015`、`CRD-*`、`INS-*`、`LOA-*`、`SET-*`、`ANA-*`；契约 4.2、8.8、11.4、13.2、13.7，REQ-082 及隐私规则。
- 项目现状：外观设置会把 `defaultAmountsHidden` 提供给全局 `LocalLedgerAmountsVisible`，账户首页顶栏也可切换该值。`AmountText`、`MoneyStack` 和使用 `MoneyUiModel` 的 `MetricCard` 会隐藏，但大量页面先取 `.formatted` 字符串再传给 `LedgerText`、`LedgerBanner`、`stringResource` 或 `AccessibleTableUiModel`，绕过了全局可见性。可确认的泄露包括：退款原金额/已退/剩余，预算超额、版本和模板金额，项目/目标公式与可访问表，互请头寸/建议/历史，信用账单、分期摘要，贷款计划/模拟/账户影响，流水详情及分析图表和数据表。
- 正确需求：所有业务金额都必须保持类型化 `MoneyUiModel` 到最终渲染边界，或在中央格式化器中同时消费可见性。隐藏时视觉统一显示等宽 `••••`，TalkBack 只读“金额已隐藏”；实际值不得出现在语义树、表格单元、图表 series/marker/summary、比较文本或 content description 中。隐藏只改变展示，不改数据计算。
- 风险：这是用户明确启用隐私选项后仍可直接看到财务数字的隐私破坏；部分卡片隐藏、部分正文泄露还会使用户误以为保护已生效。
- 证据：`ReadyRootScaffold.kt` 的全局 `LocalLedgerAmountsVisible`；`RefundScreens.kt`、`BudgetScreens.kt`、`ProjectGoalScreens.kt`、`SettlementScreens.kt`、`CreditScreens.kt`、`InstallmentScreens.kt`、`LoanScreens.kt`、`JournalDestination.kt`、`AnalysisScreens.kt` 中直接渲染 `.formatted`/金额字符串的路径。

#### SRC-014｜P2｜币种“可见/隐藏和排序”偏好仅在设置页内生效

- 状态：待修复
- 覆盖：`SETG-004` 币种设置与所有币种选择器，尤其 `ACC-003`；契约 `SETG-004` 的 `VisibleCurrencyList/HiddenCurrencyList/ReorderAction`、8.17、9.4、13.6。
- 项目现状：`CurrencySettingsPolicy` 会维护并持久化 `visibleCurrencyCodes`，设置页也正确区分可见/隐藏与顺序；但该列表在库中唯一的生产消费者仍是 `CurrencySettingsPolicy.create()`。账户币种选择器和其他候选数据继续直接使用完整法定币种目录，不优先可见列表、不按用户顺序排列，隐藏一项也不会改变任何业务 UI。
- 正确需求：本位币与已有账户币种始终强制可见；其余币种的可见性和排序应作为所有币种选择器的默认候选顺序。隐藏币种不得破坏旧数据显示，仍应通过搜索“显示更多”可选，但不应继续混在默认主列表中。
- 风险：用户完成整理后选择器仍显示数百个币种且顺序不变，使设置成为无效功能。
- 证据：全库 `visibleCurrencyCodes` 只在 `AppRootViewModel.updateCurrencySettings()`/持久化和 `CurrencySettingsScreen.kt` 中出现；`AccountsScreens.kt` 仍使用独立 `supportedCurrencies` 完整列表。

#### SRC-015｜P1｜回收站保留期设置未接入移入回收站和自动清理

- 状态：待修复
- 覆盖：`SETG-008`、`JRN-011`、`JRN-012`；契约 9.6、12.9 以及屏幕合同中的 `RetentionSelector/AutoPurgeSummary/TrashRetentionBanner`。
- 项目现状：设置页可选 7/30/90 天、从不或自定义并将 `trashRetentionDays` 持久化；但 `moveJournalToTrash()` 无条件使用常量 `JOURNAL_RETENTION_SECONDS = 30 天` 生成 `purgeAfter`，从不读设置。库中也只有用户手动进入 `JRN-012` 后调用的 purge 路径，没有根据保留规则执行的自动清理 Worker/调度器。因此 7/90/自定义/从不都不改变新删除交易的清理日期，页面所称“预计自动清理”也不会发生。
- 正确需求：移入回收站时必须在同一时间源下读取当前保留策略并写入一致 `purgeAfter`；“从不”需使自动清理无期限，且不得因为先前的 30 天常量进入可自动清理。到期后的清理仍必须通过领域依赖/备份占用验证，执行可追踪的后台任务；保留期变更对已在回收站项的影响必须在设置页说明。`JRN-011` 顶部和每行应显示真实策略/日期。
- 风险：这是数据保留行为与用户明确选择不一致；可导致“从不”项过早变成可清理，或用户以为系统会自动执行而实际永不清理。
- 证据：`AppRootViewModel.kt::moveJournalToTrash()` 与常量 `JOURNAL_RETENTION_SECONDS`；`updateTrashRetention()` 仅写设置；全库 purge 执行只有 `purgeJournalTransaction()` 手动路径。

#### SRC-016｜P2｜`REC-006` 与 `ANA-012` 未按屏幕合同的 sheet/bottomSheet presentation 呈现

- 状态：待修复
- 覆盖：`REC-006` 选择实体卡，`ANA-012` 地点明细；`android_ledger_screen_contract_v1.yaml` 的页面 presentation，契约 7.3、7.4、9.9、15.3。
- 项目现状：屏幕合同将 `REC-006` 标记为 `sheet`、`ANA-012` 标记为 `bottomSheet`；但前者被 `OrdinaryRecordDestination` 当作普通 `LazyColumn(fillMaxSize)` 全屏目的地渲染，后者虽经过 `GovernedDestinationModal`，却不在 `GOVERNED_SHEET_DESTINATIONS` 集合中，因而同样落入 `else -> content()` 全屏分支。两者都会出现普通子页顶栏/返回栈，而不是契约的可下滑/点击外部取消、焦点受限模态容器。
- 正确需求：`REC-006` 应在原交易编辑器上方以 sheet 展示当前账户的“无”和兼容实体卡，选择或取消后返回同一编辑器且保留表单；`ANA-012` 应作为地图上的 bottom sheet，保留后方地图上下文，展示簇/地点摘要并可打开交易明细。两者打开时焦点必须限制在模态内，关闭后恢复到触发控件。
- 风险：失去原页上下文、返回行为和焦点语义与冻结契约不符，地图明细尤其无法作为地图上的连续探索面板。
- 证据：YAML 中两个 screen 的 presentation；`OrdinaryRecordScreens.kt::CardPicker()`；`GovernedDestinationModal.kt` 的 sheet 白名单未包含 `ANA-012`。其他合同 dialog（`JRN-012`、`ACC-012`、`ATT-002/003`、`SYS-001`）已在各自内部使用真实 `LedgerDialog`，不在此问题范围。

#### SRC-017｜P2｜恢复与小组件阶段校验器仍锁定旧实现名和旧数据库版本

- 状态：待修复
- 覆盖：P31 恢复/合并/清除、P33 小组件/导航的发布证据；契约 16.3、16.6、29、33。
- 项目现状：`validate_p31_restore.py` 只在 `SecureRoomRestoreLedgerApplicationPort.kt` 单个文件中搜索字面 `DatabaseIntegrityAudit`，而当前恢复端正确调用上层 `RoomLedgerIntegrityAudit.run()`，后者内部真实执行 `DatabaseIntegrityAudit.run()`、全量投影重建和 hash/不变式比较；因此 P31 产生假失败。`validate_p33_widget_navigation.py` 又强制旧测试名 `encryptedVersionOneDatabaseMigratesToVersionFourWithoutLosingLedgerData`，但当前 schema 已到 v5，真实测试是 `everyEncryptedPredecessorMigratesToVersionFiveWithFinancialAndQueryContractsIntact()`，覆盖 v1–v4 每个前代。多个阶段校验器还要求 `PROJECT_STATE` 同时等于各自的历史 `Current stage: Pxx`，而项目已合法处于 P36，所以会批量假红。
- 正确需求：发布校验应验证当前语义而非过期字符串：P31 应跟随 `RoomLedgerIntegrityAudit -> DatabaseIntegrityAudit + RoomProjectionEngine` 调用链，并用 mutation 证明删掉任一底层审计会失败；P33 应要求“所有加密前代迁移到 `LedgerMigrations.CURRENT_VERSION`且金融/查询契约完整”。历史阶段校验不得要求项目状态倒退；最终 P36 可聚合运行各阶段的业务校验。
- 风险：发布门禁在实现正确时仍无法全绿，团队可能被迫追加无意义注释/测试名来“骗过”校验，反而不能保护真实恢复安全性。
- 证据：`validate_p31_restore.py` 的单文件 marker；`RoomLedgerIntegrityAudit.kt` 的真实调用链；`validate_p33_widget_navigation.py` 的 v4 旧测试名；`EncryptedSchemaV1DeviceTest.kt` 的 v5 全前代测试；`docs/implementation/PROJECT_STATE.md` 当前 P36 状态。

#### SRC-018｜P2｜“减少动画”仍会执行表单与地图定位的平滑滚动

- 状态：待修复
- 覆盖：`REC-003` 校验错误定位、`REC-009` 地图定位，以及全局 Reduce Motion；契约 5.6、13.5，REQ-083。
- 项目现状：Theme 已合并应用与系统的减少动画设置，图表/地图相机也有相应降级；但普通交易编辑器在首个错误出现、点击校验摘要时始终调用 `LazyListState.animateScrollToItem()`，位置页跳转到地图区域也始终调用同一平滑滚动 API。这三个路径没有读取 `LedgerTheme.motion.reduceMotion`，因此开启减少动画后仍发生明显的位置连续移动。
- 正确需求：减少动画开启时只允许不超过 80ms 的淡入淡出，页面内位置和尺寸变化应直接完成。错误定位与地图区定位仍需保持功能，但应改用 `scrollToItem()`；正常模式才使用 `animateScrollToItem()`。焦点移动和错误公告不得因动画降级而丢失。
- 风险：对前庭敏感用户，最常见的校验失败流程仍会触发强制长距离运动；也说明应用设置没有覆盖全部生产动效路径。
- 证据：`OrdinaryRecordScreens.kt` 的三处 `animateScrollToItem()`；生产代码其余动画均集中在 design system 并已有 `reduceMotion` 分支。

#### SRC-019｜P2｜共享子页面顶栏把合同允许的两行标题强制压成一行

- 状态：待修复
- 覆盖：所有使用 `LedgerTopAppBarVariant.BACK` 的子页面；契约 6.3、13.1，REQ-083。
- 项目现状：设计合同规定子页面中间标题最多两行，但共享 `LedgerTopAppBar` 对所有 variant 一律设置 `maxLines = 1`、`TextOverflow.Ellipsis`，同时用精确 `.height(topAppBarHeight)` 锁死高度。任何较长英文/日文标题、窄屏或 200% 字体都会在仍有第二行合同空间时提前截断；该限制影响全部子页面而不是个别文案。
- 正确需求：顶层短标题可保持单行；返回型子页面标题应允许最多两行并使顶栏在 token 最小高度之上自然增高（或采用合同化的两行高度 token），仍需给左右导航/操作保留 48dp 命中区。200% 字体和三语长标题下标题不得与操作重叠，也不能仅靠省略号隐藏页面身份。
- 风险：用户在深层路由和大字号下无法辨认当前页面；同一共享组件会把问题扩散到近乎全部编辑、详情和选择页。
- 证据：主合同 6.3“子页面—中间标题最多两行”；`FoundationComponents.kt::LedgerTopAppBar()` 的固定高度与无条件 `maxLines = 1`。

#### SRC-020｜P2｜部分无效表单直接禁用保存，无法触发合同化校验定位

- 状态：待修复
- 覆盖：共享表单行为及 `REC-003`、`BUD-003`、`ANA-008`、`AUT-006`、`BKP-004`、`VLT-003`；契约 8.4、9.1、13.2，REQ-027、REQ-083。
- 项目现状：多数编辑器已经保持保存按钮可点并在回调内校验，但仍有多条生产路径把“表单无效”直接映射为 `enabled = false`：普通交易只要互请分配错误就禁用 FAB；分类预算使用 `state.validation.valid` 禁用固定保存栏；自定义报表最后一步用 `canSave` 同时禁用预览和保存；周期规则的应用按钮用 `AutomationPolicy.canSaveRecurrence()` 禁用；备份设置把保留数量/天数解析失败直接映射为禁用；保险库自定义敏感字段不完整时用 `canSave` 禁用。用户点击这些灰色操作时不会产生 ValidationSummary、错误公告、滚动或聚焦。
- 正确需求：表单无效时保存/应用仍必须可点击；点击后执行本地校验，展示字段错误与顶部摘要，向 TalkBack 宣告并滚动/聚焦首个错误。只有提交中、维护中、权限/认证等绝对前置条件缺失时才真正禁用。无变化状态可以使用明确“没有更改”反馈，但不能与字段无效混成不可解释的灰色按钮。
- 风险：键盘、TalkBack 或低经验用户无法知道为何无法继续，跨字段错误尤其没有单一可发现入口；相同保存模式在不同页面表现不一致。
- 证据：`RecordAttachmentPicker.kt::ordinaryRecordFixedAction()`、`BudgetScreens.kt::BudgetCategoryEditor()`、`P26AnalysisScreens.kt::BuilderActionBar()`、`AutomationScreens.kt::RecurrenceRuleEditor()`、`BackupFlowScreen.kt` 的 BKP-004 fixedAction、`VaultScreens.kt` 的 `canSave/VaultSaveBar`。

#### SRC-021｜P3｜Glance 小组件容器间距仍是源码常量而非设计令牌

- 状态：待修复
- 覆盖：所有桌面小组件；契约 2.1、2.2、5.2、UI-ADR-012。
- 项目现状：小组件颜色与字号已经通过 `LedgerGlanceTokens` 从中央 design system 派生并提供 day/night 值，但根容器仍直接 `.padding(12.dp)`，文件还用 `@file:Suppress("MagicNumber")` 压制检查。12dp 当前恰好属于冻结间距序列，却没有与 JSON token 建立可执行关联；token 改版时小组件会静默漂移。
- 正确需求：Glance 可用的全部可见尺寸也应由 `:core:designsystem` 暴露的只读 token 子集提供，例如 `contentPadding`；widget 只能消费该值，不在本地重新写数字。自动校验应覆盖 Glance 子集与 JSON 标量一致性。
- 风险：当前视觉差异轻微，但破坏“机器可读 JSON 为唯一值”的治理边界，后续间距 patch 无法可靠传播到桌面组件。
- 证据：`LedgerGlanceWidget.kt` 根 `GlanceModifier.padding(12.dp)`；`LedgerGlanceTokenSubset` 目前只有颜色与字号，没有间距字段。

#### SRC-022｜P2｜长任务失败的主提示直接显示内部稳定码

- 状态：待修复
- 覆盖：共享 `OperationProgressPanel`，影响操作中心、导入与恢复等传入 `failureCode` 的页面；契约 8.28、9.1、10.8、15.5。
- 项目现状：`OperationProgressPanel` 收到 `model.failureCode` 后，把 `code.value` 原样作为危险 Banner 的唯一主文案，例如 `IMPORT_FAILED`、`OPERATION_FAILED`；点击“查看错误”后又显示“code · statusExplanation”。虽然 `statusExplanation` 往往说明取消能力或持久化阶段，但首屏错误没有回答“发生了什么、是否保存、下一步是什么”，而且把内部全大写标识当成用户文案。部分调用页会额外再放一个本地化错误 Banner，导致重复；操作中心等调用页则只剩原始码。
- 正确需求：失败模型应同时携带本地化、面向用户的三要素错误说明和可选脱敏稳定码。主 Banner 展示说明并附合适的重试；稳定码只放在展开的技术详情/复制诊断信息中。组件不应自行假设任意 `UiErrorCode.value` 可直接显示。
- 风险：关键长任务失败时用户不知道数据是否已写入或应该怎样恢复，内部码还破坏三语一致性；重复 Banner 又增加噪声。
- 证据：`ChartsMapsAndRiskComponents.kt::OperationProgressPanel()` 的 `LedgerBanner(code.value, ...)`；`AppRootScreen.kt` 操作中心以及 `ImportWizardScreen.kt`/`RestoreFlowScreen.kt` 的 failureCode 调用。

## 阶段 2：agent-device 全量黑盒测试

状态：进行中。测试包为本轮源码编译后安装到 `ExpenseTracker_API_36` 的 Debug APK；进入本阶段后，应用观察与交互只使用 agent-device，不读取源码、不运行 Gradle。

### 发现清单

#### RUN-001｜P2｜顶层与交易子页面顶栏缺少当前页面标题

- 状态：待修复
- 分类：视觉、导航、无障碍。
- 影响：`REC-001` 记账首页、`REC-013` 转账；后续发现同类画面时继续补充。
- 复现：打开记账首页，随后进入“其他交易 > 转账”；分别运行 `agent-device snapshot -i` 和截图。
- 实际行为：记账首页在系统状态栏下直接开始“支出/收入/其他交易”Tab，只在右上显示“更多”图标；转账、退款、贷款、分类/商户编辑、流水详情、账户详情等子页面也只有返回按钮，没有稳定标题。多处首个内容卡直接延伸到返回按钮命中区下方；账户详情的“现金钱包”标题甚至与返回区域明显重叠。
- 正确需求：顶层页和子页面都必须有稳定的当前画面标题及 heading 语义，右侧再放页面专属动作；Tab 或返回按钮不能替代当前页面身份。
- 用户风险：首次使用及深层导航时缺少当前位置，TalkBack 用户也无法从顶栏确认所在画面。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-run-001.png`、`docs/testing/ManualTestFindings/fulltest-phase2-run-001-transfer.png`。

#### RUN-002｜P1｜转账账户字段点击后直接轮换，无法明确选择目标账户

- 状态：待修复
- 分类：功能、交互、可恢复性；与源码审查 `SRC-003` 相互印证。
- 影响：`REC-013` 转账的“转出账户”和“转入账户”，以及余额调整的“调整账户”；后续发现同类字段时继续补充。
- 复现：进入“其他交易 > 转账”，点击“转出账户”。
- 实际行为：点击字段后没有打开选择器、搜索或候选列表，而是立即把“现金钱包 · CNY”替换成下一个“回归信用卡 · CNY”。用户看不到完整候选集、无法直接选定账户，也没有取消这次轮换的机会；候选较多时只能反复点击并记忆顺序。
- 正确需求：动态业务实体必须使用可取消、可浏览且在候选较多时可搜索的实体选择器，当前选择应明确标记；点击字段本身不能产生不可预览的轮换副作用。
- 用户风险：极易选错转出/转入账户，账户多时操作成本随数量线性上升，并影响转账账务正确性。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-run-002-account-cycle.png`。

#### RUN-003｜P1｜信用卡“提前还款”选项与提交规则矛盾，且金额错误文案误导

- 状态：待修复
- 分类：功能、表单校验、文案。
- 影响：信用卡还款画面。
- 前置条件：信用账户当前未还金额为 0。
- 复现：选择付款账户，输入 `10`，选择“暂不分配（提前还款）”，点击“保存”。
- 实际行为：画面允许用户选择明确标为“提前还款”的模式，但提交后顶部提示“主动还款不能超过当前全部未还金额”，金额字段同时显示与真实原因不符的“请输入有效的正金额”；已输入的 `10` 在字段内清晰可见且确为正数。
- 正确需求：若产品允许提前还款，应按该模式正确记账；若业务规则禁止超额还款，则在无可还金额时应禁用/隐藏该模式并提前说明上限。字段错误必须说明真实约束和可接受范围，不能把业务上限错误伪装成“不是有效正金额”。
- 用户风险：用户无法判断是数值格式、账户余额还是还款规则出错，也会被“提前还款”入口诱导进入必然失败的流程。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-run-003-credit-positive-invalid.png`。

#### RUN-004｜P1｜无费用分类时独立退款缺少必填入口，错误却标在有效金额上

- 状态：待修复
- 分类：功能、空状态、表单校验。
- 影响：退款画面的“独立退款”路径。
- 前置条件：账本尚无费用分类，且没有可关联原交易。
- 复现：进入“其他交易 > 退款”，保持“独立退款”，输入 `5` 后点击“保存”。
- 实际行为：说明文案明确写着“仍需选择费用分类”，但整个表单没有费用分类字段、空态说明或创建分类入口；提交后没有指出分类缺失，反而在已正确显示计算结果 `5` 的金额字段下标出“请检查此字段”。
- 正确需求：必填业务实体即使候选为空也必须保留字段位置，显示“暂无费用分类”并提供创建/管理路径；提交校验必须把错误绑定到真正缺失的分类字段，并给出可执行的修复提示。
- 用户风险：在空账本上独立退款形成无法完成的死路，用户会反复修改本来正确的金额且仍不能保存。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-run-004-refund-no-category.png`。

#### RUN-005｜P1｜无贷款合同时放款/还款表单隐藏合同选择，形成不可完成死路

- 状态：待修复
- 分类：功能、空状态、表单校验。
- 影响：贷款放款与贷款还款画面。
- 前置条件：账本中没有贷款合同。
- 复现：进入“其他交易 > 借贷 > 记录贷款放款”，输入放款总额 `100` 后保存；贷款还款页也可观察到同类结构。
- 实际行为：画面出现“贷款合同”分区标题，但下面既没有合同字段、空态说明，也没有“新增贷款”入口；保存后只在顶部泛化提示“请修正标出的贷款条款或金额”，却没有任何可修正的合同控件。
- 正确需求：没有合同候选时必须显示明确空状态及“新增/管理贷款合同”动作；放款和还款不能进入一个缺少核心必填实体且无法自救的表单。
- 用户风险：新用户按正常业务顺序先尝试记录放款时必然失败，且无法从当前页面知道必须先去另一个入口创建合同。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-run-005-loan-disbursement.png`。

#### RUN-006｜P1｜新增贷款向导停在第 3/6 步，没有继续入口且保存只报泛化错误

- 状态：待修复
- 分类：功能阻断、导航、表单校验。
- 影响：贷款合同“新增贷款”向导。
- 复现：完成名称/贷款方/放款日期和分项本金，进入“第 3/6 步 还款条款”，填写期数、首次还款日和年利率。
- 实际行为：第 3 步的可访问树与完整画面中都没有“下一步”，只剩全局“保存”。点击“保存”后仍停在第 3/6 步并泛化提示“请修正标出的贷款条款或金额”，页面没有指出具体字段错误，也没有进入第 4 步的方法。
- 正确需求：六步向导的每个非末步骤必须有明确“下一步”和“上一步”，完成本步校验后可继续；若字段不合法，错误必须落在对应字段并解释约束。保存只能在数据达到可提交状态时工作，或明确引导到尚未完成的步骤。
- 用户风险：贷款合同无法创建，继而阻断贷款放款、还款、计划与详情链路。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-loan-save.png`。

#### RUN-007｜P2｜只有单一币种时仍进入不可完成的外币兑换表单

- 状态：待修复
- 分类：空状态、流程引导、功能可达性。
- 影响：`REC-021` 外币兑换。
- 前置条件：当前可用账户全部为 CNY，没有其他币种账户。
- 复现：进入“其他交易 > 外币兑换”。
- 实际行为：入口照常打开完整表单并默认选择两个 CNY 账户；顶部虽说明“外币兑换必须选择不同币种”，但账户字段只能在现有同币种账户间切换，用户填写两个有效金额后仍不可能完成。
- 正确需求：进入前或进入后应识别没有合格的异币种账户组合，显示专用空状态，并提供“新增异币种账户”或返回使用“内部转账”的明确动作；不应让用户填写一个先验上必然失败的表单。
- 用户风险：用户花费时间输入金额后才确认流程无法提交，也无法从当前画面直接补齐必要账户。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-run-007-fx-same-currency.png`。

#### RUN-008｜P1｜普通收支编辑页底部项目字段被保存操作遮挡且无可见内容

- 状态：待修复
- 分类：布局、可操作性、响应式安全区。
- 影响：普通支出与收入编辑页。
- 复现：从分类进入一笔普通支出或收入编辑，观察页面最底部“项目（可选）”字段与浮动“保存”操作。
- 实际行为：无障碍树存在“项目（可选）”控件，但视觉上该卡片只剩一块空白边框，字段文字/当前值不可见；右下的“保存”浮层又覆盖卡片大部分区域。金额字段获得焦点后，该项目节点还会从当前可见树中消失。
- 正确需求：最后一个表单字段必须完整可见、可识别且可点击；固定/浮动提交操作需要为滚动内容预留足够底部 inset，不能遮住字段或字段说明。候选为空时也要显示可理解的空值与创建项目路径。
- 用户风险：用户不知道页面还有项目关联能力，也可能误触保存而无法打开项目字段。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-expense-editor.png`。

#### RUN-009｜P2｜商户别名移除控件缺少目标名称的无障碍语义

- 状态：待修复
- 分类：无障碍、可恢复性。
- 影响：新建/编辑商户的别名列表。
- 复现：输入别名“商户别名”，点击“添加别名”，再运行 `agent-device snapshot -i`。
- 实际行为：视觉上行内同时显示别名和“移除”，但无障碍树只暴露一个名为“移除”的 group，没有把“商户别名”作为文本节点或按钮标签的一部分；多个别名时所有移除操作将无法区分。
- 正确需求：每个删除动作必须是按钮并带目标上下文，例如“移除别名 商户别名”；别名文本本身也应可由辅助技术读取。
- 用户风险：TalkBack 用户不知道即将删除哪个别名，容易误删且难以恢复。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-run-008-merchant-alias.png`。

#### RUN-010｜P0｜隐藏金额开启后流水无障碍标签仍泄漏真实金额

- 状态：待修复
- 分类：隐私、安全、无障碍。
- 影响：流水列表及可能复用同一语义标签的金额卡片。
- 前置条件：全局金额隐私已开启。
- 复现：打开“流水”，运行 `agent-device snapshot -i`。
- 实际行为：视觉子节点显示“金额已隐藏”，但整行无障碍标签仍包含真实值，例如“收入. 工资. 现金钱包. 200.00 CNY…”、“支出. 餐饮…18.00 CNY…”和“余额调整…1.00 CNY…”。进入详情后，虽然账户影响被掩码，原金额表达式 `12+3*2` 与关系串里的 `gross=1800`、`remaining=1800` 仍可见，继续旁路泄漏金额。
- 正确需求：金额隐私必须覆盖可见文本、contentDescription、stateDescription、测试/语义节点、通知、组件与导出预览；隐藏时无障碍描述只能说“金额已隐藏”，不能保留原值。
- 用户风险：旁听 TalkBack、自动化辅助服务、无障碍检查器或录屏日志都可绕过用户明确选择而取得敏感财务金额，属于发布阻断级隐私泄漏。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-run-010-hidden-a11y.png`（视觉上已隐藏；真实值由同一画面的 agent-device 可访问快照直接读取）、`docs/testing/ManualTestFindings/fulltest-phase2-run-013-journal-detail.png`。

#### RUN-011｜P2｜流水筛选与模板单选项不暴露角色和选中状态

- 状态：待修复
- 分类：无障碍、状态可感知性。
- 影响：流水高级筛选类型/状态，复制为模板的图标、交易类型、分类与账户等单选集合。
- 复现：打开流水搜索的“更多功能”，点击“支出”；另从交易详情进入“复制为模板”，运行 `agent-device snapshot -i`。
- 实际行为：这些视觉上为筛选 chip 或单选项的控件在无障碍树中都只是普通 `group`；点击后快照完全不产生 `selected`/`checked` 状态变化。辅助技术无法知道集合关系、当前选择或点击结果。
- 正确需求：互斥项使用 radio/selected 语义，多选筛选使用 checkbox/toggle 语义，并实时暴露选中状态与集合标签；状态不能只靠背景颜色表达。
- 用户风险：TalkBack 用户无法可靠配置筛选或模板，可能保存错误的交易类型、分类和账户。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-run-011-filter-semantics.png`。

#### RUN-012｜P2｜空回收站复用了普通流水空态并提供无效“重试”

- 状态：待修复
- 分类：空状态、文案、交互反馈。
- 影响：流水回收站空状态。
- 复现：在回收站没有项目时进入“流水 > 回收站”，点击“重试”。
- 实际行为：画面标题为“暂无交易”，正文写“记下的交易会按发生时间显示在这里”，与回收站语境不符；唯一主要动作是“重试”，点击后界面完全不变且没有加载或结果反馈。
- 正确需求：空回收站应明确说明“暂无已删除交易”和保留/账务净效果规则，主要动作应返回流水或打开保留期设置；只有真实加载失败时才显示“重试”。
- 用户风险：用户会误以为列表加载失败，不知道回收站是否真的为空，也无法从动作理解如何产生或管理回收站项目。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-run-012-trash-error.png`。

#### RUN-013｜P1｜流水列表和详情直接展示内部状态、键名与序列化关系串

- 状态：待修复
- 分类：内容呈现、本地化、数据边界；与源码审查 `SRC-006`、`SRC-007`、`SRC-022` 相互印证。
- 影响：普通交易详情、退款/已退款流水行及其他复用关系/统计组件。
- 复现：打开普通支出详情；创建部分退款后返回流水列表。
- 实际行为：详情直接显示 `included:1`、`refund.status:gross=1800:refunded=0:remaining=1800:CNY` 等内部键和值；退款与原交易行又直接显示英文机器状态 `refund`、`refunded`。多个无数据分区用裸 `—` 占位。详情的可访问树在首屏几乎只包含“有效”和返回按钮，大量可见业务内容不可读。
- 正确需求：内部枚举、minor-unit 整数、序列化关系和诊断码必须在 UI 边界转换为本地化标签、格式化金额和可理解的关系卡片；无数据使用契约空态文案；所有可见详情需具备相同信息量的无障碍语义。
- 用户风险：界面像未完成的调试构建，用户无法理解退款状态和统计口径，TalkBack 用户还会漏掉绝大部分交易详情。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-run-013-journal-detail.png`。

#### RUN-014｜P1｜有关联退款的原交易“移入回收站”无效果且无解释

- 状态：待修复
- 分类：功能、依赖处理、错误反馈。
- 影响：已存在部分退款关系的支出详情。
- 复现：对支出创建部分退款，回到原交易详情点击“移入回收站”，再返回有效流水并查看回收站。
- 实际行为：操作没有确认、成功或失败反馈；原交易仍以“有效/refunded”出现在有效流水，回收站仍为空。当前详情中的删除动作会短暂消失，但重新进入后又出现。
- 正确需求：若允许删除，应原子地移入回收站并刷新列表；若退款依赖阻止删除，应在点击前后明确说明依赖、受影响记录及可执行方案，绝不能静默无效。
- 用户风险：用户以为敏感交易已删除但它仍然存在；反复点击还会造成对账务状态的不信任。

#### RUN-015｜P1｜版本对比只有较早版本列，无法看到变更后的值

- 状态：待修复
- 分类：功能、审计可解释性。
- 影响：交易修改历史的“版本对比”。
- 复现：把一笔支出从表达式 `12+3*2` 修改为 `19`，进入“修改历史 > 版本对比”。
- 实际行为：“已变化”表只显示“字段”和“较早版本”两列，金额行只有一个被隐藏的值；没有“当前/较新版本”列，因此页面无法表达从什么值变成什么值。
- 正确需求：版本对比必须并列展示字段、较早版本与较新版本（在金额隐私下两侧一致掩码），并清晰标注版本号/时间；恢复动作需要让用户知道恢复的具体目标。
- 用户风险：审计和恢复前无法判断实际差异，可能恢复错误版本。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-run-015-version-compare.png`。

#### RUN-016｜P1｜新建退款出现在流水后详情永久加载失败并暴露内部错误码

- 状态：待修复
- 分类：功能阻断、错误边界、诊断信息泄漏。
- 影响：由原支出创建的退款交易详情。
- 复现：从原支出详情创建部分退款 `5`，返回流水并点击新出现的“退款”行；点击“重试”。
- 实际行为：详情显示“该交易不可用”，并把内部错误码 `JOURNAL_DETAIL_FAILED` 直接展示给普通用户；点击“重试”仍返回同一错误。退款行本身存在，原交易也已标为 `refunded`，但退款无法查看、审计或继续操作。
- 正确需求：所有成功创建并列入流水的交易类型都必须有可打开的详情；错误边界应提供用户可执行的恢复路径与本地化说明，诊断码只能在明确的脱敏诊断区按需复制，不能作为主界面内容。
- 用户风险：退款账务已生效但详情链路断裂，用户无法核验、撤销、管理附件或处理后续退款，属于发布阻断功能缺陷。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-run-016-refund-detail-error.png`。

#### RUN-017｜P1｜账户详情与账户流水的大量可见内容未进入无障碍树

- 状态：待修复
- 分类：无障碍、信息可达性。
- 影响：账户详情、账户流水及其筛选项。
- 复现：打开“账户 > 现金钱包”，再进入“账户流水”，分别运行 `agent-device snapshot -i`。
- 实际行为：账户详情视觉上有余额、可用余额、关联目标、近期流水表格等大量信息，但快照首屏只暴露“账户流水”“余额检查点”和返回；账户流水视觉上有退款、收入、支出和余额调整多行，快照却只暴露“全部/支出/收入”三个筛选与返回。筛选也没有 selected 状态。
- 正确需求：每张账户卡、目标、表头和流水行都要有可读且不泄漏隐藏金额的语义；筛选项需暴露角色与选中状态，阅读顺序与视觉顺序一致。
- 用户风险：TalkBack 用户几乎无法获知账户余额结构、目标或任何账户交易，账户模块实质不可用。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-account-detail.png`、`docs/testing/ManualTestFindings/fulltest-phase2-run-017-account-activity-empty.png`。

#### RUN-018｜P1｜保存余额检查点后应用任务被关闭并把用户踢回桌面

- 状态：待修复
- 分类：稳定性、导航、数据写入反馈。
- 影响：账户“余额检查点”的“仅保存检查点”动作。
- 复现：为现金钱包选择 `2026-08-31`、输入 `1000`，点击“仅保存检查点”。
- 实际行为：按钮短暂变为 disabled，随后前台直接变成 Android Launcher，Quiet Ledger 整个任务退出；没有成功提示、返回账户详情或失败说明。重新点击桌面图标后应用从记账首页冷启动。
- 正确需求：保存成功应停留在应用内并返回检查点/账户上下文，明确显示保存结果；失败应保留输入并显示可重试错误，绝不能无提示结束应用任务。
- 用户风险：表现等同崩溃，用户无法确认数据是否保存，且可能丢失导航上下文或其他未保存操作。

#### RUN-019｜P1｜空信用账户详情的空态图标、标题与相邻分区严重重叠

- 状态：待修复
- 分类：布局、空状态、响应式排版。
- 影响：没有账户流水/关联目标的信用账户详情。
- 复现：打开“账户 > 回归信用卡”。
- 实际行为：“实体卡（0）”“关联目标”“暂无账户流水”连续区域没有正确占位；空态感叹号与“暂无账户流水”标题互相叠压，并侵入关联目标区域，文字基线和间距明显破坏。
- 正确需求：各 section 独立占位并遵循纵向间距；空列表只在对应“近期流水”容器内显示图标、标题、说明和动作，不能覆盖前一个 section。
- 用户风险：界面呈现为渲染错误，用户无法判断空态属于实体卡、目标还是账户流水。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-credit-account-detail.png`。

#### RUN-020｜P0｜实体卡保存成功后仍停留在脏表单，重复点击会创建重复卡片

- 状态：待修复
- 分类：数据完整性、幂等性、保存反馈。
- 影响：实体卡新增。
- 复现：输入名称“测试实体卡”和尾号 `1234`，点击“保存”；画面不变后再次点击“保存”，再返回并选择“放弃更改”。
- 实际行为：每次点击都会短暂显示“正在保存”后回到完全相同的编辑表单，没有成功提示、导航或禁用；返回时仍弹出未保存更改对话框。放弃后实体卡列表出现两张完全相同的“测试实体卡 / 尾号 1234”，证明两次写入均已发生。
- 正确需求：一次保存必须幂等；成功后清理 dirty 状态并导航到新卡详情/列表，重复提交在请求期间与完成后均不可再次写入。若留在表单，应明确显示已保存并把后续保存视为更新同一记录。
- 用户风险：普通用户会因无反馈重复点击而制造重复支付工具，影响分类默认卡、筛选、导入映射和后续账务准确性，属于发布阻断级数据完整性问题。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-card-save.png`、`docs/testing/ManualTestFindings/fulltest-phase2-run-020-card-duplicate.png`。

#### RUN-021｜P1｜预算金额隐私把指标标签一起隐藏，汇总卡只剩无含义圆点

- 状态：待修复
- 分类：隐私呈现、信息架构、无障碍。
- 影响：预算首页汇总卡、分类预算详情的统计摘要。
- 前置条件：全局隐藏金额开启。
- 复现：打开预算首页及餐饮分类预算详情。
- 实际行为：“本月剩余可用预算”和“今日可用”卡片内多行内容全部只显示 `••••`，对应的指标名称、口径和单位也不见了；分类预算详情下方同样只有两行圆点。可访问快照也不包含这些汇总项。
- 正确需求：金额隐私只替换敏感数值，不得隐藏“月度总预算/已用/剩余/今日可用”等非敏感标签和口径；每个掩码值需与标签配对并可由辅助技术读为“某指标，金额已隐藏”。
- 用户风险：开启隐私后预算首页失去解释价值，用户连每行代表什么都无法判断。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-budget.png`、`docs/testing/ManualTestFindings/fulltest-phase2-budget-category.png`。

#### RUN-022｜P1｜分析详情和自定义报表控件缺少可操作的无障碍名称/结构

- 状态：待修复
- 分类：无障碍、图表等价信息。
- 影响：固定报表详情、自定义报表七步向导。
- 复现：打开“收支与净结余趋势”或“消费分类结构”详情并运行交互快照；在自定义报表第 1 步运行 raw 快照。
- 实际行为：固定报表视觉上有关键指标、比较期、图表、筛选、导出和查看原始交易，但交互快照几乎只暴露返回按钮；自定义报表 raw 树中的 `EditText` 和各 `CheckBox` 本身 label 为空，标签只作为分离的兄弟文本存在，选中状态也没有进入稳定的聚合语义。
- 正确需求：图表必须提供等价摘要和数据表语义；每个字段、checkbox、radio、筛选、导出与下钻动作都需有自己的可访问名称、角色与状态，并保持合理阅读顺序。
- 用户风险：TalkBack 用户无法读取报表结果，也无法可靠构建和保存自定义报表。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-analysis-trend.png`、`docs/testing/ManualTestFindings/fulltest-phase2-analysis-category.png`、`docs/testing/ManualTestFindings/fulltest-phase2-custom-report.png`。

#### RUN-023｜P1｜自定义报表第 3 步的地点筛选被底部导航遮挡

- 状态：待修复
- 分类：布局、表单可操作性。
- 影响：自定义报表“筛选”步骤。
- 复现：完成指标和日期维度，进入第 3/7 步。
- 实际行为：账户、分类、商户筛选完整显示，但最后一个“地点”卡片被固定的“上一步/下一步”操作栏盖住，只露出标题上沿；可访问快照也只剩两个普通文本节点“地点/全部”，没有可点击筛选控件。
- 正确需求：滚动容器必须为固定底部操作栏预留 inset，最后一个筛选字段可完整滚入视口并具备与其他实体筛选相同的可点击、可搜索语义。
- 用户风险：用户无法设置地点过滤，也容易误触“下一步”。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-run-023-custom-location.png`。

#### RUN-024｜P0｜自定义报表可视化选择工作表形成无法退出的导航陷阱

- 状态：待修复
- 分类：功能阻断、模态导航、未保存更改。
- 影响：自定义报表第 6/7 步的“图表与数据表”选择器。
- 复现：打开兼容性列表，选择“条形图”，点击工作表外部/“关闭工作表”或按返回。
- 实际行为：选择不会自动关闭；关闭工作表或返回不是回到第 6 步，而是弹出整个报表的“放弃未保存更改”对话框。选择“继续编辑”重新打开工作表；选择“放弃更改”也同样重新打开工作表。所有正常返回路径都在工作表与对话框之间循环，无法回到向导或退出页面。
- 正确需求：选择兼容图表后应更新选中状态并关闭工作表（或提供明确“完成”）；系统返回/遮罩只关闭工作表。只有离开整个向导才显示未保存确认，确认放弃后必须真正退出。
- 用户风险：用户被困在模态循环，已完成的前五步无法保存，只能强制关闭应用，属于发布阻断级导航问题。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-vis-sheet.png`。

#### RUN-025｜P1｜项目详情的返回动作被错误绑定为页内视图切换

- 状态：待修复
- 分类：导航、返回栈、无障碍。
- 影响：“更多 > 项目 > Release Project”的概览、交易与现金流视图。
- 复现：在项目概览点击左上角“返回”，或在交易视图使用系统返回。
- 实际行为：左上角返回不返回项目列表，而是从概览切换到项目交易视图；交易视图的系统返回又回到概览。页面另外提供一个内容区“返回项目”，它也只切回概览。用户在正常返回路径中无法回到项目列表。
- 正确需求：顶部返回与系统返回必须按导航栈返回项目列表；概览/交易/现金流只能由页签或明确的内容动作切换，不得复用返回事件。
- 用户风险：违背 Android 返回预期并形成页内循环，使项目列难以达到，TalkBack 用户尤其难以理解状态变化。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-project-backtrap.png`。

#### RUN-026｜P1｜目标资金历史表超出手机视口，金额列和操作列不可见

- 状态：待修复
- 分类：响应式布局、数据表、可用性。
- 影响：“更多 > 目标资金 > Emergency Fund”的“目标资金历史”。
- 复现：在 1080×2400 模拟器打开目标详情，执行一次“分配”和“释放”后查看历史。
- 实际行为：表头只完整显示“日期”，右侧仅露出下一列的极小字形边缘；所有行只能看到日期时间，金额、动作类型/余额等关键列全在屏幕外，也没有可发现的水平滚动或窄屏替代布局。
- 正确需求：手机宽度下应使用紧凑可读的行布局/卡片化历史，或提供明确的可滚动容器；每条记录的日期、动作和金额必须可达，开启金额隐藏时仍保留非敏感列标题。
- 用户风险：用户无法核对资金分配/释放结果，历史审计功能在手机上失效。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-goal-detail.png`、`docs/testing/ManualTestFindings/fulltest-phase2-goal-new-back.png`。

#### RUN-027｜P1｜信用账户列表的唯一新建入口错接到贷款向导

- 状态：待修复
- 分类：信息架构、路由、功能缺失。
- 影响：“更多 > 信用账户”列表的新建动作。
- 复现：进入信用账户列表，点击底部唯一动作“新增贷款”。
- 实际行为：页面展示现有信用卡账户，但唯一 CTA 命名为“新增贷款”；点击后确实打开“贷款名称/贷款方/放款日期”的贷款创建向导，而非信用账户创建或设置。
- 正确需求：信用账户列表应提供与模块一致的“新增信用账户”路径，贷款创建应只从“贷款与负债”进入；若该列表只允许展示无需创建，也不应显示无关 CTA。
- 用户风险：用户无法从预期位置新建信用账户，并可能误入复杂贷款流程。

#### RUN-028｜P1｜账单时区仅有 6 个预设，搜索其他合法 IANA 时区时无结果也无空态

- 状态：待修复
- 分类：本地化、时区正确性、搜索空态。
- 影响：信用账户设置的“账单时区”选择器。
- 复现：打开时区工作表，查看全部选项，再搜索 `Asia/Shanghai`。
- 实际行为：只有 `Asia/Tokyo`、`UTC`、`Europe/London`、`America/New_York`、`America/Los_Angeles`、`Australia/Sydney` 六项；输入合法的 `Asia/Shanghai` 后结果区完全空白，没有“无结果”、清除筛选建议或任何可选条目。
- 正确需求：必须覆盖完整的可用 IANA 时区集并可搜索；搜索无匹配时显示有明确文案和恢复动作的空态。
- 用户风险：大量地区无法配置真实账单时区，可导致交易归期、账单生成与到期判定错误。

#### RUN-029｜P1｜信用账户与贷款两个不同入口打开同一混合列表

- 状态：待修复
- 分类：信息架构、路由、领域分类。
- 影响：“更多 > 信用账户”与“更多 > 贷款与负债”。
- 复现：分别进入两个菜单项并对比页面内容。
- 实际行为：两个入口均显示完全相同的“回归信用卡/完整负债/新增贷款”列表。页面没有标题可说明当前领域，信用卡被当作贷款列表项，而信用账户页又提供贷款新建。
- 正确需求：信用账户与组合贷款应按契约分属各自列表、空态、新建路径和详情；若需要统一负债总览，必须作为明确的第三层汇总页，不得让两个不同入口静默指向同屏。
- 用户风险：用户无法理解信用卡与贷款的归属，可在错误领域创建记录，也无法判断某一类负债是真为空还是被混入其他列表。

#### RUN-030｜P0｜新建互请活动的“添加参与人”被固定保存层覆盖，无法满足必填条件

- 状态：待修复
- 分类：功能阻断、布局、触摸命中。
- 影响：“更多 > 互请与结算 > 新建互请活动”。
- 复现：输入活动名“测试互请”，添加第一位参与人“小王”并设为本人；在底部输入第二位“自己”并尝试点击“添加参与人”。
- 实际行为：第二个“添加参与人”控件只露出顶部弧线，被底部固定“保存”操作层与系统导航区覆盖；即使点击未被蓝色按钮视觉覆盖的左侧区域也无响应。保存则提示至少需要 2 位参与人，导致无可继续路径。
- 正确需求：滚动容器必须为固定底部操作区和 IME/系统导航 inset 预留足够空间，使最后一个字段及“添加参与人”能完整滚入、可见、可触达；不可用不可满足的校验条件锁死创建流程。
- 用户风险：互请活动无法新建，连带使参与人、分摊、多次部分结算与补充结算整条主功能在新用户路径上不可用，属于发布阻断。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-sharing-create.png`、`docs/testing/ManualTestFindings/fulltest-phase2-sharing-participants.png`。

#### RUN-031｜P0｜周期交易表单在所有可见必填项完成后仍无法保存，草稿预览也失效

- 状态：待修复
- 分类：功能阻断、表单校验、预览。
- 影响：“模板与周期 > 周期交易 > 创建”。
- 复现：选择已能正常创建账务的模板“测试商户”，保留“候选模式”、开启候选通知，设置“每月指定日/间隔 1/每月 31 日/当月无此日期时移到月末/从 2026-08-31 开始”，固定地点为“不使用固定地点”，然后点击预览和保存。
- 实际行为：“预览未来 10 次”跳到空态并提示“先保存有效的周期规则”，无法预览当前草稿；返回后点击保存，只显示“请检查标出的必填项或规则”，但页面没有任何字段标红、错误文案或可见的未选必填项，也不会创建记录。
- 正确需求：创建页的“预览”必须直接基于当前草稿计算未来发生日期，不应要求先保存；保存若失败必须将具体错误绑定到可见字段并滚动/聚焦到首个错误。上述完整有效配置应能保存。
- 用户风险：新用户无法创建任何周期规则，因此待确认候选、自动正式记账、补齐和去重等整条周期功能不可使用，属于发布阻断。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-recurring-create.png`、`docs/testing/ManualTestFindings/fulltest-phase2-recurring-rule.png`、`docs/testing/ManualTestFindings/fulltest-phase2-recurring-preview-result.png`、`docs/testing/ManualTestFindings/fulltest-phase2-recurring-save-disabled.png`。

#### RUN-032｜P1｜导入向导的字段与实体映射操作向辅助技术暴露为成组同名按钮

- 状态：待修复
- 分类：无障碍、导入、语义。
- 影响：“导入数据 > 字段映射/实体映射”。
- 复现：选择模拟器中的 `transactions.csv`，完成 UTF-8/表头行 1 解析，然后用交互快照检查字段映射和实体映射页。
- 实际行为：字段映射页的所有行只读为 5 个无法区分的“更改目标字段”，没有源列名、当前目标和样例值；实体映射页的账户/实体卡/分类/商户动作也全部只读为“映射到现有实体”，不含实体类型、缺失数量、当前决策或禁用原因。
- 正确需求：每个映射动作的可访问名称必须聚合视觉卡片的完整上下文，例如“将源列 amount_minor（样例 1500）从金额更改到其他目标字段”和“账户：缺失 1 项，当前将创建，映射到现有实体”，并暴露角色、状态与合理阅读顺序。
- 用户风险：TalkBack 用户无法判断正在修改哪一列或哪一类实体，极易导致错字段、重复实体或错账户导入。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-import-mapping.png`、`docs/testing/ManualTestFindings/fulltest-phase2-import-entity-mapping.png`。

#### RUN-033｜P0｜本地备份必然失败，重试无响应且操作中心暴露内部错误码

- 状态：待修复
- 分类：数据安全、备份、错误恢复、文案。
- 影响：“备份 > 立即备份”及“操作中心”。
- 复现：备份位置显示为 `snapshots`，保留“包含保险库”并点击“开始备份”；等待失败后打开“单文件便携备份”、使用默认文件名 `ledger.ledger-backup`，再点击“重试”和“在操作中心查看”。
- 实际行为：数据库快照阶段失败，页面提示“备份仓库不可用，本机账本未被更改”。修改为便携备份后“重试”毫无反应；退回备份首页再进入仍固定留在失败/重试状态。操作中心直接显示并换行截断 `BACKUP_REPOSITORY_UNAVAILABLE`，同时进度文案为“已处理 0 / 总量待确定”，不提供可执行的重新授权/重选位置路径。
- 正确需求：发起备份前必须验证仓库 URI/应用私有位置可用性；失效授权应直接提供“重新选择位置”，便携备份应走可用的安全文件创建路径。重试必须真正启动新任务，错误使用本地化的用户语言、原因与解决动作，内部代码仅供可折叠诊断细节。
- 用户风险：无法产生发布前最基本的数据备份，且失败后没有自助恢复路径，存在严重数据安全风险。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-backup-failed.png`、`docs/testing/ManualTestFindings/fulltest-phase2-operation-error.png`。

#### RUN-034｜P1｜新建地点的地图一直空白，点击地图也不能移动图钉

- 状态：待修复
- 分类：地图、异常状态、输入。
- 影响：“基础数据管理 > 地点 > 添加地点”。
- 复现：打开新建地点页，等待地图加载，分别点击地图中心和偏移位置，再点击“向北”。
- 实际行为：OpenStreetMap/OpenFreeMap 容器始终是浅灰空白块，无瓦片、网格、图钉或加载/失败提示；坐标默认为纬度 0.00000/经度 0.00000，点击不会更改。“向北”按钮可将纬度改为 1.00000，说明数值状态可写，但主地图交互不工作。
- 正确需求：地图需显示可理解的瓦片和明确图钉，点击/长按需更新图钉与坐标；加载或网络失败必须显示原因、重试和不依赖地图的纬经度/搜索备选输入，不得在无说明时将用户地点默认到几内亚湾。
- 用户风险：用户无法选择真实地点，地图分析、商户关联和交易位置可被错记到 (0,0)。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-location-create.png`、`docs/testing/ManualTestFindings/fulltest-phase2-location-map-tap.png`、`docs/testing/ManualTestFindings/fulltest-phase2-location-north.png`。

#### RUN-035｜P1｜“语言与地区”缺少承诺的每周起始日设置，部分时区名称也被误标为 GMT

- 状态：待修复
- 分类：设置缺失、本地化、时区。
- 影响：“设置 > 语言与地区”。
- 复现：打开页面并检查全部内容；打开账本时区工作表查看前几个 IANA/兼容 ID。
- 实际行为：上级菜单明确写“界面语言、日期格式、时区和每周起始日”，但页面只有语言、日期格式和账本时区，不可滚动且完全没有每周起始日。时区列表又将 `ACT`、`AET`、`AGT`、`ART`、`AST` 等不同 ID 的本地化名称全部显示为“格林尼治标准时间”，无法区分。
- 正确需求：页面必须提供“周一/周日/跟随地区”的每周起始日选择、当前选中语义和即时预览；时区列表应优先显示规范 IANA ID，并用与该 ID/当前偏移真实对应的本地化名称，不得把不同地区都标成 GMT。
- 用户风险：无法配置周统计口径，时区选择也容易出错，会连带影响周报表、账单与日期归类。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-language-settings.png`。

#### RUN-036｜P2｜金额隐私误伤“关于”页的非金融许可证名称

- 状态：待修复
- 分类：隐私呈现、文本完整性、合规。
- 影响：开启“金额默认隐藏”时的“设置 > 关于 > 开源许可”。
- 复现：保持金额隐藏开启，打开关于页并检查开源许可列表。
- 实际行为：AndroidX、Jetpack Compose、SQLCipher、Tink、Apache Commons、FastExcel、Vico、ACRA 等条目正常显示，但中间至少一个开源条目的整个库名/许可证文本被替换为 `••••`。该内容不是金额，不应被隐藏。
- 正确需求：金额隐私只能在结构化金额组件/明确的财务字段上生效，不得通过通用字符串形状误判非金融文本；版本、库名、链接、许可证和法务信息必须始终完整可读。
- 用户风险：开源告知不完整，并证明金额红正在对任意非金融文本产生副作用。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-about.png`。

#### RUN-037｜P1｜固定报表“消费地图”无法打开

- 状态：待修复
- 分类：固定报表、加载失败、空态。
- 影响：“分析 > 查看全部固定报表 > 消费地图”。
- 复现：打开固定报表列表并点击“消费地图”。
- 实际行为：页面只剩“重试”和“返回”，无地图、无数据、无空态说明；重试不能恢复，整个固定报表不可用。
- 正确需求：有地点数据时正常显示地图聚合；没有地点数据时显示明确且可执行的空态；真实加载失败时显示用户可理解的原因，并支持可靠重试或安全返回。
- 用户风险：用户完全无法使用消费地图，也无法区分“没有地点数据”和“系统加载失败”。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-consumption-map-failed.png`。

#### RUN-038｜P1｜从未编辑的消费地图返回却弹出“放弃更改”确认

- 状态：待修复
- 分类：导航、脏状态、确认弹窗。
- 影响：“消费地图”失败页返回流程。
- 复现：进入消费地图失败页，不进行任何编辑，点击返回。
- 实际行为：应用弹出只有“继续编辑 / 放弃更改”的未保存更改确认；该页面没有可编辑内容，用户也从未产生改动。
- 正确需求：只读固定报表和加载失败页不应持有编辑脏状态；返回应直接回到固定报表列表。仅当用户真实修改可保存内容时才允许显示放弃更改确认，并应提供明确标题/说明。
- 用户风险：用户会误以为有数据被改动，返回路径增加无意义阻碍，也表明自定义报表的编辑状态泄漏到固定报表。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-map-back-discard.png`。

#### RUN-039｜P1｜报表“应用筛选”被未保存更改确认阻断

- 状态：待修复
- 分类：报表筛选、导航、状态管理。
- 影响：多维交叉分析等固定报表的“筛选与粒度”页面。
- 复现：进入多维交叉分析 → 筛选与粒度，勾选/取消任一指标，再点击“应用筛选”。
- 实际行为：点击主操作后没有应用筛选并返回报表，而是弹出“继续编辑 / 放弃更改”；继续编辑回到原页，放弃更改则丢弃选择，导致筛选无法完成。
- 正确需求：“应用筛选”应提交当前合法选择并返回报表刷新结果；未保存更改确认只应拦截用户主动离开且确有未提交更改的场景，不能拦截提交按钮自身。
- 用户风险：报表筛选核心交互形成死循环，用户不能改变指标或粒度。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-multidimensional-filter.png`、`docs/testing/ManualTestFindings/fulltest-phase2-report-filter-apply-blocked.png`。

#### RUN-040｜P2｜导出完成页“打开”和“查看位置”点击无反馈

- 状态：待修复
- 分类：导出、文件访问、交互反馈。
- 影响：报表 CSV 导出完成页。
- 复现：完成一次报表导出，分别点击“打开”和“查看位置”。
- 实际行为：两项操作点击后页面与前台应用完全不变，也没有提示缺少可处理应用、文件路径或失败原因；同页“分享”和“在操作中心查看”可以正常工作。
- 正确需求：“打开”应通过系统文件类型处理器打开导出结果；“查看位置”应打开系统文件管理器并定位文件或至少明确显示保存目录。设备没有对应处理器时必须给出可理解提示和替代动作（分享/复制路径）。
- 用户风险：导出成功后用户无法从应用内确认或定位文件，并会把无反馈理解为按钮失效。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-export-operation-center.png`、`docs/testing/ManualTestFindings/fulltest-phase2-report-share-sheet.png`。

#### RUN-041｜P2｜“重建分析投影”执行后没有进度或完成反馈

- 状态：待修复
- 分类：数据完整性、后台操作、反馈。
- 影响：“数据完整性报告”底部操作。
- 复现：运行完整性检查后点击“重建分析投影”。
- 实际行为：页面短暂回到检查结果顶部，随后按钮原样恢复；没有确认、进度、成功/失败结果、时间戳或操作中心入口，用户无法判断是否真正执行。
- 正确需求：重建前说明作用与影响；执行后提供明确的进行中状态，并在完成时显示成功结果/失败原因及最新完成时间。若任务进入后台，应提供操作中心入口且防止重复提交。
- 用户风险：用户可能重复触发昂贵重建，也无法确认分析数据是否已经修复。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-data-integrity-actions.png`。

#### RUN-042｜P1｜固定报表列表的“账户余额与核心净金融资产”落入滚动死区而无法打开

- 状态：待修复
- 分类：列表布局、安全区、可达性。
- 影响：“查看全部固定报表”列表。
- 复现：停在列表顶部，尝试点击最底部的“账户余额与核心净金融资产”；再向下滚动并寻找该项。
- 实际行为：该卡片中心位于屏幕底部安全区之外，按可访问性引用或可见部分点击均无响应；一次向下滚动后列表直接从“分期余额与费用”开始，该项落在前后两个滚动位置之间，无法重新点击。
- 正确需求：列表末端/分页交界处的每个卡片都必须能够完整滚入内容安全区并可点击；滚动不得跳过任何项目，自动化/无障碍焦点也应先滚入视口再激活。
- 用户风险：账户余额与核心净金融资产这一固定报表对正常触控、键盘和无障碍用户均不可达。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-fixed-report-clipped.png`。

#### RUN-043｜P1｜报表“下一期”控件没有无障碍名称且语义点击区仅约 15 px

- 状态：待修复
- 分类：报表期间导航、无障碍、触控目标。
- 影响：所有固定报表顶部的期间切换器。
- 复现：打开任一固定报表，切到上一期，再检查日期右侧的下一期控件及其无障碍树。
- 实际行为：左侧按钮可读为“上一期”，右侧图标按钮没有任何名称；其按钮语义矩形宽度约 15 px，交互快照也完全不列出该控件。读屏用户不知道它的作用，键盘/自动化无法可靠选择。
- 正确需求：两个方向都应有明确且随状态正确的名称（“上一期”“下一期”），报告当前期间和禁用边界；触控/语义点击区均至少满足 48dp，且焦点顺序位于日期范围两侧。
- 用户风险：无障碍用户切到历史期间后可能无法返回当前期间，小触控目标也易误触或无法触发。
- 证据：`docs/testing/ManualTestFindings/fulltest-phase2-report-period-navigation.png`。

### 阶段 2 完成结论

- 已基于最新 Debug 安装包完成纯设备黑盒测试；设备测试期间未读取源码、未运行 Gradle。
- 已覆盖：5 个顶层导航域、全部“更多”入口、记账与其他交易类型、流水/详情/历史/筛选/回收站、账户/预算/项目/目标/信用/分期/贷款/互请、模板/周期/候选、固定与自定义报表、数据完整性、导入/导出/备份/恢复/操作中心、主数据管理、保险库、全部设置、桌面小组件、系统文件选择/分享、冷启动、大字体与显示缩放。
- 已验证代表性真实写入与回读、编辑、退款、归档/删除、目标拨入/释放、报表 CSV 导出与系统分享；未执行“清除本地数据”“云端删除”和真实恢复覆盖等不可逆操作。
- 大字体与显示缩放测试后已恢复原系统设置；应用金额隐私、主题、日期格式和可见币种等测试设置亦已恢复。
- 本阶段新增运行时问题共 `RUN-001` 至 `RUN-043`，均待第三阶段修复。

## 阶段 4：修复后全量设备回归新增发现

### 回归约束与覆盖说明

- 回归日期：2026-09-01；设备：Android Emulator（API 36）。
- 回归前重新执行 `:app:assembleDebug`、停止 Gradle daemon，并将最新 `app-debug.apk` 安装到模拟器；进入设备测试后未读取源码、未再次运行 Gradle。
- 使用 agent-device 重新遍历五个顶层导航域、记账与专项交易、流水与详情、账户、预算、分析报表，以及“更多”中的项目、目标、信用、分期、贷款、互请、模板、周期、数据传输、操作中心、基础数据、保险库和全部设置入口。
- 已复测选择器搜索/应用、金额隐私开关、图表数据表、日期选择器、系统文件/目录选择器、表单校验、返回与放弃流程；未确认清除本机数据、删除云端备份、真实恢复覆盖等不可逆操作。
- 下列项目是第三阶段修复完成后新发现的问题。依照本次任务约束，仅忠实记录，不在本轮继续修复。

#### REG-001｜P1｜合法金额的内部转账仍然保存失败，且失败原因不可操作

- 状态：回归新增，未修复。
- 分类：核心记账、转账、错误反馈。
- 影响：“记账 > 其他交易 > 转账”。
- 复现：保留页面自动选择的转出账户“现金钱包 · CNY”和转入账户“回归信用卡 · CNY”，输入 `12.34`，点击“保存”。
- 实际行为：页面显示红色提示“保存失败；输入和金额证据仍保留在本机内存中。”，没有指出哪个字段或业务规则不合法，交易也未写入；两个账户均由界面自动选中，用户没有得到“信用账户不可作为普通转账目标”等前置限制。
- 正确需求：若该账户组合允许内部转账，应原子保存并在流水与两端账户回读；若信用账户必须走“信用卡还款”等专项流程，转账选择器就不得自动选择/允许该目标，并应在提交前给出字段级、可执行的说明与正确入口。
- 用户风险：用户无法完成最基本的账户间资金移动，也无法根据当前错误信息自行修正。

#### REG-002｜P1｜多个只读详情页的核心内容未进入无障碍树

- 状态：回归新增，未修复。
- 分类：无障碍、详情页、语义完整性。
- 影响：至少包括“交易详情”“目标详情”和现金账户详情。
- 复现：分别打开已有交易、目标和现金账户详情；等待页面稳定后重复获取交互/无障碍快照。
- 实际行为：“交易详情”快照只剩“有效”和“返回”，类型、分类、商户、账户、金额、预算、关系和来源均不可读；“目标详情”只暴露“返回”；现金账户详情只暴露滚动区域、图表探索/数据表按钮和返回，账户名、余额等静态事实缺失。对应视觉页面仍有内容，因此不是空态。
- 正确需求：所有视觉可见且具有业务含义的静态文本、状态、金额（遵守金额隐私后的等价描述）和字段关系都必须进入语义树，并按标题、字段名、字段值、操作的合理顺序供 TalkBack/键盘访问。
- 用户风险：读屏用户无法了解交易、目标和账户的关键事实，只能听到少量操作控件。

#### REG-003｜P2｜仅触发校验或取消选择器也会把未编辑表单标记为“有未保存更改”

- 状态：回归新增，未修复。
- 分类：表单状态、返回导航、确认弹窗。
- 影响：新建项目、新建预算调整、新建贷款，以及备份位置设置等流程。
- 复现：不修改字段，仅执行以下任一操作后返回：在新建项目点击一次“保存”触发必填校验；进入新建预算调整；在新建贷款点击“下一步”后取消日期选择；在备份位置打开并取消系统文件夹选择器。
- 实际行为：返回时均弹出“放弃未保存的更改？”，要求“继续编辑 / 放弃更改”，即使没有任何可保存值被用户修改。
- 正确需求：脏状态只能由与初始模型不同的可持久化字段变化触发；校验结果、错误展示、焦点变化以及外部选择器取消都不应算作未保存修改。返回未编辑表单应直接离开。
- 用户风险：频繁的虚假确认使用户误以为数据被改动，并增加退出表单的成本。

#### REG-004｜P1｜预算切到历史月份后全局底部导航消失

- 状态：回归新增，未修复。
- 分类：顶层导航、预算、历史月份。
- 影响：“预算”根页面的月份切换。
- 复现：在 2026 年 9 月预算根页确认底部五项导航存在，点击上一月进入 2026 年 8 月并上下滚动。
- 实际行为：历史月份有预算数据，但“记账 / 流水 / 账户 / 预算 / 分析”整条底部导航消失；只有系统返回可恢复当前月根页面及底部导航。
- 正确需求：月份切换属于预算根域内的内容过滤，不应把用户推入没有全局导航的隐式子页面；当前和历史月份都应保留同一底部导航与“预算”选中状态。
- 用户风险：查看历史预算时用户会失去顶层导航，并误以为进入了不同层级或页面被截断。

#### REG-005｜P2｜新建预算调整一打开就显示金额错误

- 状态：回归新增，未修复。
- 分类：表单校验、初始状态、错误时机。
- 影响：“预算 > 预算调整 > 新建调整”。
- 复现：从调整列表打开新建调整，不触摸金额输入，也不点击提交。
- 实际行为：页面立即显示“请输入有效的非负金额。”，把尚未填写的全新表单呈现为错误状态。
- 正确需求：必填/格式错误应在用户编辑过字段、离开字段或主动提交后显示；首次进入应提供中性占位、输入提示和合法范围，不应预先责备用户。
- 用户风险：初始红错态造成紧张感，也与其他新建表单的延迟校验行为不一致。

#### REG-006｜P2｜多处设置与历史页面的顶部标题被页内操作名替代

- 状态：回归新增，未修复。
- 分类：页面标题、导航定位、一致性。
- 影响：屏幕隐私、回收站设置、隐私与诊断、导入历史。
- 复现：依次从“更多”打开上述页面，检查顶部应用栏标题。
- 实际行为：“屏幕隐私”的标题显示为“全局阻止截图”；“回收站设置”显示为“打开回收站”；“隐私与诊断”显示为“查看隐私政策”；“导入历史”显示为“导入”。前三项明显复用了页面中的首个/末个操作标签，无法准确说明当前层级。
- 正确需求：顶部应用栏应分别稳定显示“屏幕隐私”“回收站设置”“隐私与诊断”“导入历史”；页内开关和跳转操作必须保留在内容区，不能覆盖页面标题语义。
- 用户风险：用户和读屏用户难以确认所在页面，返回层级也变得不可预测。

#### REG-007｜P1｜操作中心把失败任务标为“可重试”，却没有“重试”操作

- 状态：回归新增，未修复。
- 分类：后台任务、失败恢复、操作中心。
- 影响：“数据传输中心 > 操作中心”的失败导入任务。
- 复现：打开状态为“导入，失败，可重试”的任务，展开“查看错误”。
- 实际行为：任务说明要求用户“查看说明或重试”，展开后可看到 `IMPORT_UNSUPPORTED_SOURCE`，但卡片和展开区都只有“查看错误”，没有“重试”按钮或返回导入并保留来源的入口。
- 正确需求：只有真正提供恢复动作的任务才应标记“可重试”；重试按钮应重新进入安全的预览/校验流程，保留可复用输入并避免重复写入。无法重试时应改为明确的终止状态和下一步建议。
- 用户风险：用户被告知可以恢复，却找不到任何可执行路径，失败任务只能永久滞留。

#### REG-008｜P1｜“更多 > 分类”稳定把应用退到桌面，分类管理页不可达

- 状态：回归新增，未修复；连续复现两次。
- 分类：主数据管理、导航、运行时失败。
- 影响：“更多 > 基础数据 > 分类”。
- 复现：从记账或分析页打开“更多”，滚动到“基础数据”，点击“分类”。
- 实际行为：应用立即失去前台并显示系统桌面，只留下“当前没有可选数据”的短暂提示；分类列表/管理页没有打开。重新点击桌面的应用图标后只回到记账根页。相同路径连续两次结果一致。
- 正确需求：分类入口必须打开支出/收入分类管理，显示现有“餐饮”“工资”等分类及新建、编辑、层级、图标和默认值操作；数据为空时也应留在应用内显示规范空态，绝不能退出到 Launcher。
- 用户风险：分类管理主入口完全不可用，并表现得像应用崩溃；虽然记账根页仍有“创建分类”旁路，但无法替代完整管理。

#### REG-009｜P1｜选择项与开关缺少“当前选中/开启”无障碍状态

- 状态：回归新增，未修复。
- 分类：无障碍、选择控件、设置。
- 影响：外观主题与动态色/金额隐私/减少动画、屏幕隐私、隐私与诊断、回收站保留期等设置。
- 复现：打开上述设置页，检查每个单选项和开关的交互树，再切换可安全恢复的开关并重新检查。
- 实际行为：相关控件只暴露为带文本标签的普通 `View`，没有 switch/radio 角色，也没有 checked/selected/on/off 状态；切换前后读屏语义没有可判断的状态变化。
- 正确需求：所有单选项、复选项和开关都必须暴露正确角色、可访问名称、当前状态、可用状态及状态变化通知；分组还应说明互斥关系和当前选择。
- 用户风险：TalkBack 用户无法知道设置当前值，也无法确认一次点击是否生效，隐私类开关尤其存在误配置风险。

### 阶段 4 回归结论

- 第三阶段登记的 `SRC-001` 至 `SRC-022` 与 `RUN-001` 至 `RUN-043` 已逐项修复并完成构建、单元测试、验证脚本和设备回归。
- 本轮新增 `REG-001` 至 `REG-009` 共 9 项发布前问题；按任务要求已完整记录但未继续修复。
