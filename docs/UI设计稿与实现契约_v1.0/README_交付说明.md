# UI 设计稿与实现契约交付说明

本目录是 Android 记账软件 UI v1.0 的完整实现基线。

## 使用顺序

1. 先阅读 `Android记账软件_UI设计系统与实现契约_v1.0.md`。
2. 在 `:core:designsystem` 中导入或映射 `android_ledger_ui_tokens_v1.json`。
3. 按 `android_ledger_screen_contract_v1.yaml` 建立 Navigation 3 route、screen ID 和状态覆盖测试。
4. 用 `UI需求追踪矩阵_v1.csv` 检查需求覆盖和验收条件。
5. 视觉评审使用 `UI视觉样稿_v1.html`、`UI视觉样稿_浅色.png` 和 `UI视觉样稿_深色.png`。

## 唯一来源优先级

发生歧义时按以下优先级处理：

1. 已冻结产品需求、系统架构和领域不变量；
2. 主 UI 契约中的交互与语义；
3. 设计令牌 JSON 中的具体视觉值；
4. 页面路由 YAML 中的覆盖范围；
5. 视觉样稿。

视觉样稿是评审材料，不得用截图取色或测量替代 token。

## 变更纪律

- feature 模块不得复制或改造公共组件。
- 新页面先登记 screen ID 和 route。
- 新视觉变体先修改设计系统和 token，再使用。
- 任何涉及交易语义、隐私、安全、备份或恢复的改动，必须回到原始需求和领域模型评估。
