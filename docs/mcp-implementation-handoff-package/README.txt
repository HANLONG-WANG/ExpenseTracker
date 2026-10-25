ExpenseTracker MCP 实现交接包 1.0  2026-10-20

先阅读 mcp-implementation-handoff.docx。
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
原网站未更改或重新部署。附件中的增强建议均不是现有接口。
