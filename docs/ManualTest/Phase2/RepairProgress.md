# ExpenseTracker 第二轮手动测试修复进度

> 约定：每个问题完成根因修复和针对性验证后立即记录；最终还会执行一次整体回归。

## MAN-P2-001｜已修复

- 根因：预算首页在可用额小于等于 0 时向 `LedgerProgressIndicator` 传入 `null`，而组件把 `null` 解释为不确定进度，因此显示永久旋转动画；分类预算编辑策略又把 `0` 当作有效限额持久化、把空输入当作错误，并且没有明确清除入口。
- 修复：零可用额现在使用确定进度（无消费为 0%，有消费为 100%）而不再进入加载态；`0`、空金额及“清除分类预算”按钮都会删除对应限额，清除一级分类时同时清除受其约束的二级限额；加载旧数据时忽略历史零限额，数据写入边界也会过滤零限额。
- 覆盖：分类预算从非零改为 `0`、改为空值、显式清除及零可用额进度均已加入 `BudgetPolicyTest`。
- 验证：`./gradlew :feature:planning:testDebugUnitTest --tests app.ledger.feature.planning.BudgetPolicyTest` 通过；随后已执行 `./gradlew --stop`，3 个 Gradle daemon 已停止。

## MAN-P2-002｜已修复

- 根因：`AnalysisHome` 在 `NO_DATA` 分支直接返回全屏空状态，绕过了包含上一周期/下一周期按钮的正常列表；顶层加载与失败分支也会用全屏状态替换周期控件。
- 修复：无数据状态改为“常驻周期控件 + 下方空状态”；分析首页/报告页在首次加载、周期切换加载和失败时保留当前周期状态与周期控件，失败信息显示在控件下方。周期控件增加了稳定测试标识，便于持续回归。
- 验证：`./gradlew :feature:analysis:testDebugUnitTest :app:compileDebugKotlin` 通过，确认分析模块与控制器集成编译成功；随后已执行 `./gradlew --stop`，2 个 Gradle daemon 已停止。

## MAN-P2-003｜已修复

- 根因：根 `LedgerScaffold` 在放置悬浮保存按钮时，还会在内容容器底部硬性扣除一整块 `bottomActionInset`；记账列表自身已经预留了可滚动的底部空间，二者叠加后形成了按钮背后的固定空白底板，内容无法滚到按钮后方。
- 修复：脚手架新增显式的 `fixedActionOverlaysContent` 布局模式，并只为共用收入/支出编辑器 `REC-003` 启用；内容现在延伸到悬浮按钮后方，按钮仍独立避让导航栏与输入法。列表自身保留底部 content padding，因此末项可以继续滚动到按钮和安全区之外。
- 验证：`./gradlew :core:designsystem:compileDebugKotlin :feature:record:testDebugUnitTest :app:compileDebugKotlin` 通过；随后已执行 `./gradlew --stop`，2 个 Gradle daemon 已停止，构建进程最终正常退出。

## MAN-P2-004｜已修复

- 根因：`REC-005` 的冻结导航契约要求非空 `allowedTypes: enumMask` 参数，但收入/支出编辑器点击“账户”时传入了空参数；`LedgerRouteContract.destination` 在导航边界校验必填参数并抛出异常，因此表现为点击即闪退。
- 修复：账户入口现在按 `UserAccountType` 生成完整且非零的账户类型位掩码；通用记账导航适配器会读取目标参数种类并用 `EnumMaskArgument` 进行强类型编码，不再错误地当作普通枚举或漏传。现有账户列表仍按活动状态过滤，编辑态允许保留当前停用账户。
- 覆盖：`OrdinaryRecordPolicyTest` 新增账户类型掩码和 `REC-005` 完整路由构造断言，确保必填参数、类型和最终路径持续有效。
- 验证：`./gradlew :feature:record:testDebugUnitTest --tests app.ledger.feature.record.OrdinaryRecordPolicyTest :app:compileDebugKotlin` 通过；随后已执行 `./gradlew --stop`，2 个 Gradle daemon 已停止。

## MAN-P2-005｜已修复

- 根因：`SearchableReferenceChooser` 把搜索框和账户 `LazyColumn` 作为两个直接子项放进 `Box`；Compose 的 `Box` 会让子项从同一原点层叠，导致搜索框覆盖单选行并出现错位。
- 修复：选择器内容改为明确的纵向 `Column`，搜索区固定在顶部，账户单选列表位于其下并设置最大高度与内部滚动；行间距、稳定键和整行 radio 触控语义保持一致。补充了无搜索结果状态及中/英/日文案，并为入口、搜索区和列表增加稳定测试标识。
- 验证：`:feature:settings:compileDebugKotlin` 在组合构建中成功，主应用 `compileDebugKotlin` 也成功。尝试编译 App 全量旧 androidTest 时被仓库既有的 P11/P12 等测试 API 漂移错误阻断（错误与本次选择器实现无关）；随后已执行 `./gradlew --stop`，2 个 Gradle daemon 已停止。该浮窗会在最终安装包设备回归中再次实测。

## MAN-P2-006｜已修复

- 根因：金额表达式组件只把纯字符串交给上层，数字键盘工具栏的运算符操作也只接收字符串；记录与账户模块因此只能把运算符追加到表达式末尾，完全丢失了文本框的光标和选区信息。
- 修复：金额表达式组件现在在组件边界统一持有 `TextFieldValue`，并由共享的 `MoneyExpressionEditing` 按当前选区执行编辑；`+`、`−`、`×`、`÷` 会插入光标位置或替换所选文本，删除键也会删除选区或光标前一字符。字符串接口会在外部状态同步时保留并约束光标位置，记录、退款、专项交易和账户编辑器统一复用该实现。
- 覆盖：新增 `MoneyExpressionEditingTest`，覆盖四种运算符在表达式中间插入、选区替换以及选区/单字符删除。
- 验证：`./gradlew --quiet :core:designsystem:testDebugUnitTest --tests app.ledger.core.designsystem.MoneyExpressionEditingTest :feature:record:testDebugUnitTest :feature:accounts:compileDebugKotlin :app:compileDebugKotlin` 通过；随后已执行 `./gradlew --stop`，1 个 Gradle daemon 已停止。

## MAN-P2-007｜已修复

- 根因：系统授权返回时，权限 launcher 回调与页面的 `ON_RESUME` 回调都会调用授权完成逻辑；旧实现每次都无条件压入一个 `REC-009`，于是回栈中出现两个相同的位置页。第一次点击“使用此位置”只弹出上层副本，第二次才真正返回记账页。
- 修复：授权完成与拒绝流程现在只能由仍位于 `SYS-001` 的权限页消费；第一个回调完成回栈迁移后，后到的重复回调会被幂等丢弃，不会重复创建定位会话或重复压入位置页。
- 覆盖：新增 `LocationPermissionCompletionPolicyTest`，断言只有权限页可消费完成事件，位置页和记账页上的迟到回调均被拒绝。
- 验证：`./gradlew --quiet :app:testDebugUnitTest --tests app.ledger.app.LocationPermissionCompletionPolicyTest :app:compileDebugKotlin` 通过；随后已执行 `./gradlew --stop`，2 个 Gradle daemon 已停止。

## MAN-P2-008｜已修复

- 根因：定位会话原本是总预算仅 3 秒的一次性对象，第一次超时后会被标记为永久完成；编辑器既没有创建下一次尝试，也没有手动重试入口，保存时反而可能再次等待这个已完成会话。定位任务同时缺少与表单保存、退出及应用前后台一致的取消边界。
- 修复：单次前台定位上限改为 15 秒，并新增严格串行的重试运行器：只有超时会在 1 秒后创建全新会话继续尝试，成功后停止；权限拒绝与系统定位服务不可用会进入明确的可处理终态。位置页为超时提供“重试定位”，为权限拒绝提供授权入口，为定位服务不可用提供系统定位设置入口。手动选点、清除位置、点击保存、丢弃/退出表单、切换顶层页面、应用进入后台或 ViewModel 销毁都会取消当前请求且阻止后续重试；回到前台时只恢复仍有效的未完成流程。保存不再等待定位，点击后会立即冻结当时已有的位置结果。
- 覆盖：`ForegroundLocationSaveSessionTest` 已改为验证完整 15 秒尝试预算；新增 `ForegroundLocationRetryRunnerTest`，覆盖连续两次超时后的串行重试、不可恢复结果终止，以及活动请求取消后不再重试。
- 验证：`./gradlew --quiet :core:geo:testDebugUnitTest :feature:record:testDebugUnitTest :app:compileDebugKotlin` 通过；随后已执行 `./gradlew --stop`，2 个 Gradle daemon 已停止。

## MAN-P2-009｜已修复

- 根因：主记账页的位置入口没有读取 `locationPresentation`，只检查持久化位置 ID；自动获取到的新位置在保存前只存在 `pendingLocation`，其 ID 仍为空，因此入口无论成功、超时、拒绝还是清除都被硬编码成“正在获取”。此外，地图组件失败会直接把整个定位状态覆盖为“地图不可用”，连已经成功获取的位置摘要也会丢失。
- 修复：主记账页与位置编辑页现在共用唯一的真实状态映射，明确区分尚未请求、正在获取、已获取、单次超时待重试、权限拒绝、系统定位服务不可用、用户选择不记录、手动选择及地图不可用；成功状态显示地点摘要与精度，手动状态显示已选地点。新增待记录、超时和服务不可用的独立设计系统文案。地图可用性被拆为独立字段，地图渲染失败只显示地图提示，不再覆盖定位成功/失败结果。
- 覆盖：新增 `RecordLocationPresentationTest`，逐项断言所有定位状态映射，并验证自动与手动位置的可识别摘要不会丢失。
- 验证：`./gradlew --quiet :core:designsystem:compileDebugKotlin :feature:record:testDebugUnitTest --tests app.ledger.feature.record.RecordLocationPresentationTest :app:compileDebugKotlin` 通过；随后已执行 `./gradlew --stop`，2 个 Gradle daemon 已停止。

## MAN-P2-010｜已修复

- 根因：金额框的 `autoFocus` 每次都由“表达式为空且处于新建/模板模式”重新计算；子页面返回或配置重建后条件仍为真，新的组合实例就再次执行 `requestFocus()`，没有任何状态记录首次聚焦已经发生。
- 修复：编辑器状态新增一次性的 `amountAutoFocusConsumed` 标志；金额组件只在标志未消费时请求焦点，并在焦点请求发出后立即回调消费。该标志由 ViewModel 中的同一编辑器状态持有，因此旋转、重新组合及账户、卡片、分类、位置等子页面往返不会重新触发；新建一个全新的空白编辑器仍保留首次自动弹出输入法的行为，之后只有用户主动点击金额框才会再次聚焦。
- 覆盖：`OrdinaryRecordPolicyTest` 新增一次性消费与重复消费幂等断言。
- 验证：`./gradlew --quiet :core:designsystem:compileDebugKotlin :feature:record:testDebugUnitTest --tests app.ledger.feature.record.OrdinaryRecordPolicyTest :app:compileDebugKotlin` 通过；随后已执行 `./gradlew --stop`，2 个 Gradle daemon 已停止。

## MAN-P2-011｜已修复

- 根因：系统认证成功后，ViewModel 先把应用锁控制器标为已解锁，却在主线程启动同步的加密数据库打开；同一主线程上的会话状态收集器无法及时消费 `Opening/Ready`，锁屏仍停留在 `AUTHENTICATING` 旋转状态。前一次后台上锁任务与新的解锁任务也没有串行化或去重，旧锁任务可能在认证成功后覆盖新状态；迟到或重复的认证回调同样没有状态边界。
- 修复：认证成功现在只允许由“Locked + Authenticating”的当前尝试消费，重复/迟到的成功和失败回调都会忽略；成功后同步把 UI 切到明确的 `Opening`，数据库打开移到 IO 调度器。后台锁任务和解锁任务通过作业与代际号串行协调：解锁会先等待既有锁任务结束，再从真实会话状态继续；新的锁定会取消旧解锁并立即恢复明确锁屏，旧任务不能覆盖新状态。锁定、前台回锁、测试锁和重试打开均不再在主线程执行数据库开关。`AppLockController.authenticationSucceeded()` 也改为一次性消费，只能从 Locked 转为 Unlocked。
- 覆盖：扩展 `AppLockControllerTest` 验证成功事件只能消费一次；新增 `AppUnlockTransitionPolicyTest`，覆盖当前认证成功、重复成功、Opening 阶段迟到成功及迟到失败。
- 验证：`./gradlew --quiet :core:security:testDebugUnitTest --tests app.ledger.core.security.AppLockControllerTest :app:testDebugUnitTest --tests app.ledger.app.AppUnlockTransitionPolicyTest :app:compileDebugKotlin` 通过；随后已执行 `./gradlew --stop`，2 个 Gradle daemon 已停止。

## 最终整体回归

- 自动化：`./gradlew --quiet testDebugUnitTest :app:assembleDebug` 全量通过，Debug APK 成功生成；随后已执行 `./gradlew --stop`，1 个 Gradle daemon 已停止。
- 安装：启动 `ExpenseTracker_API_36` 后，`:app:installDebug` 成功安装最新构建到 1 台模拟器；随后已执行 `./gradlew --stop`，1 个 Gradle daemon 已停止。
- Android 设备实测：确认默认账户浮窗的搜索栏与三条 radio 行按纵向排列且无重叠（MAN-P2-005）；收入/支出共用编辑器点击账户可稳定进入账户列表且返回后表单保留（MAN-P2-004）；把 `1234` 的光标置于 `1` 后点击 `+`，结果为 `1+234`（MAN-P2-006）；从账户页返回后输入法未重复弹出（MAN-P2-010）；首次授权位置权限后已获取“新位置点 · 精度约 100.0 米”，第一次点击“使用此位置”即返回（MAN-P2-007），主表单同步显示同一成功摘要而不再显示“正在获取”（MAN-P2-009）；编辑器底部内容可滚动到悬浮保存按钮后方且没有白色遮罩（MAN-P2-003）；切换到 2026 年 10 月无数据周期后，“上一期/下一期”仍保持可见可操作（MAN-P2-002）。
- 设备环境边界：该模拟器没有配置系统 PIN 或生物识别，应用锁页面明确显示“打开系统安全设置”，因此无法在不改变系统安全配置的前提下执行真实认证成功回调。MAN-P2-011 已由核心控制器与应用状态边界单测、全量 JVM 回归和安装构建覆盖；没有把未执行的系统认证设备场景误报为已通过。
- 清理：已关闭 agent-device 会话并正常停止 API 36 模拟器；最终 `git diff --check` 无格式错误。

## MAN-P2-012｜已修复

- 根因：卡片保险库在 `openList()` 时把实体卡引用复制到控制器私有缓存；从保险库空状态进入卡片管理并新增/编辑卡片后，权威 `ReferenceDataSnapshot` 虽然已经重新加载，更新却只发布给账户与记账页面，没有同步给仍存活的保险库控制器。因此返回原有保险库路由时继续使用旧缓存，只有重启进程并重新初始化后才会读到新卡。另外，旧的保险库密钥查询任务没有版本边界，较晚返回时可能覆盖更新后的列表。
- 修复：每次引用数据变更成功并重载权威快照后，都会在返回页面前把最新实体卡集合主动同步到保险库。同步会立即合并新增、改名与移除的卡，保留已知保险库配置标记，再异步从密文仓库校准；所有异步校准都带快照代际号，旧账本或旧列表的迟到结果会被丢弃。若保险库列表已解锁，普通卡片刷新会保留当前解锁会话；若当前详情卡已被移除，则安全关闭敏感值和待认证请求并回到锁定列表状态。
- 覆盖：新增 `VaultRuntimePolicyTest.newlyAddedPhysicalCardAppearsInTheNextReferenceSnapshot`，验证新引用快照会立即包含新增实体卡，同时保留旧卡已知的保险库配置标记；控制器集成编译通过。
- 验证：`./gradlew --quiet :app:testDebugUnitTest --tests app.ledger.app.VaultRuntimePolicyTest :app:compileDebugKotlin` 通过；随后已执行 `./gradlew --stop`，2 个 Gradle daemon 已停止。

## MAN-P2-013｜已修复

- 根因：存在两个独立缺陷。其一，使用 PIN、图案或设备密码时，Android 系统凭据界面可能先使 `MainActivity.onStop()`；旧后台处理不区分“系统认证暂时覆盖 Activity”和“用户真正离开应用”，会清空 `VaultController.pending`。其二，项目使用的 AndroidX Biometric 1.1.0 会让同一 `FragmentActivity` 中的 `BiometricPrompt` 共享 Activity 作用域回调；`MainActivity` 先后构造了应用锁、备份、保险库和敏感设置四个 prompt，最后构造的敏感设置回调覆盖了保险库回调。因此设备认证虽然成功，结果却被发送到错误业务，保险库的 `pending` 永远无人消费，页面一直停在“正在使用设备安全验证”。
- 修复：`MainActivity` 现在只构造一个 `BiometricPrompt`，由 `SystemAuthenticationCoordinator` 记录发起认证的业务通道，并把成功或终态错误精确路由回应用锁、备份、卡片保险库或敏感设置；同一业务的重复触发保持当前 prompt，其他并发请求立即收到失败回调，不再遗留新的等待态；Activity 重建时也保存当前业务通道。系统认证覆盖期间，保险库只隐藏短时明文而保留当前一次性请求，同时暂不启动应用锁后台计时；真正的普通后台或应用锁事件仍会取消请求并清空敏感值。
- 覆盖：`VaultRuntimePolicyTest.onlyAnActiveSystemPromptPreservesPendingAuthenticationAcrossActivityStop` 覆盖系统认证过渡保留、普通后台取消和无待认证时不误保留；新增 `SystemAuthenticationCoordinatorTest`，覆盖单 prompt 结果路由、同通道幂等触发、跨通道并发拒绝、结果一次性消费以及 Activity 重建时的通道恢复。
- 验证：`./gradlew --quiet :app:testDebugUnitTest --tests app.ledger.app.VaultRuntimePolicyTest --tests app.ledger.app.SystemAuthenticationCoordinatorTest :app:compileDebugKotlin` 通过；随后已执行 `./gradlew --stop`，2 个 Gradle daemon 已停止。
