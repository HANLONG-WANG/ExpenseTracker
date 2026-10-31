# 余量 · 分析交接台

私人文件分析交接工作台。网站不直接调用模型 API；用户可以单次委托助手，或在 Work 中建立持续事件订阅，由新提交任务触发助手领取资料、分析并提交结果。Android 工程不在本项目修改范围内。

## 已实现

- 三类任务：小票整理、银行流水转换、通用分析；共用源文件、状态、版本和事件记录。
- D1 持久任务记录，R2 私密文件存储；所有读取与写入按 Sites 用户身份或设备令牌授权。
- HTTP 原始二进制上传，SHA-256 校验、大小限制、重试去重；流式分析包下载。
- 读取小票 ZIP 根目录 manifest.json（stored/deflate，限 2MB），关联检查原始 ID、图片和修订号。
- 返回文件格式与关联校验；人类可读说明、结果版本、旧资料提醒、发布领取、手机导入回执。
- 站点 MCP：列任务、读任务、分块读文件、更新分析状态、提交及发布结果。
- 中文响应式界面：任务列表、详情、小票图片预览、结果表格、设备管理、接口说明。

## HTTP 接入

站点内「设备连接」生成一次性显示的连接配置。配置同时包含平台私人站点访问令牌和独立设备令牌；手机每次请求必须携带：

```
OAI-Sites-Authorization: Bearer <platformAccessToken>
Authorization: Bearer <deviceToken>
```

全程 HTTPS。配置不得写入仓库或日志。手机需安全存储。平台令牌为 Sites 提供的站点访问凭证，并非本应用签发；设备令牌仅存 SHA-256。撤销设备在应用接口层立即生效，不改变站点访问范围。若平台令牌轮换，需重新生成连接配置。

1. POST `/api/v1/tasks`；JSON `{type,title,instructions,context}`；必须携带稳定 `Idempotency-Key`。相同键与相同参数返回原任务，不同参数 409。
2. POST `/api/v1/tasks/{id}/files?name=<URL编码文件名>`；请求体为原始文件字节；附 `Content-Length`、`Content-Type`、`X-Content-SHA256`（小写64位）。相同文件名及校验值重试去重。
3. POST `/api/v1/tasks/{id}/submit`，全部文件上传成功后任务进入待分析。
4. GET `/api/v1/tasks/{id}` 查询状态。GET `/api/v1/tasks` 返回最近500个任务。
5. GET `/api/v1/tasks/{id}/result` 只返回当前输入版本已发布结果，未就绪返回409。
6. POST `/api/v1/tasks/{id}/ack`；JSON `{resultId,status:"imported"|"failed"}`；确认成功导入与仅下载分开记录。

类型 `receipt` / `statement` / `general`。context 可包含 `manifest` 或 `identifiers`；也可以另行上传 JSON。小票 ZIP 内清单自动读取。分包必须属于同一账本和同一 bundle，原始文件不变。银行任务需上传原始流水和账户标识；未提供应用账目时，结果仅是流水转换，不代表已完成对账。

手机不能管理设备、接管分析或发布结果。Web UI 可保存源文件、分析包和任意历史结果；手机文件接口只开放当前可领取结果。后台不执行导入手机账目。

## 限制与边界

- 单源文件大于0且严格小于100MB；每任务最多200份；需结合实际 Sites/Cloudflare 请求限制验收。
- 结果支持 UTF-8 文本/Markdown/JSON，最大2MB。通用类型不接受任意二进制结果。
- schema 与金额、归属、日期等语义校验不证明 AI 内容正确；发布前必须人工检查。
- 每次成功添加源文件增加输入版本；发布禁止旧输入版本，预览仍可查看历史。
- 结果校验与 Android 最终校验均保留，服务端不能替代设备当前账本修订校验。
- 自动处理需先建立 Work 事件订阅，仍受账号权限及审批规则约束；没有自动同步、定时清理或云备份承诺。首版用归档管理，不提供永久删除入口。
- Sites 私有认证在网关执行；直接访问本地 Worker 的模拟测试不能证明生产认证正确。

## 开发与发布

保留 Sites Vinext 集成。依照 Sites 技能使用 `install-dependencies.mjs`、`build-site.mjs` 和 `site-workflow.mjs`。`npm run db:generate` 生成 D1 迁移，托管发布应用迁移。`.openai/hosting.json` 的 `DB` / `BUCKET` 由 Sites 配置。环境密钥 `SITES_SERVICE_ACCESS_TOKEN` 在 Sites 服务端配置，不进入源码和前端静态包。

`node scripts/test-core.mjs` 执行不依赖第三方库的协议与 ZIP 测试；`node scripts/test-api.mjs` 使用 Node 内置 SQLite 和内存 R2 模拟器验证接口。需要 Node 24+，不替代生产验收。`node scripts/test-http.mjs` 在已启动的本机预览上验证真实Worker/D1/R2文件往返。首版类型检查、构建和桌面/手机布局验证已完成，验收记录见 STATUS.md。

## MCP Events（2026-10-27）

事件 `analysis_task.submitted` 支持按 `type`（receipt / statement / general）筛选，省略表示当前用户所有类型。仅在 uploading / needs_info 提交至 queued 时触发；助手退回队列、发布结果不触发。投递携带 taskId、inputRevision、type，原文件通过现有工具读取。重复事件应先读取任务，仅处理 queued 且资料版本一致的任务。

发布后在插件页确认事件已被发现，必要时重新扫描。使用网页版 Work 或桌面 Work + Cloud，委托“持续监控余量分析交接台的新提交任务，读取资料，按余量协议分析、校验并发布；资料不足标记待补充”。网站部署不会替用户创建订阅。新订阅仅覆盖后续提交，不重放历史；现有待处理任务仍需单次委托。

MCP 同一认证端点提供 events/list、events/subscribe、events/unsubscribe。订阅按用户隔离，默认一天、最长七天，ChatGPT 在 refreshBefore 前续订。即使请求无限期订阅也授予有限有效期。回调先通过签名 challenge 验证；密钥轮换保留五分钟双签窗口。密钥仅保存在服务端 D1，不返回任务 API 或日志。

当前回调仅允许四个明确的 OpenAI 主机：chatgpt.com、api.chatgpt.com、api.openai.com、connectors.api.openai.com（HTTPS 默认端口，禁重定向）。请求使用 redirect: 'manual'，不跟随 Location；订阅验证拒绝非 2xx 响应，事件投递将 3xx 记为失败。这是一条窄范围主机白名单，不是支持任意 webhook 的 DNS 固定连接代理。若实际 ChatGPT 提供其他主机，订阅会明确失败，须先核实官方归属再修改白名单，不能直接开放任意地址。

状态变更和通知入队在同一 D1 事务中提交。Worker 在成功的 /mcp 或 /api/v1 请求后用 waitUntil 投递，单轮约24秒；网络错误、408、429、5xx 最多三次指数退避，重试沿用 eventId，410 停用订阅。失败写入任务历史，需人工重新委托。中断的待投递记录保留，下次成功 API/MCP 请求会恢复；没有独立 Cron，因此完全无后续访问且 Worker 中断时不保证继续重试。2xx 只代表事件已接收，不代表分析完成。

新增迁移只创建 mcp_subscriptions / mcp_deliveries 和索引，不更改原任务与文件。`node scripts/test-mcp.mjs` 验证工具兼容、订阅隔离、签名、提交触发、重试及取消。真实 Work 订阅和手机闭环须另行验收。

手机照片导入可按全局设置优化或原样保存；分析读取已保存图片，打包不再转码，ZIP大小/SHA对应实际入包字节。具体尺寸、编码及回退规则见 [业务格式](lib/formats/README.md)。
