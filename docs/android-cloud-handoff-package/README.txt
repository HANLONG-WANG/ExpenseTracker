ExpenseTracker 安卓云端分析接入交接包 1.0
更新日期 2026-10-31

先读本说明与 formats/README.md 的照片保存约定，再读 android-cloud-handoff.docx，最后导入 api.openapi.json 查看移动接口。
照片保存补充（2026-10-31）：Word 文档中“保留原图”“原图ZIP”“不压缩或修改原图”均指已保存的小票图片；导入阶段可按设置优化或原样保存。此补充及 formats/README.md 覆盖 Word 中相应旧表述，上传协议 JSON 结构不变。
导入时可按全局“照片保存方式”设置优化（默认节省空间），也可在本次表单切换原样保存。节省空间模式下，所有 MIME 为 image/* 的图片都重新编码，不按截图、PNG、GIF 等格式豁免：小票短边 ≤ 2000px、JPEG 质量90；图片附件长边 ≤ 2560px、JPEG 质量85；等比缩小且不放大，EXIF 方向烘焙到像素，输出 sRGB SDR，去掉 gainmap，透明区域合成白底，不保留 EXIF/XMP、GPS、机型或动态视频。优化后 MIME 为 image/jpeg、扩展名 .jpg；原样保存及非图片保持字节不变。源文件完整读入后若格式不支持或损坏而无法解码，原样保存并提示数量；读取、写入或数据库失败不走回退。
上传打包与备份使用已保存的图片字节，打包时不再转码；ZIP 中 sha256 / size_bytes 是实际入包字节的校验值与大小，MIME 也对应保存后的文件。
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
