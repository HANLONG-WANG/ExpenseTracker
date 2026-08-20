# 技术栈一致性审查

> 审查方式：仅阅读工作区源代码与配置；未运行 Gradle、应用或设备自动化。

## 构建基线与平台

- 开发语言（Kotlin 2.4.x，应用业务代码仅 Kotlin）：正确
- Java 工具链（JDK 17，JVM target 17）：正确
- 最低系统（Android 9 / API 28）：正确
- 编译与目标版本（compileSdk 36，targetSdk 36）：正确
- 构建系统（AGP 9.3、Gradle 9.5、Version Catalog）：正确
- Kotlin 构建模式（AGP 内置 Kotlin，不应用 `kotlin-android`）：正确
- 代码生成（KSP，不使用 kapt）：正确
- IDE（Android Studio Quail 2）：仅凭工作区源代码与 `.idea` 配置无法确认实际使用的 Android Studio 版本；未发现可证明或否定 Quail 2 的版本标记
- 原生 Android SDK，未采用 Flutter、React Native、Kotlin/Compose Multiplatform，未主动编写 C/C++ 业务代码：正确

## UI、状态、依赖注入与设置

- Jetpack Compose、Material 3、Compose UI/Foundation/Animation、Lifecycle Compose、Compose BOM 与 Compose UI Test：正确
- 不建设 XML View 页面体系（仅小组件 RemoteViews 所需布局 XML）：正确
- Navigation 3（未使用 Navigation 2）：正确
- Jetpack Glance 桌面小组件：正确
- Kotlin Coroutines、Flow、StateFlow/SharedFlow/Channel、ViewModel 与 `collectAsStateWithLifecycle()`（未引入 RxJava/LiveData 双体系）：正确
- Hilt＋KSP（含 ViewModel、Worker、Android 组件；无 kapt）：正确
- Proto DataStore 保存非账务设置（未使用 SharedPreferences 保存该类设置）：正确

## 数据库、金额与时间

- Room 2.8.4＋`net.zetetic:sqlcipher-android` 4.17.0＋AndroidX SQLite 2.x `SupportSQLiteOpenHelper`：正确
- 数据库 schema 导出、显式迁移、无正式数据 destructive migration、未使用 `@Upsert`：正确
- Paging 3：正确
- SQLite FTS5 全文检索与 R*Tree 地理索引：正确
- 金额使用 `Long` 最小货币单位：正确
- 汇率、贷款及需要扩宽的精确计算使用 `BigDecimal`/`BigInteger`，并显式使用 `MathContext`/舍入规则：正确
- 金额表达式使用自有 tokenizer＋Pratt parser＋`BigDecimal`，未引入 JavaScript/通用脚本表达式引擎：正确
- 日期时间使用 `java.time`（`Instant`、`ZoneId`、`ZonedDateTime`、`LocalDate`、`YearMonth`、`LocalTime`）：正确

## 数据库外加密与认证

- Google Tink 1.23 小数据 AEAD：正确
- Google Tink 1.23 Streaming AEAD（AES-GCM-HKDF 大分段模板）用于附件/大型备份流：正确
- 恢复密码使用 Bouncy Castle `Argon2BytesGenerator` 的 Argon2id，独立盐并保存/校准参数，未全局注册 Provider：正确
- Android Keystore 设备密钥：正确
- BiometricPrompt＋CryptoObject 用户认证：正确
- 内容哈希使用 JCA SHA-256：正确
- 未使用 `EncryptedSharedPreferences`、`EncryptedFile` 或 `MasterKey` 作为新设计核心：正确

## 网络、外部服务、地图、图表与图片

- OkHttp 5、Okio 3、kotlinx.serialization JSON 与 MockWebServer：正确
- 网络约束（超时、取消、有界重试、禁止明文 HTTP、最小业务字段）：正确
- Google Identity Services Authorization API＋最小 `drive.file` scope＋Drive REST API v3（OkHttp 直连、resumable upload、Range 断点下载，未引入完整 Drive Java 客户端）：正确
- MapLibre Native Android 13.4.1（通过 `AndroidView` 承载 `MapView`）：正确
- OpenFreeMap 默认底图、attribution 与可注入样式配置（可切换 MapTiler/自托管）：正确
- Google Play services Fused Location Provider＋系统 `LocationManager` 后备：正确
- Vico 图表：正确
- Coil 3.5 自定义加密附件 Fetcher（禁用明文磁盘缓存并可清理敏感内存缓存）：正确

## 文件格式、文件访问与后台操作

- CSV 使用 Apache Commons CSV 流式读写：正确
- XLSX 使用 dhatim FastExcel 0.20.2 流式读写：正确
- PDF 导出使用 Android `PdfDocument`：正确
- 归档使用 Apache Commons Compress ZIP64＋流式 I/O＋Tink Streaming AEAD：正确
- Storage Access Framework、`ContentResolver`、持久化 URI 授权与 `DocumentFile`，未申请广泛文件管理权限：正确
- WorkManager／CoroutineWorker 用于周期与可延迟任务：正确
- 大型用户发起传输与前台服务回退：不完全一致。实际仅 Drive 备份上传和远端导出在 API 34+ 使用 `JobScheduler.setUserInitiated(true)`，旧系统通过前台 WorkManager 回退；Drive 大型下载以及完整恢复直接在 `RestoreController` 的协程中执行，未发现规范要求的 UIDT Job 或前台服务/前台 Worker 承载

## 遥测、崩溃与日志

- ACRA 核心组件＋自定义白名单报告/发送器：正确
- `ApplicationExitInfo` 系统退出诊断：正确
- 自定义固定枚举与固定 schema 的功能遥测，不使用通用分析 SDK：正确
- 未引入 Firebase Analytics/Crashlytics、Sentry、会话录屏或自动网络正文采集：正确
- 结构化日志限制（release 仅阶段/错误代码，debug 仅固定诊断元数据，无业务自由文本）：正确

## 测试、质量、CI 与发布

- JVM 测试（JUnit 5、Kotest Property/Assertions、MockK、kotlinx-coroutines-test、Turbine）：正确
- Android 测试（AndroidX Test、Instrumentation JUnit 4、Compose UI Test、Espresso、Room MigrationTestHelper、SQLCipher/安全/SAF 设备测试）：正确
- 网络测试（MockWebServer）：正确
- 性能技术栈（Macrobenchmark、Baseline Profile、JankStats、目标规模基准数据与 SQLite query plan 检查）：正确
- Android Studio Profiler：仅凭源代码无法确认实际是否使用；仓库中不存在能证明或否定该人工工具使用情况的配置
- 静态与构建质量（Android Lint、detekt、Spotless＋ktlint、Kover、dependency verification/locking、CycloneDX、OSS 许可证清单）：正确
- CI（GitHub Actions）：正确
- Gradle Managed Devices（API 28、API 36）：正确
- API 28 与 API 36 实体设备回归：仅凭源代码无法确认实际执行情况；仓库和 CI 可确认的是对应 API 的模拟器 Managed Devices
- 发布格式（Android App Bundle）：正确
- Play App Signing：源码仅配置外部上传密钥输入并声明由 Play 托管分发密钥；实际 Play Console 注册状态无法仅凭源码确认

## 排除项、版本冻结与额外依赖

- 未采用规范明确排除的 Flutter、React Native、Kotlin Multiplatform、XML View 主 UI、Room 3、裸 SQLCipher Cursor、SQLDelight、Realm、ObjectBox、未加密系统 SQLite、Google Maps SDK、MapLibre Compose、MPAndroidChart、Apache POI、Retrofit、RxJava、`androidx.security.crypto`、exact Alarm、Firebase、DuckDB、AppSearch 或 JavaScript 表达式引擎：正确
- 冻结关键版本（Kotlin 2.4.x、AGP 9.3.x、Gradle 9.5.x、Room 2.8.4、SQLCipher 4.17.0、MapLibre 13.4.1、Coil 3.5.x、Tink 1.23.x、FastExcel 0.20.2）：正确
- 直接依赖使用精确稳定版本并配置 dependency locking/verification：正确
- 规范未列出的直接第三方库：不完全一致。实际生产依赖还包含 ICU4J 78.3（CSV 字符集检测）和 `javax.xml.stream:stax-api:1.0-2`（FastExcel Android 兼容依赖）；debug 构建另包含 LeakCanary 2.14

## 总结

结论：不完全一致。源码可确认的主要差异有两类：

1. Drive 大型下载与完整恢复没有使用规范要求的 User-Initiated Data Transfer Job 或旧系统前台执行回退，而是在 `RestoreController` 协程内直接执行。
2. 实际直接技术依赖比冻结文档多出 ICU4J、StAX API，以及仅 debug 使用的 LeakCanary。

此外，Android Studio Quail 2、Android Studio Profiler、API 28/API 36 实体机回归和 Play App Signing 注册均属于仅凭源码无法确认的外部状态，因此不能据源码判定为“正确”。
