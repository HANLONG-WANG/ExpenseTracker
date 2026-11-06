# 余量正式文件协议 v1

下面前四种 UTF-8 JSON 格式用于文件转换，不是备份或同步。银行文件与小票商品文件用途不同。应用不会OCR、连接金融机构或自动上传文件；用户在应用内主动把原始资料交给已连接的云端分析台，分析台按本说明生成文件，返回后用户须在应用预览并确认保存。这些云端转换格式不提供本地导出或手动选文件导入入口；用户手动的本地完整备份/恢复另用 `.yohaku` ZIP / `yohaku.backup` v1（整份替换、不加密、不同步、不自动备份，系统云备份仍关闭），见[实现规格](../implementation-spec.md#34-手动完整备份--恢复formatversion1)。

| kind | Schema | 用途 |
| --- | --- | --- |
| `yohaku.import-identifiers` | [schema](import-identifiers.v1.schema.json) | 应用随云端流水任务附带的当前账本及账户标识，供转换时引用 |
| `yohaku.statement` | [schema](statement.v1.schema.json) | 独立银行/信用卡核对资料，不产生正式账目 |
| `yohaku.receipt-bundle` | [schema](receipt-bundle.v1.schema.json) | 小票图片ZIP中的 `manifest.json` |
| `yohaku.receipt-items` | [schema](receipt-items.v1.schema.json) | 回填已有小票商品，不改交易金额 |

每种完整示例在 [examples](examples/)；示例UUID只用于说明，不能替代用户实际ID。bundle示例的校验值与大小是占位示意，不能作为真实ZIP。实际打包必须计算小票图片字节的SHA-256和大小。

## 文件转换的共通字段与约束（前四种格式）

`kind` 必须准确，`version` 必须是整数1；未知版本拒绝。兼容的额外字段可忽略，不能依赖额外字段产生业务效果。未知值使用 `null` 或省略可选字段，不能填0、空日期或猜测金额。所有ID均为规范36字符UUID（大小写可接受，但生成和交换时保持原ID文本）；账本、账户、交易、小票、图片ID只从应用提供的标识/清单复制。file_id、row_id、bundle_id、item_id由生成方分配稳定UUID，修正同一资料时保留ID；不要每次转换同一文件都换ID。

货币对象为 `{"currency":"JPY","amount":"1200"}`，支持JPY/CNY/USD。JPY整数；CNY/USD恰好两位小数，如 `"12.50"`；不允许指数、正负号、千位分隔、前导0或JSON数值。金额非负，不能超过应用Long最小货币单位范围（9223372036854775807）。方向独立表达。数量和单价允许更多小数位；汇率必须大于0。未知金额与已知0不同。

日期为有效公历 `YYYY-MM-DD`。明确时刻可用 `occurred_at`（带偏移ISO时间）和 `time_zone`（IANA时区），不从摘要猜测日期。修订号为正整数。

JSON Schema检查结构/类型；应用还检查唯一性、日期区间、语义一致、账户与图片归属、金额精度/范围、修订等。schema通过不代表可写入当前账本。文件整体校验，任一错误都不保存半份资料，错误提供字段路径。

## 银行/信用卡转换

1. 从任务附带的标识说明中，将 `book_id`、`account_id`、`account_currency` 原样填入。不要按账户名称猜ID，不需要完整卡号。
2. 填写 `file_id`、`from`/`to`（包含首尾）、`source`、`complete`、`issues`。缺页/筛选/缺行时 `complete:false`；转换疑点逐项写入 `issues`。即使complete为true，有疑点也不能把未匹配账目判为疑似多记。
3. 明确 `date_basis`：`posted_date` 表示实际入账日，`occurred_date` 表示交易发生日。每行 `date` 必须采用该语义；若对应可选日期也提供，两者必须相同。不能缺入账日就自动拿购买日替代；无法确定时向用户核对。
4. 每行填写唯一 `row_id`、`date`、`direction`、`settlement`、`summary`；银行原流水号/页行号放 `source_ref`。同日同额真实多笔必须保留各行。摘要原文保留，转换辅助解释另放 `note`。
5. `settlement` 是此账户的实际入账金额（币种必须与账户一致），流入in、流出out。信用卡消费out、还款退款in。银行付款out、收款in。可选 `original`、`occurred_date`、`posted_date`、`fees`、`exchange_rate` 只填已知事实；`event_hint` 可为expense/income/transfer/repayment/refund/borrowing，仅辅助查找。
6. `statement_info` 可补principal/fees/total货币对象及payment_date，只作独立资料，不能因导入覆盖已确认账单或计划。

同账户、同方向、指定日期语义和实际金额精确一致才能完全匹配；逐条占用资金端。同日同额不合并。日期/金额差异留作人工处理；接受差异仍为差异。转账和还款两端各自核对，不将未导入的另一账户判漏记。相同file_id或内容指纹提示重复，区间重叠单列提示，不能按同日同额删真实行。

预览取消不写入。确认导入只写独立核对资料并保留原文件。用户补记必须选择类型，进入普通表单并主动保存；外币实际确认另作影响预览。删除核对资料不删手工账目。

## 小票图片ZIP与商品转换

导入时可按全局“照片保存方式”设置优化（默认节省空间），也可在本次表单切换原样保存；在记录详情页为没有小票的记录补加小票时直接使用全局设置。节省空间模式下，所有 MIME 为 `image/*` 的图片都重新编码，不按截图、PNG、GIF 等格式豁免：小票短边 ≤ 2000px、JPEG 质量90；图片附件长边 ≤ 2560px、JPEG 质量85；等比缩小且不放大，EXIF 方向烘焙到像素，输出 sRGB SDR，去掉 gainmap，透明区域合成白底，不保留 EXIF/XMP、GPS、机型或动态视频。优化后 MIME 为 `image/jpeg`、扩展名 `.jpg`；原样保存及非图片保持字节不变。源文件完整读入后若格式不支持或损坏而无法解码，原样保存并提示数量；读取、写入或数据库失败不走回退。

上传打包与备份使用已保存的图片字节，打包时不再转码；ZIP 中 `sha256` / `size_bytes` 是实际入包字节的校验值与大小，MIME 也对应保存后的文件。

ZIP包括根目录 `manifest.json` 和 `images/<receipt_id>/<image_id>` 小票图片，无普通附件。每张图含image_id、从0连续的position、path、实际入包字节sha256（小写64位）、mime_type与size_bytes。不要解压路径穿越或绝对路径。多包保持同一bundle_id，用part/parts（1起）关联；每张小票及其图片完整放在同一包，不跨包拆一张小票。

清单每条包括record_id、receipt_id、record_revision、receipt_revision、原交易date、merchant（未知可null）、original货币及可选settlement。原币金额与账户实际金额分开。仅用户选中的未整理小票打包上传，打包时不再压缩或改写已保存的图片。提交后仍待回填，不意味着已整理。

助手读取清单和小票图片，返回 `yohaku.receipt-items` JSON，原样复制bundle_id/book_id及每张小票的record_id/receipt_id/两个修订号。每条 `currency` 取原交易币种，`image_ids` 列出本次识别的小票图片ID。不得把信用卡换算金额当商品原币总额。

每个item包含稳定item_id、image_ids、uncertain_fields；name/quantity/unit/unit_price/line_amount/note可未知。数量如 `"0.5"`；行金额按原币精度。商品图必须属于本条image_ids。`uncertain_fields` 用字段名说明不确定信息，`note` 保留具体问题。无法看清时不要猜名称、数量或金额。

`adjustments` 列税费tax、折扣discount、运费shipping及其他增加other，金额为正；折扣从小计扣除。`included_in_items:true` 表示已含商品行，不再次加减；false时另计。其他减少请用discount并说明。receipt_total为票面总额，未知null；complete表示明细是否齐全，issues记录疑点。已知商品小计只累加有line_amount的行，不用quantity×unit_price臆造缺失行金额；再计未包含的调整得到已知合计。即使complete为true，只要有行金额未知，仍呈现不完整。

合计与原消费比较，差额可以保存但须提示检查；绝不自动改交易金额。目标/账本/来源图片不符拒绝；记录或小票旧修订、已有商品重复替换需明确预览确认。替换原商品而非追加；新增未识别图片仍待整理，不能删除已有图片或偷偷覆盖新手工编辑。

## 错误样例

- `statement.invalid.json`：JPY小数、重复row_id、行日期越过区间。schema能发现精度错误，应用同时报告行ID与区间语义错误。
- `receipt-items.invalid.json`：商品引用了不属于本条来源图片的ID，结构合法但语义拒绝。

有效示例只证明文件格式；能否关联取决于当前账本真实数据及打包修订。

## 云端办理格式（v1，阶段8最终校对）

以下五种使用 `format` 字段，采用最小单位整数金额（币种由字段或关联账户明确），**不采用上面的转换格式金额字符串与可忽略额外字段规则**。办理输入严格拒绝未知字段；命令、版本、引用、数量与 digest 还须由本机注册表进行语义校验。

| format | Schema | 用途 |
| --- | --- | --- |
| `yohaku.command-plan` | [schema](command-plan.v1.schema.json) | 助手返回的办理方案；最多500步；只能引用前序步骤的有类型依赖；用户事实不可由助手填写 |
| `yohaku.capabilities` | [schema](capabilities.v1.schema.json) | 本机注册表导出的能力清单、共享引用定义、永久边界及 catalogDigest |
| `yohaku.ledger-context` | [schema](ledger-context.v1.schema.json) | 专用结构导出，含生成时间、覆盖说明、contextDigest；不直接序列化数据库 Entity |
| `yohaku.monthly-review` | [schema](monthly-review.v1.schema.json) | 手动确认提交的月度汇总、月计划快照；结果仍为 command-plan，评语在 message、建议在 steps |
| `yohaku.records` | [schema](records.v1.schema.json) | 用户选择日期范围或账户范围的未删除记录；按账户包含相关转账两端 |

完整协议：[yohaku-command-protocol-v1.md](yohaku-command-protocol-v1.md)。应用源文本是 `android/app/src/main/assets/yohaku-command-protocol-v1.md`；此处及安卓交接包是同步副本。正常/错误样例位于 `examples/`：错误样例分别演示助手代填事实、无效执行模式、缺少 contextDigest、金额类型错误；另有批量分类正常样例。方案样例 digest 的64个0仅作占位，实际方案必须复制任务附件值，不能直接用于本机验收。`capabilities.valid.json` 例外：由真实 `CommandRegistry().export()` 生成，保留真实 catalogDigest；JVM `CommandCapabilitiesTest` 校验本文件与交接包副本。更新时在 `android/` 运行 `./gradlew -Dyohaku.updateCapabilities=true :app:testDebugUnitTest --tests com.yohaku.expensetracker.data.command.CommandCapabilitiesTest`，同一开关还生成两处 `command-plan.v1.schema.json`，内容与任务附件 `yohaku-command-plan-v1.schema.json` 一致；不要手工维护这些生成文件。

当前88个命令覆盖账本、记录、规划、金融/额度、文件/偏好及月计划，执行方式有transactional、post-commit和interactive；全部以本次任务能力附件为准，不能只依据早期四命令样例。金融规则使用领域字段及显式未知值；非一次付预测必须明确原合同起始期数。不完整规则允许保存；缺少可计算预测的资料时显示缺项并阻止整批执行。

command-plan schema 由注册表完整生成，含全部88条命令的 inputSchema、步骤分支及严格顶层结构；每个办理/月评任务均附 `yohaku-command-plan-v1.schema.json`。先用它校验结构，业务可执行性仍以手机预览为准。日期/月份和已强制的固定范围由字段注解及共用 helper 生成并由本机解释。月评评语写message、建议写steps，不能新增review或代填USER_FACT；无步骤回答仍须正确book_id及两个digest。普通办理实际必带协议/能力/方案schema/结构四份，按选择另附records/源文件；月评初次为协议/能力/方案schema/结构/月汇总五份。阶段8修正实际确认示例、枚举、过时说明并补任务放弃边界，触感反馈增量已手工同步能力样例及方案 schema（含 catalogDigest），需用上述入口重新生成并比对；该测试还检查完整方案 schema 的全部命令分支、所有命令示例以及生成文件一致性。

助手往返首轮修订：结构导出增加可兼容旧快照的asOfDate/zoneId，book.values补initialized/setupStep；任务context.source_files标明初次用户源文件名称，非新增附件。协议集中说明合理推断/日期/额度可空值及完整建卡示例、月计划即时更新和日期范围未补跨范围退款的限制。entry.update.targets导出oneOf和ids的minItems/maxItems/uniqueItems，本机解释同一约束。能力复用说明按各命令区分，需验收方再次生成能力样例。

### 支出不计入预算（2026-11-06）

`entry.create`、`entry.update`（及共用字段的 `entry.confirmActual` / `entry.prepareDraft`）支持 `budgetExcluded` 布尔值：新记录默认 false，部分更新省略保留、显式 false 取消，不能为 null；仅 EXPENSE 可设 true，其他类型设 true 校验失败。仓库没有 `entry.save` 别名。排除月/分类预算及其待确认笔数、预算节奏与提醒，退款和手续费随原支出；分析、账户余额、日历实际金额与活动自身预算仍计入。`template.save` 是完整替换，budgetExcluded 默认 false，仅支出模板可为 true；`template.createEntry` 带入模板值并允许 set 覆盖。周期规则、实例和固定预留不新增字段。

`yohaku.records` 每条记录导出 `budgetExcluded`；账本结构的 template.values 同样包含该字段。月评的 budgets 与 provisionalExpenseCount 使用排除后的口径，expenseMinor/categories 仍为实际净消费；确认页字段显示「不计入预算 / 予算に含めない」，撤销按原字段恢复。
