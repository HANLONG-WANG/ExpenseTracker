# 0.5.0 签名与安装

正式包：`artifacts/yohaku-release-0.5.0.apk`，包名`com.yohaku.expensetracker`，versionCode5。开发包：`artifacts/yohaku-dev-0.5.0.apk`，包名`com.yohaku.expensetracker.dev`，versionCode5。两者Android 10+，Room v28。相应`.apk.sha256`文件用于核对完整性。

开发包可直接覆盖阶段A–D开发包，保留资料。正式包使用独立包名与固定正式证书，与开发包并存；安装正式包不会自动搬入开发包账本。不要以卸载开发包作为升级步骤。应用没有云同步或备份，卸载或清除存储会删除该包的账本与内部附件。

```sh
adb -s <serial> install -r artifacts/yohaku-dev-0.5.0.apk
adb -s <serial> install -r artifacts/yohaku-release-0.5.0.apk
(cd artifacts && sha256sum -c yohaku-release-0.5.0.apk.sha256)
```

手机也可用文件管理器打开APK，按系统提示允许该来源安装。覆盖升级始终保持同包名/同证书、递增versionCode；签名不同的包不能覆盖安装。

## 已执行的安装验证

专用API36模拟器实际验证：开发阶段D 0.4.0/code4、Room25→开发0.5.0/code5、Room28；同一正式证书的本轮RC 0.5.0-rc1/code4→最终0.5.0/code5。两者均保留非空账本、原事件ID、500JPY消费/9500余额及原字节附件。正式RC使用本轮E代码，不是历史正式发行。原开发资料已恢复。

最终正式APK还在卸载仅本轮新建的正式验证包后重新全新安装，真实MainActivity从空白开始，账本/账户/分类/记录/分录/附件表均0。300个常规方法和两条窄屏真实IME方法最终通过；51项JVM通过，Debug/Release Lint零错误（66/64警告）。全部结论仅限专用模拟器，具体原始失败与复验见[验收记录](../docs/phase-e-acceptance.md)。

## 正式密钥

正式证书SHA-256：`6abfd3e8d796fa37d3cb05c4fa58d6d220e212c1923bc197404ee3df08ef1b0b`。APK完整性哈希见各`.sha256`文件；证书指纹识别后续升级身份，与每次构建都会变化的APK哈希不同。

本次正式密钥在构建机`~/.local/share/yohaku/signing/`，目录700、文件600：`release.p12`（PKCS12，RSA3072，alias `yohaku-release`）与`store-password`。构建读取外部文件，不在仓库保存密码或私钥；换机器可用`YOHAKU_SIGNING_DIR`指定同一套密钥所在目录。不要重新生成替代旧密钥，否则无法升级已安装的正式包。请将这两个文件一起保存到受控且加密的持久备份；本次没有将密钥上传至外部服务。

```sh
./gradlew :app:assembleDebug :app:assembleRelease
```

无正式密钥仍可构建Debug；Release签名校验会失败，不会把未签名包当作正式交付。默认版本0.5.0/code5；后续版本可传`-PyohakuVersionCode=<递增整数> -PyohakuVersionName=<版本>`。最终APK证书指纹、哈希及实际安装/升级结论见[阶段E验收](../docs/phase-e-acceptance.md)。

## 用户手机精简检查（尚未执行）

- [ ] 先安装开发包覆盖原开发版本，确认原账本、余额、附件仍在；独立安装正式包应从空白设置开始。
- [ ] 在正式包创建自己的账本、账户和分类，保存一笔消费；关闭网络后编辑、退款、查看预算/历史，重启后核对原记录及附件。
- [ ] 依次切换三主题和中日文，检查常用页面、菜单、键盘及大字。手机字体和厂商系统可能与专用模拟器有差异。
- [ ] 主动保存开启提醒，拒绝权限仍能记账；允许后检查真实通知金额、点击账本和目标月份。非精确提醒受厂商省电策略限制。
- [ ] 主动开启前台定位，拒绝或关闭系统位置仍能手填。允许后核对自己的保存地点；低精度或多个候选应手动选择。
- [ ] 选择真实图片/音频/附件并打开；回收一笔测试记录后恢复，确认原ID对应资料与内部文件保留。永久清空不可撤销。

用户已明确选择本轮交付APK，实机项保持未验。目前仅专用API36模拟器验证。上述清单没有勾选，不以模拟器结果替代手机实际检查。
