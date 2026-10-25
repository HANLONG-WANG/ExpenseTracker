# 余量正式文件协议 v1

这四种 UTF-8 JSON 格式用于文件转换，不是备份或同步。银行文件与小票商品文件用途不同。应用不会OCR、连接金融机构或自动上传文件；用户也可在应用内主动把原始资料交给已连接的云端分析台，返回的同格式文件同样须预览确认。将原始流水/小票包与本说明交给助手，助手可独立生成文件；用户须在应用预览后确认保存。

| kind | Schema | 用途 |
| --- | --- | --- |
| `yohaku.import-identifiers` | [schema](import-identifiers.v1.schema.json) | 应用导出的当前账本及账户标识，供转换时引用 |
| `yohaku.statement` | [schema](statement.v1.schema.json) | 独立银行/信用卡核对资料，不产生正式账目 |
| `yohaku.receipt-bundle` | [schema](receipt-bundle.v1.schema.json) | 原图ZIP中的 `manifest.json` |
| `yohaku.receipt-items` | [schema](receipt-items.v1.schema.json) | 回填已有小票商品，不改交易金额 |

每种完整示例在 [examples](examples/)；示例UUID只用于说明，不能替代用户实际ID。bundle示例的校验值与大小是占位示意，不能作为真实ZIP。实际导出必须计算原图字节的SHA-256和大小。

## 共通字段与约束

`kind` 必须准确，`version` 必须是整数1；未知版本拒绝。兼容的额外字段可忽略，不能依赖额外字段产生业务效果。未知值使用 `null` 或省略可选字段，不能填0、空日期或猜测金额。所有ID均为规范36字符UUID（大小写可接受，但生成和交换时保持原ID文本）；账本、账户、交易、小票、图片ID只从应用提供的标识/清单复制。file_id、row_id、bundle_id、item_id由生成方分配稳定UUID，修正同一资料时保留ID；不要每次转换同一文件都换ID。

货币对象为 `{"currency":"JPY","amount":"1200"}`，支持JPY/CNY/USD。JPY整数；CNY/USD恰好两位小数，如 `"12.50"`；不允许指数、正负号、千位分隔、前导0或JSON数值。金额非负，不能超过应用Long最小货币单位范围（9223372036854775807）。方向独立表达。数量和单价允许更多小数位；汇率必须大于0。未知金额与已知0不同。

日期为有效公历 `YYYY-MM-DD`。明确时刻可用 `occurred_at`（带偏移ISO时间）和 `time_zone`（IANA时区），不从摘要猜测日期。修订号为正整数。

JSON Schema检查结构/类型；应用还检查唯一性、日期区间、语义一致、账户与图片归属、金额精度/范围、修订等。schema通过不代表可写入当前账本。文件整体校验，任一错误都不保存半份资料，错误提供字段路径。

## 银行/信用卡转换

1. 从应用导出标识说明，将 `book_id`、`account_id`、`account_currency` 原样填入。不要按账户名称猜ID，不需要完整卡号。
2. 填写 `file_id`、`from`/`to`（包含首尾）、`source`、`complete`、`issues`。缺页/筛选/缺行时 `complete:false`；转换疑点逐项写入 `issues`。即使complete为true，有疑点也不能把未匹配账目判为疑似多记。
3. 明确 `date_basis`：`posted_date` 表示实际入账日，`occurred_date` 表示交易发生日。每行 `date` 必须采用该语义；若对应可选日期也提供，两者必须相同。不能缺入账日就自动拿购买日替代；无法确定时向用户核对。
4. 每行填写唯一 `row_id`、`date`、`direction`、`settlement`、`summary`；银行原流水号/页行号放 `source_ref`。同日同额真实多笔必须保留各行。摘要原文保留，转换辅助解释另放 `note`。
5. `settlement` 是此账户的实际入账金额（币种必须与账户一致），流入in、流出out。信用卡消费out、还款退款in。银行付款out、收款in。可选 `original`、`occurred_date`、`posted_date`、`fees`、`exchange_rate` 只填已知事实；`event_hint` 可为expense/income/transfer/repayment/refund/borrowing，仅辅助查找。
6. `statement_info` 可补principal/fees/total货币对象及payment_date，只作独立资料，不能因导入覆盖已确认账单或计划。

同账户、同方向、指定日期语义和实际金额精确一致才能完全匹配；逐条占用资金端。同日同额不合并。日期/金额差异留作人工处理；接受差异仍为差异。转账和还款两端各自核对，不将未导入的另一账户判漏记。相同file_id或内容指纹提示重复，区间重叠单列提示，不能按同日同额删真实行。

预览取消不写入。确认导入只写独立核对资料并保留原文件。用户补记必须选择类型，进入普通表单并主动保存；外币实际确认另作影响预览。删除核对资料不删手工账目。

## 原图ZIP与商品转换

ZIP包括根目录 `manifest.json` 和 `images/<receipt_id>/<image_id>` 原图，无普通附件。每张图含image_id、从0连续的position、path、原字节sha256（小写64位）、mime_type与size_bytes。不要解压路径穿越或绝对路径。多包保持同一bundle_id，用part/parts（1起）关联；每张小票及其图片完整放在同一包，不跨包拆一张小票。

清单每条包括record_id、receipt_id、record_revision、receipt_revision、原交易date、merchant（未知可null）、original货币及可选settlement。原币金额与账户实际金额分开。仅用户选中的未整理小票导出，原图不压缩、不改写。成功保存后仍待回填，导出不意味着已整理。

助手读取清单和原图，返回 `yohaku.receipt-items` JSON，原样复制bundle_id/book_id及每张小票的record_id/receipt_id/两个修订号。每条 `currency` 取原交易币种，`image_ids` 列出本次识别的原图ID。不得把信用卡换算金额当商品原币总额。

每个item包含稳定item_id、image_ids、uncertain_fields；name/quantity/unit/unit_price/line_amount/note可未知。数量如 `"0.5"`；行金额按原币精度。商品图必须属于本条image_ids。`uncertain_fields` 用字段名说明不确定信息，`note` 保留具体问题。无法看清时不要猜名称、数量或金额。

`adjustments` 列税费tax、折扣discount、运费shipping及其他增加other，金额为正；折扣从小计扣除。`included_in_items:true` 表示已含商品行，不再次加减；false时另计。其他减少请用discount并说明。receipt_total为票面总额，未知null；complete表示明细是否齐全，issues记录疑点。已知商品小计只累加有line_amount的行，不用quantity×unit_price臆造缺失行金额；再计未包含的调整得到已知合计。即使complete为true，只要有行金额未知，仍呈现不完整。

合计与原消费比较，差额可以保存但须提示检查；绝不自动改交易金额。目标/账本/来源图片不符拒绝；记录或小票旧修订、已有商品重复替换需明确预览确认。替换原商品而非追加；新增未识别图片仍待整理，不能删除已有图片或偷偷覆盖新手工编辑。

## 错误样例

- `statement.invalid.json`：JPY小数、重复row_id、行日期越过区间。schema能发现精度错误，应用同时报告行ID与区间语义错误。
- `receipt-items.invalid.json`：商品引用了不属于本条来源图片的ID，结构合法但语义拒绝。

有效示例只证明文件格式；能否关联取决于当前账本真实数据及导出修订。
