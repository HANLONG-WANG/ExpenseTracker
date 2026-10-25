ExpenseTracker 安卓云端分析接入交接包 1.0
更新日期 2026-10-20

先读 android-cloud-handoff.docx，再导入 api.openapi.json 查看移动接口。
formats/ 为安卓现有四种 v1 协议与正常/错误样例。
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
