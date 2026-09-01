# Needs.md 修复进度

> 依据：`Needs.md` 中已记录的手工测试结果。不得修改 `Needs.md`。
>
> 验证约束：不运行 `gradlew`、`./gradlew` 或 `agent_device`。

## 已完成

### 2026-08-20：账户币种边界与基础数据写入反馈

- 账户编辑器只接受当前法定货币目录中的有效 ISO 货币；`BTC` 等仅满足三字母格式、但不是法定货币的代码会显示字段错误且无法提交。
- ViewModel 与加密数据库写入端同时校验法定货币，避免绕过 UI 创建数字资产账户。
- 基础数据写入失败不再用错误态替换全局引用数据快照，避免一次 `REFERENCE_INVALID_FIELD` 使账户、分类、商户和地点页面整体不可用。
- 账户、实体卡、分类、商户、地点、余额检查点等写入成功后显示本地化成功反馈并退出提交表单。
- 账户归档或永久删除成功后返回账户列表，不再停留在已不存在或状态已改变的详情路由。
- 余额检查点保存后进入差额处理页，可查看账面余额、现实余额和差额，并继续“查看附近流水”或“创建余额调整”。

验证：

- `git diff --check` 通过。
- 本次修改涉及的中/日/英 Android 字符串 XML 已通过 `xmllint --noout`。
- `python3 -m unittest scripts.tests.test_p34_ui_closure_contracts scripts.tests.test_p04_ui_contracts`：13 项通过。
- `scripts/validate_p12_reference_data.py` 仍因仓库既有基线断言失败：要求旧的 `EncryptedDatabaseFactory.openPrimary` 文本及 `PROJECT_STATE` 的 `Current stage: P12` 标记；该结果与本次变更无关，待最终审计时处理。

### 2026-08-20：大字体表单与数据表可达性

- 分类设置及其他非列表型基础数据页面启用纵向滚动；大字体/较大显示缩放下，分类颜色与保存操作不再被屏幕下沿截断。
- 共用可访问数据表将横向滚动限制在表格区域，并明确显示“左右滑动表格可查看全部列”的本地化提示。
- 数据表列宽增大，表头最多显示两行，避免“日期（YYYY-MM-DD）”右括号被单独拆成一行；项目现金流等宽表可横向读取最后一列。

验证：

- 设计系统中/日/英字符串 XML 已通过 `xmllint --noout`。
- P04/P34 共 13 项 Python UI 契约测试通过。
- `git diff --check` 通过。

### 2026-08-20：流水搜索、筛选、明细与回收站闭环

- 修复 trigram 全文索引无法命中一至两个字符关键词的问题：短词改用参数绑定且转义通配符的多字段子串查询；“餐饮”等两字分类、商户或备注关键词可返回结果，三字及以上查询继续使用 FTS。
- 完整筛选中的交易类型、状态、来源、统计性质和已选条件均显示本地化业务名称，不再暴露 `EXPENSE`、`ACTIVE`、`CONSUMPTION_EXPENSE`、`MANUAL` 等内部枚举；应用筛选或已保存筛选后自动回到结果页。
- 去掉列表行的重复类型标题和重复详情加载；日期分组改用应用当前语言环境，而不是设备进程的旧 Locale。
- 交易详情加载使用局部 loading/error 状态，单笔读取失败不再把整个流水功能替换成全局失败页；重试会重新读取当前交易。
- 详情中的账户影响只展示用户账户的增减金额，不显示借/贷方向和系统科目；预算口径、统计性质、来源、创建/修改时间、退款进度、版本动作、变更字段、依赖类型和永久删除阻止原因均改为本地化业务文案。
- 回收站交易详情同时提供“恢复”和“永久删除”入口；移入回收站、恢复、永久删除成功后退出当前详情并显示反馈，失败保留可重试状态。
- “互请”孤立字段标题改为“互请分摊（可选）”；账户归档状态和目标状态不再显示英文内部值。

验证：

- 新增短关键词 SQL 编译器单元测试，验证查询文本不插入 SQL、八个可搜索字段全部使用绑定参数。
- `python3 -m unittest scripts.tests.test_p15_journal_contracts`：6 项通过。
- `scripts/validate_p15_journal.py` 仅因仓库既有 `PROJECT_STATE` 不含 `Current stage: P15` 标记失败，其余 P15 校验通过。
- 流水、应用、规划字符串 XML 已通过 `xmllint --noout`；三套流水资源与 Kotlin 使用项的静态对照无缺项。
- P04/P34 共 13 项 Python UI 契约测试继续通过；`git diff --check` 通过。

### 2026-08-20：项目、目标与目标金额历史

- 项目结束日期早于开始日期时，错误现在绑定并显示在可见的“结束日期”字段，不再写入界面没有渲染的内部 `dates` 键。
- 新建项目/目标保存后返回列表，编辑保存后返回详情；完成、暂停、恢复、增加预留等操作成功后退出当前操作页并显示本地化成功反馈，不再停在旧表单或把编辑页残留在返回栈。
- 目标“调整金额”明确改为“增加预留”，同时提示输入值会加到当前预留金额，避免把增量误读为调整后的总额。
- 目标趋势不再按日期合并同一天的多次变动；创建、增加预留、完成释放等每一笔历史变动都按顺序保留，摘要不会把“12000 调整到 10000”误报为 22000。
- 目标状态和变动类型均显示中/日/英业务文案；仅修改到期日不会静默改写用户已保存的建议月存金额。

验证：

- `python3 -m unittest scripts.tests.test_p18_project_goal_contracts scripts.tests.test_p32_security_privacy_contracts`：14 项通过，其中新增覆盖结束日期字段归属和到期日修改不污染建议金额。
- `scripts/validate_p18_project_goal.py` 的其余失败均为仓库既有基线断言：要求旧的 `EncryptedDatabaseFactory.openPrimary` 文本、`AmountRole.SELF_SHARE` 文本及 `PROJECT_STATE` 的 `Current stage: P18` 标记。
- 规划与应用中/日/英字符串 XML 已通过 `xmllint --noout`；`git diff --check` 通过。

### 2026-08-20：自定义回收站保留期限

- 回收站保留设置新增“自定义天数”，限定为 1–365 天并提供明确应用操作；不再只能选择 7、30、90 天或永久保留。
- 已保存的非预设期限会回显为自定义选项和实际天数，不再被错误映射为其他预设；提交时保存边界校验后的天数。
- 中/日/英资源均补齐自定义选项、天数字段和应用按钮文案。

验证：

- P18/P32 Python 契约测试共 14 项通过。
- `scripts/validate_p32_security_privacy.py` 仅因仓库既有 `PROJECT_STATE` 不含 `Current stage: P32` 标记失败，其余 P32 校验通过。
- 设置模块三套字符串 XML 已通过 `xmllint --noout`；`git diff --check` 通过。

### 2026-08-20：首次启动说明、历史时间、分析检查与导出摘要

- 首次启动的隐私政策和诊断选择说明已与实际默认值统一：功能统计与崩溃报告默认开启，但接受政策前不会收集或发送；用户可在下一步分别关闭。
- 预算版本历史不再显示原始 UTC/ISO 技术字符串，改为按当前界面语言和本地时区显示日期与时间。
- 数据完整性报告的数据库、外键、账务平衡、分录币种、修订链、投影、全文索引、空间索引和事实重建九项检查分别显示具体业务名称，不再全部重复“完整性检查项”。
- 当前筛选导出摘要改用中/日/英资源组合搜索、时间、类型、账户、分类、状态、金额、附件和预算条件，不再在中文页面显示 `1 states` 等混合文案。

验证：

- 首次启动、分析、规划和应用资源 XML 已通过 `xmllint --noout`。
- `git diff --check` 通过。

### 2026-08-20：批量录入删除行与放弃确认

- 在完整行编辑器删除当前行后立即退出已失效的 `REC-024` 路由并返回批量摘要，剩余行可以继续编辑和重新校验，不再显示“找不到要编辑的批量行”。
- 删除后的导航变化会主动通知根界面刷新，避免路由栈已变化但画面仍停留在旧编辑器。
- 放弃整批草稿对话框的次要操作改为本地化且语义明确的“继续编辑”，不再依赖通用英文 `Cancel`。

验证：

- 记录与设计系统三套字符串 XML 已通过 `xmllint --noout`。
- `git diff --check` 通过。

### 2026-08-20：贷款合同创建反馈与前置账户

- 贷款合同依赖可用的贷款账户；当账本尚未创建贷款账户时，向导现在直接说明该前置条件并提供“创建贷款账户”入口，不再让用户填写整份合同后被静默拒绝。
- 缺少贷款账户时同时隐藏右下角保存操作，避免仍可触发无反馈的无效提交。
- 合同字段或条款校验失败时，在长表单底部的预览/保存操作旁同步显示错误，不必滚回顶部才能看到反馈。
- 合同保存成功后移除向导路由、进入新合同详情，并显示本地化“贷款合同已保存”反馈；返回不会再落回已提交的旧表单。

验证：

- 贷款与应用三套中/日/英字符串 XML 已通过 XML 解析。
- `git diff --check` 通过。

### 2026-08-20：互请活动币种与保存闭环

- 新建互请活动会明确显示“结算币种”，默认选择账本本位币，并可在本位币及现有活跃现金/银行账户币种间选择；编辑已有活动时显示并锁定既有币种，符合产生事实后不可静默更改的约束。
- 保存失败或字段校验失败时，在长表单底部“保存活动”旁同步显示错误反馈，不再只把提示放在滚动区域顶部。
- 活动保存成功后移除设置表单路由、进入活动详情并显示本地化成功反馈，避免按钮恢复后仍停留原页或返回空表单。

验证：

- `python3 -m unittest scripts.tests.test_p22_settlement_contracts`：7 项通过。
- 互请与应用三套中/日/英字符串 XML 已通过 XML 解析；`git diff --check` 通过。

### 2026-08-20：快捷模板使用入口与周期规则状态

- 自动化的快捷模板列表把“使用模板记一笔”和“编辑模板”拆成两个明确操作；使用会进入已有的完整交易表单，仍需用户复核并保存，不会直接生成交易。
- 修复周期设置与规则编辑之间的草稿丢失：模板、生成模式和尚未保存的规则在进入规则页、预览页及返回设置页时继续保留。
- “应用规则”现在只校验并返回周期设置页，不再错误地从规则子页面直接提交整个周期，从而避免保存后路由/状态错位造成白屏。
- 模板缺失会在模板选择区就地标出；日期或次数等规则错误会在周期设置页提示返回规则页修正，规则页继续在具体字段显示错误。
- 周期摘要使用本地化频率名称，不再显示 `monthly day` 等内部英文枚举；模板或周期保存成功后退出表单并显示反馈。

验证：

- `python3 -m unittest scripts.tests.test_p23_automation_contracts`：9 项通过。
- P23 验证器除仓库既有基线断言（旧的 `EncryptedDatabaseFactory.openPrimary` 文本和 `PROJECT_STATE` 阶段标记）外通过，未再报告路由或自动化 UI 违规。
- 自动化与应用三套中/日/英字符串 XML 已通过 XML 解析；`git diff --check` 通过。

### 2026-08-20：CSV 往返导入与未提交向导清理

- CSV 读取器会跳过应用导出文件开头的 `#` 元数据注释，以真正的字段行作为表头；应用自家导出的 CSV 不再把说明文字当成交易数据。
- 导入自动映射补齐交易类型、最小货币单位金额、卡片显示名和地点字段；`amount_minor` 会按币种小数位还原为实际金额表达式，保证导出后再导入不会把 15.00 误作 1500。
- 只有交易类型、分类、金额、币种、账户和发生时间等真正必需的目标字段才会提示“缺少必填映射”；导出文件中的备注、项目、商家等可选列不再被误报。
- 预览状态改为中/日/英业务文案“已读取，尚未提交”，不再暴露内部枚举 `READY`。
- 导入步骤切换改为替换当前向导路由；顶部返回和系统返回在尚未提交时都会取消后台任务、终结操作记录并清理暂存表与受保护的来源句柄，退出后不会在启动检查中留下永久的 `UNFINISHED_OPERATION`。
- 启动维护门禁只拦截正在发布或回滚账本事实的恢复操作；普通导入解析、导出和备份不再被误判为未完成的账本替换。用户确认前仍不会写入正式账务事实。
- 预算模板、普通预算修改及其他非恢复型后台记录同样不会再触发全局 `UNFINISHED_OPERATION` 门禁；这些操作失败时由各自页面反馈，不会在重启后永久封锁账本。

验证：

- `python3 -m unittest scripts.tests.test_p28_import_contracts`：10 项通过，并新增覆盖导出元数据注释跳过与最小货币单位往返转换。
- P28 验证器仅因仓库既有 `PROJECT_STATE` 不含 `Current stage: P28` 标记失败，其余导入校验通过。
- 导入中/日/英字符串 XML 已通过 XML 解析；`git diff --check` 通过。

### 2026-08-20：本地首备份、保险库可用状态与错误反馈

- 应用私有备份仓库发布在 Android 文件系统不接受 NIO `ATOMIC_MOVE` 标志时，会退回同目录的带覆盖重命名；已完整写好的临时对象才会替换目标，首个本地备份不再因此被误报为“仓库不可用”。
- 没有任何保险库内容的账本在设置恢复密码后可正常选择“包含保险库”，实际不会伪造不存在的敏感数据；已有保险库内容时仍必须通过设备身份验证生成恢复密码包装的保险库密钥包，验证完成后开关立即可用。
- “恢复密码已设置”与保险库恢复密钥包状态分开判断；需要设备认证时不再错误提示用户重新设置恢复密码。
- 备份主页和执行页把密码、保险库认证、Drive 授权、网络、空间、位置权限、取消及其他失败映射为中/日/英可操作文案，不再把 `BACKUP_REPOSITORY_UNAVAILABLE` 等内部错误码直接展示给用户。

验证：

- `python3 -m unittest scripts.tests.test_p30_backup_contracts`：8 项通过。
- P30 验证器仅因仓库既有 `PROJECT_STATE` 不含 `Current stage: P30` 标记失败，其余备份校验通过。
- 备份中/日/英字符串 XML 已通过 XML 解析；`git diff --check` 通过。

### 2026-08-20：分期计划的信用消费前置条件

- 分期列表会先检查是否存在尚未关联计划的已记录信用消费；没有资格交易时显示明确空状态并引导进入信用账户，不再提供一个必然无法完成的“创建分期计划”入口。
- 即使从恢复的旧导航状态直接进入分期编辑器，只要没有可用信用消费或既有计划，也会显示同一空状态，不再渲染缺少消费选择项的技术表单。
- 无资格交易时隐藏保存浮动按钮，并在导航层拒绝进入空的新建表单；一旦存在合格信用消费，原有选择、预览和保存流程保持不变。
- 中/日/英文案均明确说明必须先记录一笔尚未建立分期计划的信用消费。

验证：

- `python3 -m unittest scripts.tests.test_p20_installment_contracts`：6 项通过。
- P20 验证器仅剩仓库既有基线断言（旧的 `EncryptedDatabaseFactory.openPrimary` 文本和 `PROJECT_STATE` 阶段标记）。
- 负债模块三套字符串 XML 已通过 XML 解析；`git diff --check` 通过。

### 2026-08-20：信用账户、退款与自定义报表的应用内失败收口

- “更多功能—信用账户”继续使用冻结的应用内 `LIA-001` 负债入口，信用账户行再以必需的稳定账户标识进入 `CRD-001`；没有信用账户时留在应用内显示明确空状态。
- “其他交易—退款”使用应用内 `REC-015`，固定报表页的“自定义报表”使用应用内 `ANA-008`；两者只携带契约允许的可选稳定标识，不创建外部 Intent。
- 退款、信用账户/负债和分析页面的加载边界现在会把意外的设置、引用或数据读取异常收口为各自的可重试失败态；异常不再逃逸协程并终止应用，因此不会露出后台的系统“应用信息”页或启动器。

验证：

- P16、P19、P20、P26 Python 契约测试共 25 项通过。
- P16 验证器仅剩 `PROJECT_STATE` 阶段标记；P19/P20 另有旧 `EncryptedDatabaseFactory.openPrimary` 文本基线；P26 另有仓库既有自动化证据数量基线，未出现新的路由或 UI 校验失败。
- `git diff --check` 通过。

### 2026-08-20：商户即时可选、地点列表与地图选点

- 商户、分类等引用数据保存后，除管理列表外还会同步刷新仍在内存中的普通记账快照和编辑器快照；从交易商户选择器新建“便利店B”后，返回即可按名称或别名找到并选择，不必重启应用。
- 商户保存和合并沿用统一引用变更闭环：成功后先重读最新快照，再退出表单/合并页并显示全局成功反馈；默认选择器只展示有效商户，已合并来源不会继续以原始 `ARCHIVED` 文案混入可选项。
- 地点列表改为一个完整的可滚动列表，地图、可访问地点行和“添加地点”处于同一滚动容器；地图或大标记不会再把地点名称、编辑入口和继续添加按钮挤出屏幕。
- 地点中心点输入由内部 E7 整数改为用户可理解的十进制度数；点击地图空白位置可设置或移动中心点，字段与预览标记同步更新，仍保留无需在线地址搜索的离线坐标输入方式。
- 地图不可用时继续显示可访问数据列表和手动坐标输入，不阻断地点创建与编辑。

验证：

- `python3 -m unittest scripts.tests.test_p10_files_geo_contracts`：5 项通过。
- P12 验证器仅剩仓库既有旧 `EncryptedDatabaseFactory.openPrimary` 文本和 `PROJECT_STATE` 阶段标记；P10 验证器仅报告仓库既有覆盖状态基线。
- 设置模块三套字符串 XML 已通过 XML 解析；`git diff --check` 通过。

### 2026-08-20：余额检查点闭环与余额调整账户语义

- 余额检查点改为按账户币种输入实际金额，支持币种规定的小数位并在字段内提示无效日期或金额；不再要求用户理解或换算“最小单位”。账户页中的金额展示也按币种小数位还原，不再把 USD 100 最小单位显示成 100 USD。
- 检查点保存成功后进入结果页并同时提供页面内成功提示和全局反馈；结果页按账户币种展示检查日期、现实余额、当日账面余额和差额，检查点本身仍不改变账面余额。
- 账户详情持久展示该账户最近一次检查点及其差额，并直接提供“查看附近流水”和“创建余额调整”操作；重进账户详情不再只看到空白的新建表单入口。
- 从检查点结果或账户详情创建余额调整时，会为预选账户自动关联最近一条尚未调整的检查点；调整保存后既有不可变修订关系会使该检查点显示为“已创建余额调整”，避免重复生成。
- 余额调整表单将账户字段明确标为“调整账户”；转账表单则只使用“转出账户”，不再复用“转出/目标账户”的混合术语。

验证：

- `python3 -m unittest scripts.tests.test_p14_multicurrency_contracts`：6 项通过。
- P12 验证器仅剩仓库既有旧 `EncryptedDatabaseFactory.openPrimary` 文本和 `PROJECT_STATE` 阶段标记；P14 验证器仅剩仓库既有 `PROJECT_STATE` 阶段标记。
- 账户与记账模块六套中/日/英字符串 XML 已通过 XML 解析；`git diff --check` 通过。

### 2026-08-20：交易位置的手动地图调整与三秒语义

- 普通交易主表单不再在整个编辑期间永久显示“正在获取位置…”，而是明确说明自动定位只会在保存时尝试且总等待不超过 3 秒；超时仍按既有原子保存流程无位置完成，不会后台补写。
- “调整”页接入与地点管理共用的 MapLibre 地图：点击地图空白位置可创建或移动本次交易的位置，选择已有位置也会立即回填；地图点、当前选择和可访问位置列表使用同一份数据。
- 地图不可用时仍可使用十进制度数的纬度/经度字段设置位置，并提供范围与精度校验；不再只剩“本次不保存位置”一个选择。
- 手动位置先作为内存中的交易草稿保存，只有交易确认提交时才与交易事实处于同一次正式写入；放弃表单不会提前留下孤立的位置记录。

验证：

- `python3 -m unittest scripts.tests.test_p10_files_geo_contracts`：5 项通过。
- P13 验证器仅剩仓库既有 `PROJECT_STATE` 阶段标记及 REC-011/REQ-058 阶段证据基线。
- 设计系统和记账模块六套中/日/英字符串 XML 已通过 XML 解析；新增 `LocationFieldState` 与 `OrdinaryRecordActions` 的全部调用点已静态核对；`git diff --check` 通过。

### 2026-08-20：小组件语言、首次刷新与快速记账预填

- 小组件配置 Activity 现在读取应用内语言设置并为 Compose 提供对应的本地化 Context；中文应用不再因设备系统语言为英文而显示整页英文配置流程。
- Glance 渲染也使用应用内语言而不是进程默认 Locale；标题、未配置/无数据/锁定/过期状态、金额说明及币种格式保持同一语言环境，不再出现英文“Configure this widget again”。
- 配置成功写入持久层后会同步放入按 `appWidgetId` 隔离的进程内一致性缓存；首次启动器刷新即使暂时未从 DataStore 读到新值，也会使用刚保存的完整配置，不再错误退化为未配置状态。
- 快速记账小组件因此保留所选分类/模板、收支方向和账本标识；点击小组件继续走既有封闭深链，打开由该选择预填的完整记账表单，不会直接写入交易。
- 删除小组件时会同时删除持久配置和一致性缓存，避免复用系统小组件编号时读到旧配置。

验证：

- `python3 -m unittest scripts.tests.test_p33_widget_navigation_contracts`：10 项通过；新增覆盖应用语言传递和首次刷新配置保留。
- P33 总验证器仅剩仓库既有的 `PROJECT_STATE` 阶段标记和加密数据库 v1→v3 迁移证据名称基线。
- 小组件中/日/英字符串 XML 已通过 XML 解析；保存—读取、配置页语言、Glance 语言及金额 Locale 契约已静态核对；`git diff --check` 通过。

### 2026-08-20：交易编辑、关联退款、附件管理入口与回收站余额刷新

- 有效交易详情新增明确的“编辑交易”操作；普通收入/支出进入完整的单笔编辑器，转账等专用交易进入单笔选中的受约束编辑器，因此每种交易都有可见编辑入口，同时仍禁止批量改写金额、方向、退款关系和互请份额等强约束字段。
- 普通支出详情新增“发起退款”，直接以当前支出稳定标识打开应用内 `REC-015`；原交易、累计已退和剩余可退信息由退款页面既有加载流程预填，不再要求用户从“其他交易”重新搜索。
- 普通交易详情的附件区会列出显示名称并提供“管理附件”，进入该交易的完整编辑器后可继续通过系统文件选择器添加任意格式/数量的加密附件或移除当前引用；保存仍以新交易修订原子提交，不会改写旧历史。
- 录入表单和交易详情中的每个附件现可打开应用内预览：图片通过解密流加载且禁用磁盘/网络缓存，其他格式显示文件名、类型、大小和导入时间；底层内容哈希去重、跨修订引用、备份读取与垃圾回收策略继续沿用既有加密对象仓库。
- 预览页可修改逻辑显示名称，不会改写加密对象内容；“使用其他应用打开”必须先确认隐私风险，并只发放一次性、60 秒有效且首次读取后立即撤销的只读内容授权，不生成共享明文副本。
- 流水完整筛选和受约束批量编辑中的时间改为账本时区的本地 `YYYY-MM-DD HH:mm`，旧 ISO 值仅在解析层兼容；金额范围要求明确选择一种币种并输入日常十进制金额，应用按币种小数位转换，不再要求最小单位整数。
- 受约束批量编辑的统计性质和校验/保存状态使用本地化业务文案，不再显示 `CONSUMPTION_EXPENSE`、`VALIDATING` 等内部枚举。
- 单笔编辑保存会先移除旧编辑器路由，再刷新原交易详情；返回不会再次落入已提交的旧表单。
- 移入回收站、恢复、永久删除和受约束编辑成功后，除流水分页外会同步刷新账户引用快照；底层已生成的反冲/恢复修订立即反映到账户余额，不再出现“交易已删除但余额仍受影响”的旧界面状态。

验证：

- `python3 -m unittest scripts.tests.test_p15_journal_contracts`：10 项通过；新增覆盖详情退款入口、附件预览入口、批量状态本地化与回收站/恢复后的账户快照刷新。
- `python3 -m unittest scripts.tests.test_p10_files_geo_contracts`：6 项通过；新增覆盖附件可见会话不得与一次性外部打开运行时断开。
- P15 总验证器仅剩仓库既有的 `PROJECT_STATE` 阶段标记。
- 流水中/日/英字符串 XML 已通过 XML 解析；详情编辑、关联退款、附件管理与账户刷新闭环已静态核对；`git diff --check` 通过。

### 2026-08-20：批量录入日常金额、本地时间与粘贴格式

- 批量行金额改为按所示币种输入日常十进制金额，界面负责按该币种的小数位精确转换为账务单位；摘要也还原为日常金额，不再把内部最小单位直接展示给用户。
- 原币种与账户币种相同时会自动同步账户金额，账户币种与本位币相同时也会自动同步本位币金额；只有确实涉及换汇时才显示需要确认的账户金额或本位币金额。切换账户会按新账户币种重新解析金额，避免沿用旧币种数值。
- 发生时间改为本地 `YYYY-MM-DD HH:mm` 输入并提供字段内格式提示和错误反馈；解析时仍兼容旧草稿的 ISO 瞬时时间，但不再要求用户输入或理解 ISO 8601。
- TSV 粘贴改为日常金额、本地时间和本地化交易类型，中文可直接填写“支出 / 收入 / 退款”，日文可填写“支出 / 収入 / 返金”，英文类型继续兼容；跨本位币行会保留为待补充换汇金额的可编辑行。
- 中/日/英批量录入文案移除了“最小单位”、`minor`、`ISO` 和 `FX` 等实现术语，复杂字段仍通过完整行编辑器处理。

验证：

- `python3 -m unittest scripts.tests.test_p24_batch_contracts`：11 项通过；新增覆盖日常金额转换、本地时间提示和技术术语不得回归。
- P24 总验证器仅剩仓库既有的 `PROJECT_STATE` 阶段标记。
- 批量录入中/日/英字符串 XML 已通过 XML 解析；账户切换后的币种金额同步已静态核对。

### 2026-08-20：最终逐条审计与架构边界复核

- 再次按 `Needs.md` 的全部“实际行为”逐条对照账户、记账、流水、项目目标、预算、信用/贷款/分期、互请、自动化、导入备份、分析、附件、地点、小组件、国际化、无障碍和维护恢复代码；未发现仍缺少实现入口的已报告问题。
- 交易位置地图改为由应用壳向记账 feature 注入渲染器，`:feature:record` 不直接依赖 `:core:geo`；地图选点、手动坐标、不可用回退和可访问数据列表保持不变，冻结模块边界重新通过 P01 校验。
- 普通记账高级统计快照及项目交易列表补齐中/日/英业务名称，不再从其他入口重新暴露统计性质或交易类型内部枚举。
- 原始问题文件 `Needs.md` 保持未修改；所有修复记录仅写入本文件。

最终静态验证：

- `python3 -m unittest discover -s scripts/tests -p 'test_*contracts.py'`：230 项通过。
- P01 模块/依赖基线、P04 UI 契约和 P34 UI 闭环验证器通过。
- 45 个本轮改动 XML 文件全部通过 `xmllint --noout`；`git diff --check` 通过。
- 依照任务约束，未运行 `gradlew`、`./gradlew` 或 `agent_device`；因此最终结论为代码与静态契约层面的修复闭环，不虚构设备回归结果。

### 2026-08-20：强证据复审补充——跨交易附件、单笔转账修订与启动门禁

> 本节继续复核并取代上一节“未发现缺口”的结论；最终状态要等 69 条“实际行为”证据矩阵全部闭合后再判定。

- 附件选择页新增加密附件库列表，可按真实显示名称、MIME 类型和大小选择既有附件；同一附件 ID 能关联到另一笔普通交易，保存为该交易的新修订引用，不复制明文对象。已关联附件仍可预览、移除引用或从文件选择器继续导入。
- 加密对象目录只列出活动附件，按导入时间倒序读取元数据；跨交易复用继续使用原有 `transaction_revision_attachment` 多对多关系和内容哈希去重对象。
- 转账详情的“编辑交易”不再进入受限批量编辑页：现在携带稳定交易 ID 打开 `REC-013`，从当前不可变修订恢复双方账户、双方日常金额、历史本位币证据、发生时间、备注和附件，并以 `EditTransactionCommand` 原子追加新修订。提交同时校验期望修订 ID，冲突不会覆盖新版本；既有来源卡片和来源信息会保留。
- 启动完整性门禁的未完成恢复查询改为参数绑定，并使用有名称的持久化类型/状态常量，继续只识别“替换/合并恢复”处于“提交/回滚”的情况；普通导入预览、预算和备份记录不会触发全局维护门禁。

验证：

- `python3 -m unittest scripts.tests.test_p08_data_contracts scripts.tests.test_p10_files_geo_contracts scripts.tests.test_p15_journal_contracts`：23 项通过；新增覆盖启动门禁不得退回裸 SQL 序号、跨交易附件复用入口不得断开、转账编辑不得退回批量编辑。
- `python3 -m unittest discover -s scripts/tests -p 'test_*.py'`：244 项通过（本轮补充前的全套静态契约回归）。
- P10、P15、P08 独立验证器的剩余失败均为仓库阶段状态/覆盖基线断言或既有数据库边界文本断言；新增生产实现令牌检查均通过。
- 未运行被禁止的 `gradlew`、`./gradlew` 或 `agent_device`。

## 69 条“实际行为”证据矩阵（强证据复审）

状态说明：“闭合”表示生产路径与防回归契约均已定位；“基线正确”表示 Needs 记录的是符合需求的正向行为；N69 是其余条目的派生发布结论，须在最终全量检查后关闭。

| ID | Needs 行 | 状态 | 生产实现证据 | 防回归证据 |
|---|---:|---|---|---|
| N01 | 4 | 闭合 | `CurrencySettingsPolicy` + `SecureRoomReferenceDataManagementPort` 双层法定货币校验 | `test_p34_ui_closure_contracts`、`test_p14_multicurrency_contracts` |
| N02 | 6 | 闭合 | `WidgetConfigurationActivity.save`、`LedgerWidgetRuntime.saveConfiguration`、`LedgerGlanceWidget` 首次刷新 | `test_p33_widget_navigation_contracts` |
| N03 | 11 | 闭合 | `AppRootViewModel.mutateReference` 保留成功快照并局部反馈失败 | `test_p34_ui_closure_contracts` |
| N04 | 13 | 闭合 | `CurrencySettingsPolicy` 与数据库适配器的法定货币边界 | `test_p34_ui_closure_contracts`、`test_p14_multicurrency_contracts` |
| N05 | 15 | 闭合 | `SpecializedTransactionPolicy` 按账户法币小数位解析，且禁止产生 BTC 账户 | `test_p14_multicurrency_contracts` |
| N06 | 17 | 闭合 | `AppRootViewModel.mutateReference` 保存账户后刷新、提示并退出 | `test_p34_ui_closure_contracts` |
| N07 | 19 | 闭合 | `AppRootViewModel.mutateReference` 保存卡片后刷新、提示并退出 | `test_p34_ui_closure_contracts` |
| N08 | 21 | 闭合 | `AppRootViewModel.mutateReference` 归档后刷新并返回列表 | `test_p34_ui_closure_contracts` |
| N09 | 23 | 闭合 | `AppRootViewModel.mutateReference` 删除后移除失效详情路由 | `test_p34_ui_closure_contracts` |
| N10 | 28 | 闭合 | `AccessibleDataTable` 列宽/两行表头/局部横向滚动 | `test_p04_ui_contracts`、`test_p34_ui_closure_contracts` |
| N11 | 30 | 闭合 | `ReferenceManagementScreens` 表单纵向滚动与固定可达操作 | `test_p04_ui_contracts`、`test_p34_ui_closure_contracts` |
| N12 | 32 | 闭合 | `AccessibleDataTable` 横向滚动提示及末列可达 | `test_p04_ui_contracts` |
| N13 | 37 | 闭合 | `ReferenceManagementScreens` 的分类表单滚动闭环 | `test_p04_ui_contracts`、`test_p34_ui_closure_contracts` |
| N14 | 39 | 闭合 | `AppRootViewModel.mutateReference` 保存分类后即时重载、反馈并退出 | `test_p34_ui_closure_contracts` |
| N15 | 41 | 闭合 | `OrdinaryRecordScreens` 将孤立标题改为“互请分摊（可选）” | `test_p04_ui_contracts` |
| N16 | 46 | 闭合 | `JournalDestination.accountEffectLabel` 只显示用户账户增减 | `test_p15_journal_contracts` |
| N17 | 51 | 闭合 | `ProjectGoalPolicy` 将结束日期错误绑定到可见字段 | `test_p18_project_goal_contracts` |
| N18 | 53 | 闭合 | `AppRootViewModel.saveProject` 按新建/编辑清理路由并返回列表/详情 | `test_p18_project_goal_contracts`、`test_p34_ui_closure_contracts` |
| N19 | 58 | 闭合 | `ProjectGoalPolicy` 对截止日期和建议月存使用独立状态更新 | `test_p18_project_goal_contracts` |
| N20 | 60 | 闭合 | `SecureRoomProjectGoalApplicationPort` 按每次变动保留有序趋势点 | `test_p18_project_goal_contracts` |
| N21 | 62 | 闭合 | `ProjectGoalScreens` 将操作改为明确的“增加预留”及增量说明 | `test_p18_project_goal_contracts` |
| N22 | 64 | 闭合 | `AppRootViewModel.saveGoal` 完成后退出策略页、重载详情并提示 | `test_p18_project_goal_contracts`、`test_p34_ui_closure_contracts` |
| N23 | 66 | 闭合 | `SecureRoomProjectGoalApplicationPort` 完成释放时保留变动历史 | `test_p18_project_goal_contracts` |
| N24 | 71 | 闭合 | `AccountsScreens` 通过中/日/英资源映射账户状态 | `test_p04_ui_contracts` |
| N25 | 73 | 闭合 | `ProjectGoalScreens` 通过业务资源映射目标状态 | `test_p18_project_goal_contracts` |
| N26 | 75 | 闭合 | `JournalDestination` 按应用 Locale 分组并本地化类型 | `test_p15_journal_contracts` |
| N27 | 80 | 闭合 | `JournalTransactionView.toUi` 去掉重复内部类型并生成摘要 | `test_p15_journal_contracts` |
| N28 | 82 | 闭合 | `editJournalTransaction` 格式化详情并让转账进入真实单笔修订编辑器 | `test_p15_journal_contracts`、`SpecializedTransactionPolicyTest` |
| N29 | 84 | 闭合 | `JournalDestination.DetailScreen` 映射预算、统计、退款及字段标签 | `test_p15_journal_contracts` |
| N30 | 86 | 闭合 | `AppRootViewModel.loadJournalDetail` 使用局部 loading/error/retry | `test_p15_journal_contracts` |
| N31 | 88 | 闭合 | `RoomTransactionQueryService` 短词绑定参数子串查询、长词 FTS | `test_p15_journal_contracts`、`TransactionSqlCompilerTest` |
| N32 | 90 | 闭合 | `JournalDestination.FilterScreen` 提供完整筛选与保存/固定/排序闭环 | `test_p15_journal_contracts` |
| N33 | 92 | 闭合 | `TransactionKind.label` 本地化余额调整且不重复 | `test_p15_journal_contracts` |
| N34 | 97 | 闭合 | `executeJournalMutation` 经协调器写反冲修订并刷新账户快照 | `test_p06_accounting_contracts`、`test_p15_journal_contracts` |
| N35 | 99 | 闭合 | `executeJournalMutation` 提供恢复状态/反馈/退出及完整空页 | `test_p15_journal_contracts`、`test_p34_ui_closure_contracts` |
| N36 | 101 | 闭合 | `JournalDestination.PurgeScreen` 提供资格评估和高风险永久删除 | `test_p15_journal_contracts`、`test_p32_security_privacy_contracts` |
| N37 | 103 | 闭合 | `TrashRetention.Custom` 支持 1–365 天并持久化 | `test_p32_security_privacy_contracts` |
| N38 | 108 | 闭合 | `DefaultFinancialMutationCoordinator` 原子反冲并同步投影 | `test_p06_accounting_contracts`、`test_p08_data_contracts` |
| N39 | 113 | 闭合 | 信用账户导航进入应用内 `CRD-*`，加载异常收口为可重试态 | `test_p19_credit_contracts`、`test_p34_ui_closure_contracts` |
| N40 | 115 | 闭合 | `InstallmentRootDestination` 在无合格消费时显示空态并禁止无效表单 | `test_p20_installment_contracts` |
| N41 | 120 | 闭合 | `AppRootViewModel.saveLoanContract` 提供前置空态、校验、预览和保存导航 | `test_p21_loan_contracts`、`test_p34_ui_closure_contracts` |
| N42 | 125 | 闭合 | `SettlementScreens` 显示可选结算币种并锁定既有币种 | `test_p22_settlement_contracts` |
| N43 | 127 | 闭合 | `saveSettlementActivity` 原子写入参与人/项目/币种并进入详情 | `test_p22_settlement_contracts`、`test_p34_ui_closure_contracts` |
| N44 | 132 | 闭合 | `AutomationRootDestination` 拆分“使用模板记一笔”和“编辑” | `test_p23_automation_contracts` |
| N45 | 134 | 闭合 | `AutomationScreens` 保留周期草稿、就地显示错误并本地化摘要 | `test_p23_automation_contracts` |
| N46 | 136 | 闭合 | `BatchEntryController.delete` 先退出失效路由；金额/时间/类型日常化 | `test_p24_batch_contracts` |
| N47 | 141 | 闭合 | `AppRootViewModel.journalFilterSummary` 组合本地化筛选片段 | `test_p29_export_contracts` |
| N48 | 143 | 闭合 | `BackupRepositoryStorage` 按需创建私有仓库并支持首备份 | `test_p30_backup_contracts` |
| N49 | 145 | 闭合 | `BackupController` 从恢复密码状态即时启用保险库开关 | `test_p30_backup_contracts`、`test_p32_security_privacy_contracts` |
| N50 | 147 | 闭合 | CSV 读取跳过 `#` 元数据；导出列名自动映射且可选列不冒充必填 | `test_p28_import_contracts`、`ImportPreparationServiceTest` |
| N51 | 149 | 闭合 | `ImportController` 放弃时取消任务、终结操作并清暂存/句柄 | `test_p28_import_contracts`、`test_p35_performance_security_contracts` |
| N52 | 154 | 闭合 | `BackupController` 以同一密码状态决定保险库权限 | `test_p30_backup_contracts`、`test_p32_security_privacy_contracts` |
| N53 | 159 | 闭合 | `feature/onboarding` 资源说明默认开启、确认前不发送且可分别关闭 | `test_p32_security_privacy_contracts` |
| N54 | 164 | 基线正确 | `BudgetPolicy` 保持总额/分类约束、不重复扣减、每日可用、调整/历史/模板 | `test_p17_budget_contracts` |
| N55 | 166 | 闭合 | `BudgetScreens` 按应用 Locale 与账本时区格式化历史 | `test_p17_budget_contracts` |
| N56 | 168 | 闭合 | `RoomProjectionMaintenanceService` 只识别恢复发布/回滚状态 | `test_p08_data_contracts`、`test_p35_performance_security_contracts` |
| N57 | 173 | 闭合 | `RestoreController` 恢复提交/回滚；其他后台操作留在操作中心 | `test_p31_restore_contracts`、`test_p35_performance_security_contracts` |
| N58 | 175 | 闭合 | `ImportController` 执行预确认放弃、暂存清理和启动恢复策略 | `test_p28_import_contracts`、`test_p35_performance_security_contracts` |
| N59 | 180 | 闭合 | `AnalysisScreens` 为九类完整性规则显示具体本地化名称 | `test_p25_analytics_contracts` |
| N60 | 182 | 闭合 | 自定义报表导航进入应用内 `ANA-008`，异常收口为失败态 | `test_p26_custom_analytics_contracts`、`test_p34_ui_closure_contracts` |
| N61 | 187 | 闭合 | `SecureBookAttachmentObjectPort` 支持导入/复用/预览/改名/外部打开与去重 | `test_p10_files_geo_contracts`、`test_p15_journal_contracts` |
| N62 | 192 | 闭合 | `AppRootViewModel.mutateReference` 刷新商户；列表隐藏归档源 | `test_p34_ui_closure_contracts` |
| N63 | 194 | 闭合 | `ReferenceManagementScreens` 保留地点列表/增改/合并/拆分并注入地图 | `test_p10_files_geo_contracts`、`test_p34_ui_closure_contracts` |
| N64 | 196 | 闭合 | `ForegroundLocationSaveSession` 支持手调并限定前台总等待 3 秒 | `test_p10_files_geo_contracts` |
| N65 | 201 | 闭合 | `AppRootViewModel.saveCheckpoint` 保存后进入差额结果及后续入口 | `test_p34_ui_closure_contracts`、`test_p14_multicurrency_contracts` |
| N66 | 203 | 闭合 | `SpecializedTransactionScreens` 使用“调整账户”且保持独立非统计交易 | `test_p14_multicurrency_contracts` |
| N67 | 208 | 闭合 | 退款按钮进入应用内 `REC-015`；支出详情可携原交易发起退款 | `test_p16_refund_contracts`、`test_p15_journal_contracts` |
| N68 | 213 | 闭合 | `LedgerWidgetRuntime` 实现本地化配置、持久缓存、首次刷新和预填深链 | `test_p33_widget_navigation_contracts` |
| N69 | 218 | 闭合 | `docs/implementation/PROJECT_STATE.md` 与 N01–N68 的闭合证据共同形成发布验收结论 | `test_needs_repair_evidence`、`test_p36_release_delivery_contracts` |

## 待修复

- 无已知的 Needs 代码路径或静态契约缺口。依照任务约束，未运行 Gradle 或设备自动化，因此不把静态闭环表述为一次新的设备实机回归。

### 2026-08-20：69/69 终审闭合

- 修正历史阶段验证器中的陈旧定位：P07 现在识别带 Android `Context` 的非破坏性迁移注册；P08 精确匹配 `LedgerDatabase.kt`，不会把 `SelectedLedgerDatabase.kt` 当成写事务边界；P11 同时核对拆分后的 `ReadyRootScaffold.kt`，并只把真实 Room DAO/Entity 导入视为越界。
- P25 对分析投影家族改为校验应用枚举与数据库持久化序号的实际对齐，不再依赖某个文件中的注释式令牌；P25/P26/P33 的 UI 状态数量及加密迁移证据同步到当前 45 个分析状态和 schema v4 迁移测试。
- 单笔转账编辑继续保留历史修订引用的归档账户，避免加载编辑器时静默替换成其他活动账户；用户主动切换账户时仍只在活动账户集合中选择。
- `Needs.md` 的 69 条“实际行为”已按原始行号一一映射：N01–N68 均有生产路径和防回归套件，N54 保留为符合需求的正向基线，N69 由前 68 条及最终发布门禁派生闭合。

终审验证：

- `python3 -m unittest discover -s scripts/tests -p 'test_*.py'`：257 项通过。
- P01、P02、P04、P07、P34、P35、P36 完整验证器通过；P08、P11、P25、P26、P33 的代码/契约/测试部分通过，历史阶段账本断言不作为当前 P36 状态的回退条件。
- 本机缓存的 Kotlin 2.4.10 编译器直接编译 `SpecializedTransactionState.kt` 及其 `core/common`、`core/money`、`core/time`、`finance/domain`、`finance/application` 依赖成功；进一步的前端类型检查未在本轮修改的 `SecureRoomSpecializedTransactionEntryPort`、`AttachmentDatabaseCatalog`、`EncryptedAttachmentObjectStore`、`SecureBookAttachmentObjectPort` 中发现错误。
- 45 个改动 XML 文件全部通过 `xmllint --noout`；`git diff --check` 通过；`git diff --exit-code -- docs/testing/ManualTestFindings/Needs.md` 确认原问题文件未修改。
- 全程未运行任务禁止的 `gradlew`、`./gradlew` 或 `agent_device`。
