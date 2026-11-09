ExpenseTracker 安卓云端分析接入交接包 1.1
更新日期 2026-11-03

先读本说明与 formats/README.md 的照片保存约定，再读 android-cloud-handoff.docx，最后导入 api.openapi.json 查看移动接口。
照片保存补充（2026-10-31）：Word 文档中“保留原图”“原图ZIP”“不压缩或修改原图”均指已保存的小票图片；导入阶段可按设置优化或原样保存。此补充及 formats/README.md 覆盖 Word 中相应旧表述，上传协议 JSON 结构不变。
导入时可按全局“照片保存方式”设置优化（默认节省空间），也可在本次表单切换原样保存；在记录详情页为没有小票的记录补加小票时直接使用全局设置。节省空间模式下，所有 MIME 为 image/* 的图片都重新编码，不按截图、PNG、GIF 等格式豁免：小票短边 ≤ 2000px、JPEG 质量90；图片附件长边 ≤ 2560px、JPEG 质量85；等比缩小且不放大，EXIF 方向烘焙到像素，输出 sRGB SDR，去掉 gainmap，透明区域合成白底，不保留 EXIF/XMP、GPS、机型或动态视频。优化后 MIME 为 image/jpeg、扩展名 .jpg；原样保存及非图片保持字节不变。源文件完整读入后若格式不支持或损坏而无法解码，原样保存并提示数量；读取、写入或数据库失败不走回退。
上传打包与备份使用已保存的图片字节，打包时不再转码；ZIP 中 sha256 / size_bytes 是实际入包字节的校验值与大小，MIME 也对应保存后的文件。
formats/ 包含原有四种文件转换格式，以及五种云端办理/月评 v1 格式和正常/错误样例。
本包没有任何有效连接令牌，也没有用户银行或小票原始资料。
所有样例UUID只用于说明；请使用App真实导出ID。

smoke_test.py 由开发者在测试账本使用，会向真实网站创建任务和上传所选文件。
只依赖Python标准库；连接配置从用户自行提供的JSON文件读取。
示例
  python smoke_test.py --config <连接配置.json> --state <本地测试状态.json> --type receipt --title <测试任务名> --files <完整小票包.zip>
或
  python smoke_test.py --config <连接配置.json> --state <本地测试状态.json> --type statement --title <测试任务名> --files <原银行文件> <标识.json>
结果由网站或受托助手提交并标记可领取后
  python smoke_test.py --config <连接配置.json> --state <本地测试状态.json> --download <结果保存路径>

状态文件不包含凭证，记录taskId、幂等键和创建参数；创建请求重试请复用该状态文件。
测试工具不自动导入安卓数据库、不发送imported回执，也不会把下载视作导入成功。
请不要把连接配置和真实财务资料放进交接包或源码仓库。

云端办理与月评补充（2026-11-03，阶段1–8已验收）：
网站/API不修改。general 类型 context 包含 yohaku_protocol=yohaku.command-task、protocol_version=1、book_id、context_digest、purpose=command|monthly-review（月评另有month）。
普通办理实际必带 yohaku-command-protocol-v1.md、yohaku-capabilities-v1.json、yohaku-command-plan-v1.schema.json、yohaku-ledger-context-v1.json；选范围才附 yohaku-records-v1.json，再附用户原样选择的截图/文件。结构是每任务自动上传，不只是所选资料；具体交易、图片字节和草稿不因结构上传而全量发送。
月评初次上传协议、能力、方案schema、结构、yohaku-monthly-review-v1.json五份，不附逐笔或源文件。提醒仅提示，用户核对后才提交；历史可手动选择已结束月份。允许用户明确补充资料。
助手只返回 yohaku.command-plan v1 JSON；手机校验与回滚试运行、事实/风险勾选后确认。无步骤是回答；月评message必须非空，steps为可选建议，领取即保存评语而不自动执行建议。手机按来源与plan_id去重，contextDigest来自原提交，catalogDigest须与当前注册表一致。
当前90个命令，包含基础账本、规划、金融/额度、文件/偏好与月计划。preferences.haptics、preferences.scanAutoCapture 和 preferences.scanAutoProcess（enabled 必填），与照片保存偏好一样为本机所有账本共用，比较原值并支持完成后的撤销。transactional原子写账，post-commit/interactive另排队、重试或由本机用户完成。整批撤销只有完整证据且无冲突时可用；不可自动撤销的步骤在预览说明。
恢复备份、连接凭据/设备管理、所有永久删除永远禁止，包括未提交任务的放弃（cloud.task.discard）；回收/归档/停用允许。助手不得声明USER_FACT或提供任意SQL/路径/网络调用。
此补充及formats/协议覆盖Word中仅描述通用文本另存、早期客户端范围的流程；Word保留原HTTP/API接入基线，网站接口契约不变。正式当前客户端规格见 ../implementation-spec.md 的3.2/3.3（从本目录为上一级）。
协议唯一应用源文本位于assets，formats/为同步副本。能力说明和示例来源是适配器注册表；能力样例及完整command-plan.v1.schema.json由CommandCapabilitiesTest用同一更新开关生成；后者随任务以yohaku-command-plan-v1.schema.json上传。本次由验收方重新生成，不能手写生成内容或digest。完整格式与docs/formats逐文件同步。
离线独立助手工具见 ../../android/README.md 的“阶段8：独立助手往返工具”：export生成真实附件及持久测试库，verify接收plans/*.json并输出预览报告，不confirm。真实Work订阅和网站往返仍需用户实测，离线工具不证明网站链路通过。

助手往返首轮返工补充：按协议的统一约定合理推断并在message列假设，不因当前日期/日本信用卡默认一次付/可空额度政策未逐项填写而返回空方案。结构含asOfDate/zoneId、账本initialized/setupStep。任务context.source_files是初次提交源文件实际名称清单（无额外附件），补充源文件以当前input列表为准。离线export通过HTTP替身调用真实advance完成上传并保存fileId/uploadedAt，verify仍执行原附件校验；原PREPARED夹具须重建。
