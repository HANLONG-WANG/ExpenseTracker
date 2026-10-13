# 本地字体资源

三套主题独立指定显示文字、正文、数字与辅助文字的字体角色，不能从另一主题继承字体。

- 现代生活杂志：Noto Serif CJK SC 字形子集（YohakuEditorial）；金额与编号使用 Georgia，正文、标题和辅助文字使用同一宋体体系。
- 纸质手账：Klee One 子集（YohakuHand），中文缺失字形由 Fandol Kai 子集（YohakuHandCN）补充。
- 日常小物：Zen Maru Gothic 子集（YohakuRound），中文缺失字形由 Noto Sans CJK SC 子集（YohakuRoundCN）补充。

字体以 WOFF2 在本地提供，不依赖在线字体 CDN。子集包含设计稿现有文案与基本拉丁字符；新增文案应更新子集，用户自定义内容使用同主题字体栈里的系统后备字体。

子集内部名称改为 Yohaku 前缀，以区分修改后的字体。原始版权与许可证元数据保留；完整许可文件保存在本目录。

来源：

- Noto CJK：https://github.com/notofonts/noto-cjk
- Klee One：https://github.com/google/fonts/tree/main/ofl/kleeone
- Zen Maru Gothic：https://github.com/google/fonts/tree/main/ofl/zenmarugothic
- Fandol：https://ctan.org/pkg/fandol （GPL 与字体例外，见 README/COPYING）
