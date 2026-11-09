# 小票扫描处理引擎与扫描与入库流程（阶段 1–4）

实现位于 `app/src/main/java/com/yohaku/expensetracker/receipt/scan/`。其中处理引擎不依赖 UI；`ui/features/scan/` 提供相机→确认→调整的独立流程。阶段 4 已接入表单/记录拍照、已有图片处理、现有媒体入库和云端偏好命令。引擎的构造、调用和关闭在同一个后台工作线程上执行。

## API 与参考实现对应

| Kotlin 文件 | ReceiptBench Java 对应 | 职责 |
|---|---|---|
| `ReceiptScanner.kt` | `D7aPipeline` 对外调用 | `preview(Mat)`、`detect(ByteArray)`、`render(ByteArray, Found/ScanContour)`、`process(ByteArray)`；返回纯 JVM 数据，无需调用方释放 Mat |
| `Segmenter.kt` | `D7aPipeline.edgeInput/segment/decodeMask` | EdgeSAM 中心竖线三正点 + no-point token；覆盖提示、面积 3%–85% 的候选按分数/面积选取 |
| `ReceiptGeometry.kt` | `Geometry.java` | 主方向、鲁棒三次边缘、端线与可见性、转正外扩 10% 裁剪；预览端点交点失败时宽松闭合 |
| `Dewarper.kt` | `D7aPipeline.process` 展平部分 | SyntheticDoc、组合坐标、原图单次 remap、JPEG95 |
| `Grid.kt`、`GridMath.kt` | 同名 Java | 31×45 网格、外延、align_corners、分条坐标合成与 mask remap |
| `Numerics.kt` | `Numerics.java`、`CvScope.java` | round-to-even、quickselect、NumPy PCG64(2026)、70 次初始化 + 8 次 IRLS；显式 native 内存归属 |
| `PixelInputs.kt` | `PilBilinear.java`、`PixelInputs.java`、`MaskBounds.java` | Pillow 双轴缩放、22-bit 权重、RGB/归一化、逐行边界 |
| `ScannerModels.kt` | `ModelSessions.java` | 模型复制/完整性、懒加载会话、逐模型 EP 降级、context 缓存、一次节点 profiling |
| `ScannerWarmupWorker.kt` | 新增 | 后台预热、唯一任务 KEEP |
| `ScanContour.kt` | 新增 | 原图坐标中左右各 5 点、上下端线与可见性、平滑曲线、多边形 mask |

与评估程序的有意区别：

- OpenCV `IMREAD_COLOR` 解码应用 EXIF 的全部 8 种方向（包括镜像），不沿用评估程序的 `IMREAD_IGNORE_ORIENTATION`。轮廓、mask 和预览坐标始终以**方向已归一化的图片**为准。预览输入为调用方持有的、已转正的 BGR `CV_8UC3` Mat。
- JPEG 输出质量为 **95**（评估 Java/Python 为 90）。仪器测试仍以 Python JPEG90 金标准和原 `compare_device.py` 阈值比较，不放宽误差。
- 无合格候选返回 `ScanDetection.NotDetected` / `ScanProcessResult.NotDetected`；几何、模型、解码等真正错误仍抛出，不隐藏为「未检测到」。确认页用 `ScanContour.centered()` 初始化未检测到时的调整轮廓。
- `process` 在同一次解码中执行 detect + render；未编辑的 `Found` 保存原分割 mask 与 source geometry。单独 `render(photo, found)` 只运行 SyntheticDoc。只有 `render(photo, contour)` 将编辑轮廓回填为 mask 后重跑几何和展平，不运行 EdgeSAM。
- 轮廓使用 not-a-knot 三次样条连接五个控制点，能再现参考的三次边缘；上下端线连接左右端点，`withEnd` 可整体移动端线。越界或自交编辑会被拒绝。端线可见性由编辑数据传给展平器。
- 引擎保持单工作线程；阶段 2 的 `ui/features/scan/` 负责相机帧调度、时间平滑与自动拍摄判断，没有预览流水线并行。预览提供面积、重心、模型分数、拟合残差/离群率、端线可见性/宽松闭合标记、各阶段耗时。
- 移除了测速工具的 journal、模型精度诊断和导出。保留 D7a 数值运算顺序与 native Mat 的及时释放；ORT 环境是进程共享对象，会话随 scanner 关闭。

## EP、缓存和预热

- 预览 encoder：QNN HTP FP16 → QNN GPU → CPU；decoder 固定 CPU。
- 拍照 encoder 与 SyntheticDoc：QNN GPU → CPU；decoder 固定 CPU。
- CPU 6 线程、inter-op 1、SEQUENTIAL、ALL_OPT、禁用自旋。没有 XNNPACK、量化模型或 decoder/SyntheticDoc HTP 路径。
- debug 使用 `onnxruntime-android:1.29.0`（含 x86_64）；release 使用 `onnxruntime-android-qnn:1.29.0`（arm64、QNN runtime 2.42.0）。通过 ORT available providers 和后端文件检查直接跳过不可用 QNN，不每帧抛异常。
- QNN `backend_path` 是 `nativeLibraryDir/libQnnHtp.so` 或 `libQnnGpu.so` 的完整路径。HTP：`htp_performance_mode=burst`、`enable_htp_fp16_precision=1`、`htp_graph_finalization_optimization_mode=3`、`htp_arch=75`，沿用已验证的 S24 Ultra 配置。
- Context：`ep.context_enable` 只在预热编译时为 1，`ep.context_embed_mode=0`、`ep.context_file_path` 指向 context.onnx，允许 QNN 图内 CPU 节点回退。首次真实推理结束 profiling 后报告实际的 `CPU` / `QNN_GPU[+CPU]` / `QNN_HTP_FP16[+CPU]`；若 QNN 完全没执行，则继续尝试下一 EP。每次结果的 `diagnostics.actualEps` 只包含此次操作执行过的模型。
- 缓存放在 `noBackupFilesDir/receipt-scanner/contexts/`，每个模型/EP 单独目录。键为 SHA256（模型 SHA256 + ORT 运行时版本 + QNN 2.42.0 + `Build.FINGERPRINT` + ABI + EP + HTP 配置 + 四点固定维度 + 本实现版本）。context 和外部二进制一起管理；`ready.json` 最后原子发布并记录各文件长度。旧键/残缺缓存在预热时删除重建，加载/运行失败会删除并安排重建。
- 应用启动只提交唯一 WorkManager 任务 `receipt-scanner-model-warmup`（KEEP、非 expedited、延迟 15 秒、电量不低、后台线程优先级）。Worker 检查三个模型/EP 缓存，仅对缺失或失效者串行编译；不会加载 decoder。缓存已齐全时不创建模型会话。
- 前台引擎只加载已完成的 QNN 缓存，锁被预热占用或缓存未好时立即尝试下一 EP，最终可用 CPU。预热完成后，同一个 scanner 的下一次预览可自动尝试 HTP。加载/执行失败会 `Log.w` 并在本实例中禁用该失败组合；下次 scanner 可重新尝试修复后的缓存。`Log.w` 也记录缺失/不支持原因，同实例的重复原因只记录一次。
- 模型首次复制到 `filesDir/receipt-scanner/models/`，校验打包 SHA256/长度，临时文件写入并 fsync 后改名。相同长度但不同权重的模型更新也会重新复制。模型和 context 都不属于现有附件目录，完整备份只包括数据库、偏好和附件，因此无备份格式或云端命令变更。

## 模型位置与来源

三个浮点 ONNX 位于 `app/src/main/assets/receipt-scanner/`，不压缩、直接随包分发，总计约 66.4 MiB。没有用户小票、量化变体或模型下载逻辑。

- EdgeSAM 3× encoder/decoder：官方 [EdgeSAM](https://github.com/chongzhou96/EdgeSAM) 发布的 [encoder](https://huggingface.co/spaces/chongzhou/EdgeSAM/resolve/main/weights/edge_sam_3x_encoder.onnx) / [decoder](https://huggingface.co/spaces/chongzhou/EdgeSAM/resolve/main/weights/edge_sam_3x_decoder.onnx)，沿用 ReceiptScanEval 已验证副本。许可按批准计划记录为 NTU S-Lab License 1.0（非商用）。
- SyntheticDoc：ReceiptScanEval 的 `models/syntheticdoc/original_31x45.ckpt` 经 `eval/syntheticdoc.py` 导出，仅保留 backward map；输入 1×3×720×512，输出 1×2×31×45，opset 17。沿用批准计划中的 SyntheticDoc（ECCV 2026，MIT）模型。
- 运行库许可按批准计划：ONNX Runtime MIT、OpenCV Apache-2.0、QNN runtime Qualcomm 许可。

| 文件 | SHA256 |
|---|---|
| `edge_sam_3x_encoder.onnx` | `719a498cf5b3fe9be9f01ee513e13d3915f9028aa4f23dfd30eaaa0a17143159` |
| `edge_sam_3x_decoder.onnx` | `83a2174d54571596913dcb7455d021e713623c3dca30a31c8c41ab98c9fb0863` |
| `syntheticdoc_31x45.onnx` | `c70caa2c08ef54c1c8329d283b3c25143449522b89560c3afaf495748a5bdb7a` |

模型更新时同步 `ScannerModels.Model` 的 SHA256/字节数，并重新生成测试金标准。预热键会随模型变化自动更新。

## 可复现合成测试数据

`generate.py` 仅调用仓库外参考代码与模型，从零绘制白纸、虚构商品文字、条码、深色纹理背景，施加透视/卷曲。**不读取、枚举或复制 `ReceiptScanEval/work/`、`photos/`、`out/` 中的任何照片或衍生数据，也不修改参考代码。**

```sh
# 从本仓库根目录执行；参数也可用 RECEIPT_SCAN_EVAL 环境变量。
~/Projects/ReceiptScanEval/.venv/bin/python android/tools/receipt-scanner/generate.py \
  --eval-dir "$HOME/Projects/ReceiptScanEval/eval"
# 只重新导出纯数值小样本：追加 --numerics-only
```

依赖 Python 的 numpy、Pillow、opencv-python、onnxruntime，以及参考模块自身的依赖。字体为系统 Liberation Mono Regular（Fedora `/usr/share/fonts/liberation-mono-fonts/`，Debian `/usr/share/fonts/truetype/liberation2/`）。版本与模型 SHA256 记录在 `manifest.json`。参考模型必须与打包模型哈希一致；生成时强制断言所有样本候选合格、与程序纸张区域 IoU >0.95、五点拟合回填 IoU >0.98。

- `app/src/androidTest/assets/receipt-scanner/`：5 张 1300×2000 JPEG、每张的分割 PNG / 几何 JSON / 最终 JPEG90，以及深灰无候选 PNG、清单；合计约 1.9 MiB，仅打入测试 APK。
- `app/src/test/resources/receipt-scanner/numerics.json`：NumPy/Pillow/参考网格小样本，供 JVM 核验，无图片来源数据。
- 已在 Python 成功生成并查看合成输入/输出。五张分别为 flat、perspective、curled、bottom-cut、curled-bottom-cut；前 3 张两端在画面内，后 2 张下端出画。分割与程序纸张 IoU 0.987–0.990；默认轮廓回填 IoU 0.984–0.996。
- 深灰无票图在参考中无合格候选。此模型会对一些其他纯色背景给出候选，测试不引入额外分数阈值来改变 D7a。

## 验证命令

Gradle 必须串行。在 `android/` 下使用离线缓存；下面的任务按顺序执行，不能同时开多个 Gradle 进程：

```sh
GRADLE_USER_HOME=$PWD/.gradle-home GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches \
  ./gradlew --offline --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest --tests 'com.yohaku.expensetracker.receipt.scan.ScannerNumericsTest'

GRADLE_USER_HOME=$PWD/.gradle-home GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches \
  ./gradlew --offline --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:assembleRelease
```

`ScannerNumericsTest` 五项覆盖 percentile/round/PCG64、PIL 双轴顺序与放大、网格外延/align_corners、坐标组合/端线剪裁、五点三次轮廓/自交约束。

用户在 x86_64 模拟器运行以下仪器测试（无 UI，不需要 looks/visual 参数）：

```sh
GRADLE_USER_HOME=$PWD/.gradle-home GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches \
  ./gradlew --offline --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yohaku.expensetracker.receipt.scan.ReceiptScannerTest
```

`ReceiptScannerTest` 三项覆盖：五图完整 process/金标准/轮廓 IoU、分开 detect/render、编辑后 render 不运行 EdgeSAM、preview 指标、CPU EP；真实无候选输入；全部 8 种 EXIF 像素变换及 EXIF JPEG 与相同转正像素 PNG 的处理一致性。最终图使用 compare_device 相同的 identity/phase/ECC 纯平移候选（每轴 ≤8px），取去掉 10px 边界后的最低 MAE；mask IoU ≥0.995、尺寸完全相同、MAE ≤8/255、角差 ≤0.1°、几何最大差 ≤3、端线可见性相同，均不放宽。

仪器测试只在沙箱中编译，执行与 CPU 端到端验收待用户运行；QNN、缓存加载与实际加速仍须 arm64 真机验证。不得把 Python 成功或编译成功记成 Android 端到端通过。

2026-11-09 沙箱验证：`ScannerNumericsTest` 5/5 通过；`:app:assembleDebug`、`:app:compileDebugAndroidTestKotlin`、`:app:assembleRelease` 串行成功。release APK 249,612,540 字节（249.61 MB / 238.05 MiB）；debug APK 331,250,756 字节（331.25 MB / 315.91 MiB）。没有运行模拟器仪器测试或 QNN 真机测试。


## 阶段 2：独立相机页

入口是 `ScanCameraFlow(ScanCameraRequest(target), preferences, onBack, onCaptured)`。沿用 `FinancialFlow` 的全屏 Compose Dialog：拥有独立窗口和返回处理，隐藏系统栏（可边缘滑出），保留调用页面；不向 `AppViewModel` 的页面返回栈写入无人消费的拍照路由。调用方关闭时移除该 composable；阶段 4 的 `rememberReceiptCamera` 将 `ReceiptTarget.Draft/Record` 序列化为稳定 target，现有拍照按钮通过 `ScanFlow` 打开此页。

- `ScanCameraScreen.kt`：权限首次申请、拒绝重试/永久拒绝跳系统设置、全屏页面、生命周期和主题令牌；取景框、提示、快门分成独立 composable，阶段 5 再做专属设计。无后置相机/相机或分析失败有独立说明和重试。
- `ScanCameraSource.kt`：CameraX Preview + RGBA_8888 ImageAnalysis（KEEP_ONLY_LATEST、4:3、请求 960×720）+ ImageCapture（MAXIMIZE_QUALITY、4:3、最高可用分辨率，允许高分辨率模式），共享 PreviewView ViewPort。实际流尺寸由 CameraX 根据设备组合能力协商。裁剪 RGBA、转正 BGR、`ImageProxyTransformFactory` + `CoordinateTransform` + FILL_CENTER 映射和像素中心 +0.5 沿用真机验证方法。
- `ScanCapturePolicy.kt`：纯 JVM 的平滑、提示、自动拍摄判定。`ScanFrameProcessor` 在一个专属分析线程创建、使用和关闭原有 `ReceiptScanner`；阶段 1 API/引擎未改。
- `ScanMotionMonitor.kt`：加速度计去重力后与陀螺仪共同判断手机静止；有哪个用哪个，都没有时只看画面。后台暂停传感器并清空进度，返回后不沿用旧帧。
- `ScanCaptureSession.kt`：原始 JPEG 写入 `cache/receipt-capture/<uuid>/original.jpg`。采用 **CameraX 写入 EXIF 方向**，不额外重新编码；`targetRotation` 在快门时刷新，阶段 1 引擎解码应用 EXIF。成功时先释放相机，再调用 `onCaptured(File, autoProcess)`；此后文件所有权交给阶段 3，确认/放弃后删除文件及其父目录。返回、拍照失败、晚到的拍照回调会清理本次未交接目录。阶段 3 的 `ScanFlow` 接续确认/调整状态恢复，`ScanTemporaryFiles` 统一目录生成、写入、输出交接、删除及启动时的过期清理。
- 两个 DataStore 偏好：`scanAutoCapture` 默认 false、`scanAutoProcess` 默认 true；进入完整备份，旧备份缺字段采用上述默认。自动处理仅控制出口布尔值，相机页不执行 detect/render/process。阶段 4 已通过 `preferences.scanAutoCapture` / `preferences.scanAutoProcess` 发布这两个偏好。
- Debug 角落显示分析帧率与当前结果的 `actualEps`；Logcat `ScanCamera` 输出各阶段耗时、平滑耗时和到达分析器至叠加的延迟。release 不显示/输出这些调试信息。

阶段 4 已删除系统相机启动器、入口处的重复权限申请及旧相机缓存路径。权限和不可用状态统一由 `ScanCameraScreen` 处理；Manifest 的 camera、autofocus 均 required=false，不排除无相机设备。FileProvider 保留附件/导出路径，并使用 `receipt-capture/` 传递扫描输出。

### 自动拍摄与提示参数

参数集中在 `ScanThresholds`；位置和位移按转正后的帧宽高归一化，不随 UI 像素变化。

| 条件 | 当前值 |
|---|---|
| 连续合格时间 | 800 ms；帧间隔超过 1500 ms 重置 |
| 轮廓稳定 | 未平滑轮廓对应点的最大归一化位移 < 0.012 |
| 平滑 | EMA α=0.45；位移 >0.09 或无轮廓/长时间断帧直接重置 |
| 面积 | 0.16–0.78 |
| 中心偏离 | x ≤0.16、y ≤0.19 |
| 可靠性 | modelScore ≥0.80、拟合残差/短边 ≤0.018、离群率 ≤0.25、不使用宽松闭合 |
| 手机静止 | 陀螺仪 ≤0.12 rad/s、去重力加速度 ≤0.35 m/s²，运动后等待 400 ms |

检测失败提示「未找到小票」；中心不合格/面积过大提示「把小票放在中间」；面积过小提示「靠近一些」；轮廓不稳/可靠性不足提示「拿稳」；正常无提示。自动拍摄需要所有条件同时满足，进度中断即清零，手动拍照随时可用，每次相机会话最多自动触发一次；拍摄失败后可手动重试。

### 不做流水线化的依据

按用户 2026-11-09 追加指示，采用已有 **S24 Ultra 真机**数据，不启动模拟器测速：

- 第一轮 CPU_4、960×720：RGBA 1.5 ms、预处理 8.0、encoder 528、decoder 61、mask 后处理 3.9、geometry 20.9。各阶段中位数之和约 623.3 ms，模型后的 CPU 阶段约 24.8 ms（约 4.0%，这里只以中位数之和估算）。
- 第三轮 ENC_HTPFP16 + DEC_CPU：5.4–5.6 fps（约 180 ms/帧），encoder ≈16 ms、decoder ≈38 ms，mask 后处理 + geometry ≈25 ms，约 **14%**。

低于本阶段 30% 门槛，保留单工作线程，不拆分引擎、不加入第二条流水线。以上是用户提供的 ReceiptBench 数据，非本次新增测量；相机层新增的平滑耗时会在 debug 日志中报告。

### 阶段 2 验证

JVM：`ScanCapturePolicyTest`（稳定/不稳定/运动/关开关/断帧重置/不重复触发、提示、平滑），`PhotoStorageTest`（旧备份默认与序列化往返），回归 `ScannerNumericsTest`。

```sh
# android/ 下执行，所有 Gradle 调用串行。
GRADLE_USER_HOME=$PWD/.gradle-home GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches \
  ./gradlew --offline --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest \
  --tests 'com.yohaku.expensetracker.ui.features.scan.ScanCapturePolicyTest' \
  --tests 'com.yohaku.expensetracker.PhotoStorageTest' \
  --tests 'com.yohaku.expensetracker.receipt.scan.ScannerNumericsTest'

GRADLE_USER_HOME=$PWD/.gradle-home GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches \
  ./gradlew --offline --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:assembleRelease
```

2026-11-09 沙箱验证：上述 JVM 13/13 通过；`:app:assembleDebug`、`:app:compileDebugAndroidTestKotlin`、`:app:assembleRelease` 串行构建成功。阶段 1 引擎接口与实现未修改。

设备测试由用户运行；本轮不启动模拟器，不把编译通过写成仪器或视觉验收通过：

```sh
# 默认流程、完整备份往返、扫描入库回归。
GRADLE_USER_HOME=$PWD/.gradle-home GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches \
  ./gradlew --offline --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yohaku.expensetracker.ScanCameraFlowTest,com.yohaku.expensetracker.BackupRoundTripTest,com.yohaku.expensetracker.MediaFlowTest#cameraCapturesOneReceiptThroughImportAndRemovesTemporaryFile,com.yohaku.expensetracker.MediaFlowTest#recordDetailAddsFirstReceiptsWithGlobalPhotoSetting

# S24 Ultra 基准布局，视觉类方法输出三主题 × 中日文 × 提示/进度共 12 张。
adb shell wm size 1080x2340
adb shell wm density 450
GRADLE_USER_HOME=$PWD/.gradle-home GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches \
  ./gradlew --offline --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yohaku.expensetracker.ScanCameraFlowTest#staticReceiptShowsContourHintsAndAutomaticProgressAcrossLooks \
  -Pandroid.testInstrumentationRunnerArguments.looks=all \
  -Pandroid.testInstrumentationRunnerArguments.visual=true
# 查看后恢复 android/README.md 的原设备配置：
adb shell wm size 1170x2532
adb shell wm density 480
```

截图目录：应用私有 `files/verification/receipt-camera/`，文件名 `{theme}-{language}-{hint,progress}.png`，支持现有 `captureSuffix` 参数。`ScanCameraFlowTest` 使用测试 APK 的 `receipt-scanner/flat.jpg`，真实运行 `ReceiptScanner.preview` 后绘制静态预览；快门可立即返回这张图或延迟回调。覆盖开页轮廓/提示、两个开关持久化和重开、手动出口的 true/false、自动出口仅一次、权限拒绝/永久拒绝、无相机、返回按钮/系统 Back 释放资源及晚到照片清理。权限路径用可注入权限状态，不操作设备的真实系统授权。真实 CameraX/传感器、EXIF 与硬件方向仍需设备验收。

### 阶段 4 云端偏好连带位置（已接入）

- `data/repository/AppPreferences.kt`：`commandValue` / `commandValues` / `applyCommandPreference` 的原子写入、冲突检测、效果凭据。
- `data/command/FollowupCommandAdapters.kt`：偏好 capability、输入 schema、校验/预览、adapter 注册；`CommandBusinessNames.kt` 的中日名称。
- `ui/features/command/CommandFollowupViews.kt`、`CommandEffectViews.kt`、`MonthlyScreens.kt`：偏好名称、前后值和月评展示。
- `data/command/CommandRegistry.kt`（能力汇总）及 JVM `data/command/CommandCapabilitiesTest.kt`（schema/capabilities 生成与核验）；仪器 `CommandWorkflowExecutionTest`、`CommandFlowTest` 的偏好执行/撤销/恢复与展示覆盖沿用 `photoOriginal` 和 `haptics` 的路径。
- `docs/formats/yohaku-command-protocol-v1.md`、`command-plan.v1.schema.json`、`examples/capabilities.valid.json`，以及 `docs/android-cloud-handoff-package/formats/` 对应副本及包内命令数量说明；通过既有生成流程更新，不手工改 schema 大段内容。
- `entry.prepareDraft` 的 `RECEIPT_CAMERA` 续办入口与 `MediaPanels.kt` 的 `rememberReceiptCamera`、`ReceiptTarget`：已接入本页，目标恢复与云端续办由 `MediaFlowTest` 覆盖。


## 阶段 3：确认与轮廓调整

### 入口与文件归属

```kotlin
ScanFlow(
    request = ScanFlowRequest(target = "draft-id"), // 不传照片：打开相机
    preferences = preferences,
    onDone = { result: ScanFlowResult -> /* 消费 result.file，然后删除该文件及父目录 */ },
    onCancel = { /* 关闭流程 */ },
)
// 已有图片（借用，永不覆盖/删除）：
ScanFlowRequest(target = "draft-id", photo = existingFile, source = ScanPhotoSource.EXISTING_IMAGE)
// 阶段 2 返回的照片（转移所有权，必须在 cache/receipt-capture/<uuid>/）：
ScanFlowRequest(target = "draft-id", photo = capturedFile, source = ScanPhotoSource.CAMERA)
```

`ScanFlowResult(file: File, processed: Boolean, target: String)`：`processed=true` 是用户确认的处理后 JPEG；相机 `autoProcess=false` 直接移交原 JPEG 字节，`processed=false`，不进入处理或调整。调用方保持 request/target 稳定并在回调后移除 composable；阶段 4 的 `rememberReceiptCamera` 持久化 Draft/Record 目标并接入现有入库。

- 相机来源可重拍；已有图片来源显示取消。关闭按钮、系统 Back、取消流程都会明确调用 `onCancel`；重拍只回相机，不调用终态回调。
- 原图、自动结果和编辑结果统一暂存在 `cache/receipt-capture/<uuid>/`。已有图片先在后台复制，借用的源文件不受影响；相机照片直接接管阶段 2 的目录。确认时将选中的 JPEG 移交到同根目录下新的输出目录，再删除本次原图及其他结果；原图直出也不额外编码。取消/重拍删除整个会话目录。
- `ScanTemporaryFiles` 统一相机和确认页的临时文件逻辑。`YohakuApplication` 启动时在后台清理超过 24 小时未更新的扫描目录（包括未消费的输出）；当前结果用临时文件写入后改名，取消后后台处理不能重新创建已删除目录。调用方应在导入后及时删除输出目录。
- 处理页在专用单线程后台执行 `ReceiptScanner.process`；重新处理仅调用 `render(photo, contour)`。真正的模型/文件/几何错误显示可重试状态，不伪装成未检测到。`NotDetected` 直接显示默认居中轮廓和「没找到小票，请拖动边框」。

### 坐标、拖动与出画判定

`ScanEditFrame` 将 EXIF 归一化的原照片按检测轮廓纵轴转正，照片仅为显示而旋转，不覆盖原始文件。左右边与上/下端的操作都使用这套固定的显示坐标；重新处理时把轮廓逆变换回原图坐标再交给引擎，保留原来的单次 remap。未检测到时仅应用 EXIF 方向，使用 `ScanContour.centered()`。

- 左右各 5 个控制点，上下端线的两端就是对应边的首/末点。沿用引擎 not-a-knot 三次样条；UI 和 mask 使用同一插值，未改 D7a 数值行为。
- 拖动目标先限制到原照片有效范围（不是旋转后外接矩形的空白角）。左右边保持至少 1 像素间距；插值后的每一边自上而下有序；上端的最大 y 小于下端的最小 y，间隔至少 1 像素；另由 `ScanContour` 的多边形校验拒绝边线自交。越过约束时沿拖动方向二分截到最后有效位置，不抛给用户。
- 手指旁显示 2.8 倍局部放大镜及准星；优先放上方，靠上边时放下方，并限制在视口内。照片适配显示，无双指缩放。
- `ScanContourEditor.END_EDGE_FRACTION = 0.01`：手动编辑后，将端线两端逆变换到 EXIF 原图，任一端点到任一原图边缘的距离 **≤原图短边的 1%**，该端即为出画（虚线）。两端都超过阈值才为 `inFrame=true`。这与引擎的语义一致：false 表示小票从图像边缘继续延伸，而非可见的真实纸张端部；自动结果原有判定保持不变，用户编辑后使用上述阈值。
- 自动 JPEG 独立保留，「恢复自动结果」取回原始精确结果，不拿五点近似代替分割 mask。编辑取消回到上次结果；未检测到且还没有结果时，取消调整会取消整个流程。

### 恢复与 UI 替换点

以 target/照片路径/来源名称作为跨进程稳定的组合键，通过 `rememberSaveable` + kotlinx.serialization JSON 保存照片路径、固定转正变换、自动轮廓、已应用轮廓及文件名、当前编辑轮廓、结果/调整模式、初始化/未检测到标志，以及流程的导入路径/原图直出/已结束标志。`ScanContour`、`ScanPoint`、`ScanEndLine` 可序列化。Activity 或进程重建后从缓存重新解码显示；已应用结果文件丢失时由保存的轮廓重新 render，自动结果缓存丢失时重新 process。位图、模型会话和拖动中的放大镜不保存。

`ScanReviewScreen` / `ScanReviewActions` / `ScanResultImage` 负责页面和操作；`ScanContourCanvas` 中边曲线、端线、控制点、放大镜各有独立绘制函数。本阶段使用现有主题令牌与组件、中日 `word(zh, ja)`，专属三主题设计留待阶段 5。阶段 4 的表单与记录页入口、云端 `entry.prepareDraft(media=RECEIPT_CAMERA)` 均已接入此流程，并发布两个扫描偏好命令。

### 阶段 3 验证

JVM `ScanContourEditorTest` 覆盖越界/左右/顺序/端线约束、原图边缘出画阈值、平滑插值、序列化与转正坐标往返、过期清理与输出交接；回归 `ScannerNumericsTest` 和 `ScanCapturePolicyTest`。所有 Gradle 调用串行，在 `android/` 执行：

```sh
GRADLE_USER_HOME=$PWD/.gradle-home GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches \
  ./gradlew --offline --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest \
  --tests 'com.yohaku.expensetracker.receipt.scan.ScanContourEditorTest' \
  --tests 'com.yohaku.expensetracker.receipt.scan.ScannerNumericsTest' \
  --tests 'com.yohaku.expensetracker.ui.features.scan.ScanCapturePolicyTest'

GRADLE_USER_HOME=$PWD/.gradle-home GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches \
  ./gradlew --offline --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:assembleRelease
```

仪器与视觉由用户在模拟器执行；没有启动模拟器，尚未完成设备运行与目视验收：

```sh
# 流程 + 既有相机/引擎回归（真实合成小票，不需要系统相机权限）：
GRADLE_USER_HOME=$PWD/.gradle-home GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches \
  ./gradlew --offline --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yohaku.expensetracker.ScanReviewFlowTest,com.yohaku.expensetracker.ScanCameraFlowTest,com.yohaku.expensetracker.receipt.scan.ReceiptScannerTest

# S24 Ultra 布局基准，三主题 × 中日文 × {结果、调整含放大镜、未检测到}，共 18 张：
adb shell wm size 1080x2340
adb shell wm density 450
GRADLE_USER_HOME=$PWD/.gradle-home GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches \
  ./gradlew --offline --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yohaku.expensetracker.ScanReviewFlowTest#reviewAdjustmentMagnifierAndNoDetectionAcrossLooks \
  -Pandroid.testInstrumentationRunnerArguments.looks=all \
  -Pandroid.testInstrumentationRunnerArguments.visual=true
# 截图在应用私有 files/verification/receipt-review/，例如导出到本机：
mkdir -p /tmp/yohaku-receipt-review
adb exec-out run-as com.yohaku.expensetracker.dev tar -C files/verification/receipt-review -cf - . | tar -xf - -C /tmp/yohaku-receipt-review
# 查看后恢复 android/README.md 记录的模拟器配置：
adb shell wm size 1170x2532
adb shell wm density 480
```

`ScanReviewFlowTest` 使用 `flat.jpg` / `no-receipt.png` 真实运行引擎，覆盖已有图片→使用、相机→重拍、原图直出、拖动→重处理输出不同→取消调整/精确恢复自动结果、无票直接调整、取消清理和真实 `ActivityScenario.recreate()` 保留编辑轮廓。测试通过与触摸相同的 `MoveScanHandle` 动作驱动控制点，放大镜截图保持拖动状态。`ScanFlowTestActivity` 只存在于 debug，无 launcher，不进入 release。截图支持现有 `captureSuffix` 参数。

2026-11-09 沙箱验证：相关 JVM **14/14** 通过（`ScanContourEditorTest` 5、`ScannerNumericsTest` 5、`ScanCapturePolicyTest` 4）；`:app:assembleDebug`、`:app:compileDebugAndroidTestKotlin`、`:app:assembleRelease` 串行通过。仪器执行与 384×832dp 截图目视验收待用户运行。`ReceiptScanner.process/detect/render` 和 `ScanCameraFlow` 的公开接口未变；`ScanContour` 增加序列化及供编辑约束使用的未钳制插值选项，默认插值与处理数值保持原行为。

## 阶段 4：接入入库与云端偏好

- `MediaPanels.rememberReceiptCamera` 由 `MainActivity` 的 `ReceiptCameraHost` 持有，表单拍照使用 `ReceiptTarget.Draft(session)`，无小票的记录详情使用 `ReceiptTarget.Record(eventId)`。请求以 JSON 保存账本、目标、来源路径、主题/语言及表单保存选项；`ScanFlow` 同时保存相机/确认状态。Activity 重建或进程恢复后，入库使用原请求，不读取当前页面来改派目标。Host 先挂载应用/表单，后挂载扫描弹窗，恢复时扫描保持在最上层。
- 扫描输出通过 FileProvider 的 `receipt-capture/` 路径进入现有 `importFiles(receipt=true)` / `importRecordReceipts`，导入结束（含失败）清理输出目录。`processed=true` 的新照片和 `processed=false` 的原图都按保存开关入库：开时保留输出原字节，关时按现有小票优化规则保存。详情补票仍用全局保存偏好。系统相机启动器、入口权限申请、旧相机缓存路径均已移除。
- 表单每张缩略图下增加独立一行「处理这张 / 補正する」，删除仍在右上角，前移/后移另占一行，沿用各主题现有颜色与外框。入口使用 `Draft(session, replaceFileId)` 和 `EXISTING_IMAGE`；**无论自动处理开关如何，都进入确认/调整页**。新文件暂存与草稿原位置替换在同一媒体导入事务中完成，入库失败不移除旧图；旧文件沿用暂存/引用清理，编辑已存记录保存后生成新小票修订，旧图片字节和历史修订保留。扫描状态并入 `busy`，禁用其他媒体变更和表单提交。
- `AppPreferences` 增加两个偏好的命令读取与原子应用；`FollowupCommandAdapters` 注册 `preferences.scanAutoCapture` / `preferences.scanAutoProcess`，`enabled` 必填、全账本共享、默认分别 false / true，沿用偏好执行/撤销/冲突/中断恢复。不授予权限，不自动打开相机。名称及开关值覆盖 `CommandBusinessNames`、`CommandFollowupViews`、`CommandEffectViews`、`MonthlyScreens`。`CommandRegistry` 已通过既有 adapter 汇总导出 90 个命令，无额外硬编码注册。
- `entry.prepareDraft(media=RECEIPT_CAMERA)` 预览与续办明确引导用户在表单点「拍照」，由同一个入口打开扫描。云端不能直接指定本机图片路径或代替用户保存表单。
- 应用 assets 协议、`docs/formats/` 和 `docs/android-cloud-handoff-package/` 的协议/schema/capabilities 示例及数量说明同步；schema 和能力示例通过 `CommandCapabilitiesTest` 的 `-Dyohaku.updateCapabilities=true` 生成，不手工拼接。产品设计、实现规格、Android README、覆盖审计与 schema 审计同步。许可汇总、体积收尾与扫描页面专属三主题设计仍留在后续阶段。

### JVM 与构建

在 `android/` 下串行执行；首次更新能力产物时加 `-Dyohaku.updateCapabilities=true`，日常核验不加：

```sh
GRADLE_USER_HOME=$PWD/.gradle-home GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches \
  ./gradlew --offline --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDebugUnitTest \
  --tests 'com.yohaku.expensetracker.data.command.*' \
  --tests 'com.yohaku.expensetracker.data.repository.CommandScanPreferencesTest' \
  --tests 'com.yohaku.expensetracker.PhotoStorageTest' \
  --tests 'com.yohaku.expensetracker.receipt.scan.ScanContourEditorTest' \
  --tests 'com.yohaku.expensetracker.ui.features.scan.ScanCapturePolicyTest'

GRADLE_USER_HOME=$PWD/.gradle-home GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches \
  ./gradlew --offline --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :app:assembleDebug :app:compileDebugAndroidTestKotlin :app:assembleRelease
```

`CommandScanPreferencesTest` 在 JVM 通过内存 DataStore 运行真实命令读写，覆盖默认读取、全账本共享、应用/撤销、冲突不写凭据、重建偏好对象后的效果去重、必填布尔与合并撤销。能力测试逐项校验 90 个实际 adapter 输入示例、方案 schema 分支与四份生成产物。

### 用户运行的仪器与视觉验证

本阶段不启动模拟器。以下测试已提供，编译通过不代表设备测试或截图目视验收通过。

```sh
# android/ 下执行，默认数据与流程测试；系统选择器专项默认跳过。
GRADLE_USER_HOME=$PWD/.gradle-home GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches \
  ./gradlew --offline --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yohaku.expensetracker.MediaFlowTest,com.yohaku.expensetracker.CommandWorkflowExecutionTest#allPreferencesSwitchAfterCommitAndRestoreWithConflict,com.yohaku.expensetracker.CommandWorkflowExecutionTest#interruptedPreferenceWriteResumesWithoutOverwritingLaterUserChange

# 三主题 × 中日文，小票图片条中的处理、删除与排序入口；S24 Ultra 布局基准。
adb shell wm size 1080x2340
adb shell wm density 450
GRADLE_USER_HOME=$PWD/.gradle-home GRADLE_RO_DEP_CACHE=$HOME/.gradle/caches \
  ./gradlew --offline --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yohaku.expensetracker.MediaFlowTest#receiptProcessingActionsAcrossLooks \
  -Pandroid.testInstrumentationRunnerArguments.looks=all \
  -Pandroid.testInstrumentationRunnerArguments.visual=true

# 输出：应用私有 files/verification/receipt-media/{theme}-{language}-actions{captureSuffix}.png
mkdir -p /tmp/yohaku-receipt-media
adb exec-out run-as com.yohaku.expensetracker.dev tar -C files/verification/receipt-media -cf - . | tar -xf - -C /tmp/yohaku-receipt-media
# 查看截图后恢复原模拟器配置：
adb shell wm size 1170x2532
adb shell wm density 480
```

`MediaFlowTest` 使用阶段 2/3 的 `StaticReceiptSource` 与合成图片，真实执行扫描处理和现有媒体入库，覆盖：

- 表单拍照→确认→原字节入库与临时目录清理；取消不添加图片。
- `processed=false` 跳过确认：关原样保存时短边缩到 2000px，开时保留原字节。
- 记录详情拍照→确认→全局优化入库，形成第一版小票；确认页经过 Activity 重建仍指向原记录。
- 已有图片「处理这张」在自动处理关闭时仍进入确认页，重建后在三张图片的中间原位置替换；保存形成第二版，旧图与第一版保留。
- 确认页重建时另建 ViewModel、切换到另一个账本，输出仍写入原草稿会话，保留本次原样保存选择（模拟进程丢失内存状态，非实际杀进程测试）。
- 云端两个偏好的名称展示、`RECEIPT_CAMERA` 引导→打开草稿→新相机→重建→原会话入库；不自动提交记录。

`CommandWorkflowExecutionTest` 两个相关方法覆盖实际 Room + DataStore 的偏好能力快照、执行后值、整批撤销冲突与恢复，以及写入已完成但效果标记未落地时的重试去重，防止覆盖后来手动改动。

2026-11-09 阶段 4 沙箱验证：相关 JVM **50/50** 通过（含两个新偏好命令测试和能力生成/校验）；`:app:assembleDebug`、`:app:compileDebugAndroidTestKotlin`、`:app:assembleRelease` 串行调用通过。未启动模拟器、未运行仪器测试或生成本阶段设备截图；上述设备流程与视觉目视验收待用户执行。没有提交 git。
