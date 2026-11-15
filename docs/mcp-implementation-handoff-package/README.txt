ExpenseTracker MCP 实现交接包 1.1  2026-10-27

mcp-implementation-handoff.docx 保留为 2026-10-20 的 v1.0 历史说明；当前事件扩展以本说明及 reference/README.md 为准。
照片保存约定于2026-10-31同步至 reference/lib/formats/README.md：默认在手机导入时优化，也可原样保存；分析与备份使用已保存字节，ZIP打包不再转码，sha256/size为实际入包字节。业务 JSON 结构不变。
本包面向开发者实现 MCP 服务或兼容适配器，现有服务已经有六个工具。
当前 Sites 协议标识 2026-07-28 不表示适用于所有外部 MCP Host。

目录
  tools.json                 从现有 route.ts 提取的六个 inputSchema
  rpc-examples.json          合成 JSON-RPC 请求及典型响应
  contract-test.mjs          实际路由与业务服务的离线契约检查
  reference/                 当前 MCP 路由、服务、协议、数据库及部署配置快照
  reference/lib/formats/     四份业务 JSON Schema
  fixtures/                  合成业务格式样例
  MANIFEST.json              附件 SHA-256 和基线说明

运行离线检查
  使用 Node.js 22.13 以上且支持 node:sqlite、stripTypeScriptTypes 的版本。
  node contract-test.mjs
无需安装 npm 包，不访问网络，不接触线上任务。测试使用 SQLite 内存库与 R2 模拟。
执行测试会在本包目录重新生成 tools.json。测试覆盖本地路由及业务规则，不验证真实 Sites 网关和 OAuth。

reference 是实现参考快照，不是完整可部署网站。迁入框架时需补齐 Workers/Vinext、D1/R2 绑定、数据库迁移及可信身份验证。
不要把 public HTTP 客户端传入的 oai-authenticated-user-id 当成登录凭证。
不要用手机 deviceToken 访问 MCP。包中没有真实身份、银行数据或凭证。
本次扩展加入 MCP Events：订阅、签名回调、事务通知队列与 Worker 后台投递。网站发布状态见 MANIFEST.json；文档中的 v1.0 增强建议仍需逐项核对，不能视为全部实现。
新增 reference/build/sites-worker.ts 为集成片段，依赖完整网站的 Vinext / connector-context；reference/drizzle/0001_ambiguous_vector.sql 仅为新增迁移，不包含历史迁移。
离线检查现在为17项，真实 Work 事件订阅未由离线检查验证。

还款完整性补充（2026-11-15，以本段及当前命令协议为准）：
financialRepayment.save 待确认状态也校验所有分配合计的已知本金、利息、费用、实收及同币实扣（扣除已知付款端费用）上限。分配组成不完整，或既无平台实收也无同币种实扣可作上限时，手机现金预测和账单汇总保留待核对状态，不按确定金额抵扣备款。
entry.lifecycle RESTORE 对恢复后的完整账务状态检查账单、合同和循环池剩余本金；超额时整批预览/执行失败，需先调整关联再恢复。手机回收站与云端办理共用相同仓储校验，云端不得绕过。命令 JSON 结构、版本和能力目录未变。

UI 外壳账户身份补充（2026-11-15）：
手机账户编辑（含首次设置）按保存的账户 ID 恢复，目标不存在时阻止保存；资金风险/金融总览补记实际收入保留指定的启用资金账户，不回退到记忆账户。
已核对 account.save 的显式 account 经引用解析后进入 saveAccount，目标丢失会报错；entry.create 的显式 account 覆盖默认账户，正式预览/提交复用仓储的启用状态与收入资金账户校验。因此本次无需修改命令适配器、命令 JSON、能力目录或协议版本；本地 newEntryDraft 的新增可选 accountId 不改变云端未指定账户的默认行为。云端草稿仍允许不完整内容，不把草稿当作已入账收入。

记账输入与日期状态补充（2026-11-15）：
手机原币选择与实际入账确认相互独立；重选同一币种保留换算依据，切回账户币种不隐式确认。转账自动估算遇到未完成费用输入时保留草稿并显示校验原因，补齐后重试。已核对 EntryCommandAdapters 与 CommandRepository：entry.update 仍保留未显式修改的确认标志；新建外币记录的默认待确认政策不变；云端明确请求估算时继续使用严格转换函数，资料不足令整批预览失败，不把手机的“暂停自动回填”当作命令执行成功。
日期展开状态只属于本地 UI。金融逐期草稿新增可缺省的 calendarId 作为稳定行标识，不进入业务计划或命令输入；云端提交日期范围控件已同步迁移。无需修改服务端、命令适配器、命令 JSON、能力目录或协议版本。

扫描交接补充（2026-11-15）：entry.prepareDraft(media=RECEIPT_CAMERA) 续办仍复用手机扫描入口。确认后保留选中路径、原照片和原目标，入库失败可重试，成功后才清理扫描会话；用户明确取消及既有扫描缓存过期策略仍可清理。Activity 重建不重复启动同一入库任务。此修复不改变命令/schema、云端上传资料或回执协议。
源文件上传事务修正（2026-11-15，分区 03）：文件元数据、输入版本和上传日志在同一 D1 batch 中提交。日志失败整体回滚并清理未登记对象；任务状态冲突也不留下对象或上传日志。成功直接使用事务插入行数，不再执行提交后查询，避免已登记文件因后续错误被删除。新增三项离线回归覆盖日志失败与重试、提交后读取故障隔离、状态冲突清理；HTTP 字段及 Android 命令协议不变。此为参考实现修复，不表示线上站点已更新，MANIFEST 中部署记录仍为历史记录。

Android 命令补充（2026-11-15）：流水确认/差异接受通过 postingId 区分转账本金与独立手续费；流水补录不得覆盖来源事实，外币实际到账保留已确认状态。期初本金与余额池来源归属由 Android 共用业务校验执行。MCP 服务仍传递原命令文件，不推断或改写这些字段；当前定义见 ../android-cloud-handoff-package/formats/yohaku-command-protocol-v1.md 及手机导出的能力清单。

末期本金未知修正（2026-11-15）：Android 在保留账单缺少本金拆分时阻止 financialPrediction.save 整批执行，末期同样不得用预测本金推断清偿。服务端无需新增字段或改写方案，沿用手机返回的缺项；可补齐实际拆分后重算。协议详见上述 Android 交接包。

已办理重算约束（2026-11-15）：financialPrediction.save 不能删除、修改或转移已保存的办理费；terms 须保留 additionalFees，candidates 由手机沿用已保存费用。agreementDate 须为当前办理生效日，循环信用卡及循环贷款的计息开始不得早于该日；过早区间返回 plan.accrualBeforeAgreement 并阻止整批执行。服务端不裁剪日期或补造费用，按返回信息核对后重提；命令、字段及版本不变，详见 Android 交接包 formats/yohaku-command-protocol-v1.md。

云端逐项核对币种修正（2026-11-15，分区 06）：手机将本位币费用、债务金额、付款端费用、借款到账和另付费用分别绑定币种；预计结算／转账金额使用其保存的预计币种。确认事实新增 sourceCurrency／fundingCurrency／separateFeeCurrency 和嵌套还款币种，供逐项详情与历史展示；这些是手机内部事实，不是助手命令输入，无需修改网站、命令 schema 或 Money 格式。回归与三主题视觉验证入口见 android/README.md 的同名小节。


还款卡片展示补充（2026-11-15）：
手机账户卡片/详情与金融总览显示截至今日“已付待确认”的实际付款原币金额，包含尚未分配到账单的付款；多币种分别列出，实付不等同于已清偿本金。信用卡封面“仍需备款”复用账单与现金预测的 cashRequiredMinor，已知账单全部付款但清偿待确认时显示相应状态，不提示重复备款。未确认清偿仍保留账面欠款。
已核对 FinanceCommandAdapters 的 financialRepayment.save / confirm 与 CommandExports：save 保持清偿待确认，confirm 仍需手机事实确认；导出的实际付款、目标账户、platformConfirmed 和 allocations 足以区分付款与清偿。本次只修展示与共用文字排版，不新增命令、字段或协议版本；服务端继续传递原始事实，不根据卡片余额推断还款未发生。
