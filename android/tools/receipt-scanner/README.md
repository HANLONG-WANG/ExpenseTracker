# 小票扫描：处理引擎、相机、确认与入库

实现位于 `app/src/main/java/com/yohaku/expensetracker/receipt/scan/`。其中处理引擎不依赖 UI；`ui/features/scan/` 提供相机→确认→调整的独立流程。表单/记录拍照、已有图片处理、现有媒体入库和云端偏好命令均已接入；三主题采用用户选定的设计 A。阶段 1–6 完成，待阶段 7 用户真机验收。引擎的构造、调用和关闭在同一个后台工作线程上执行。

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
- 引擎保持单工作线程；`ui/features/scan/` 负责相机帧调度、时间平滑与自动拍摄判断，没有预览流水线并行。预览提供面积、重心、模型分数、拟合残差/离群率、端线可见性/宽松闭合标记、各阶段耗时。
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
- 模型首次复制到 `filesDir/receipt-scanner/models/`，校验打包 SHA256/长度，临时文件写入并 fsync 后改名。相同长度但不同权重的模型更新也会重新复制。完整备份只包括数据库、偏好和附件；模型和 context 不属于附件目录，不进入完整备份；扫描偏好则随完整备份保存，并由既有偏好命令读写。

## 模型位置与来源

三个浮点 ONNX 位于 `app/src/main/assets/receipt-scanner/`，不压缩、直接随包分发，总计约 66.4 MiB。没有用户小票、量化变体或模型下载逻辑。

- EdgeSAM 3× encoder/decoder：官方 [EdgeSAM](https://github.com/chongzhou96/EdgeSAM) 发布的 [encoder](https://huggingface.co/spaces/chongzhou/EdgeSAM/resolve/main/weights/edge_sam_3x_encoder.onnx) / [decoder](https://huggingface.co/spaces/chongzhou/EdgeSAM/resolve/main/weights/edge_sam_3x_decoder.onnx)，沿用 ReceiptScanEval 已验证副本。采用 NTU S-Lab License 1.0，**仅限非商用**；用户已确认本应用不商用。原文随 APK 分发，见 [EdgeSAM 许可](../../app/src/main/assets/licenses/EdgeSAM-S-Lab-License.txt)。
- SyntheticDoc：ReceiptScanEval 的 `models/syntheticdoc/original_31x45.ckpt` 经 `eval/syntheticdoc.py` 导出，仅保留 backward map；输入 1×3×720×512，输出 1×2×31×45，opset 17。模型为 SyntheticDoc（ECCV 2026），许可见 [SyntheticDoc MIT](../../app/src/main/assets/licenses/SyntheticDoc-MIT.txt)。
- 运行库许可及 QNN NOTICE 原文均在 `app/src/main/assets/licenses/`；版本、来源与文件索引见 [第三方许可与模型来源](../../README.md#第三方许可与模型来源)。

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

## 应用内相机

入口是 `ScanCameraFlow(ScanCameraRequest(target), preferences, onBack, onCaptured)`。沿用 `FinancialFlow` 的全屏 Compose Dialog：拥有独立窗口和返回处理，隐藏系统栏（可边缘滑出），保留调用页面；不向 `AppViewModel` 的页面返回栈写入无人消费的拍照路由。调用方关闭时移除该 composable；`rememberReceiptCamera` 将 `ReceiptTarget.Draft/Record` 序列化为稳定 target，现有拍照按钮通过 `ScanFlow` 打开此页。

- `ScanCameraScreen.kt`：权限首次申请、拒绝重试/永久拒绝跳系统设置、全屏页面、生命周期和主题令牌；取景框、提示、快门分成独立 composable，专属三主题构图由 `ScanDesign.kt` 等呈现组件提供。无后置相机/相机或分析失败有独立说明和重试。
- `ScanCameraSource.kt`：CameraX Preview + RGBA_8888 ImageAnalysis（KEEP_ONLY_LATEST、4:3、请求 960×720）+ ImageCapture（MAXIMIZE_QUALITY、4:3、最高可用分辨率，允许高分辨率模式），共享 PreviewView ViewPort。实际流尺寸由 CameraX 根据设备组合能力协商。裁剪 RGBA、转正 BGR、`ImageProxyTransformFactory` + `CoordinateTransform` + FILL_CENTER 映射和像素中心 +0.5 沿用真机验证方法。
- `ScanCapturePolicy.kt`：纯 JVM 的平滑、提示、自动拍摄判定。`ScanFrameProcessor` 在一个专属分析线程创建、使用和关闭原有 `ReceiptScanner`。
- `ScanMotionMonitor.kt`：加速度计去重力后与陀螺仪共同判断手机静止；有哪个用哪个，都没有时只看画面。后台暂停传感器并清空进度，返回后不沿用旧帧。
- `ScanCaptureSession.kt`：原始 JPEG 写入 `cache/receipt-capture/<uuid>/original.jpg`。采用 **CameraX 写入 EXIF 方向**，不额外重新编码；`targetRotation` 在快门时刷新，引擎解码应用 EXIF。成功时先释放相机，再调用 `onCaptured(File, autoProcess)`；此后文件所有权交给确认流程，确认/放弃后删除文件及其父目录。返回、拍照失败、晚到的拍照回调会清理本次未交接目录。`ScanFlow` 接续确认/调整状态恢复，`ScanTemporaryFiles` 统一目录生成、写入、输出交接、删除及启动时的过期清理。
- 两个 DataStore 偏好：`scanAutoCapture` 默认 false、`scanAutoProcess` 默认 true；进入完整备份，旧备份缺字段采用上述默认。自动处理仅控制出口布尔值，相机页不执行 detect/render/process。通过 `preferences.scanAutoCapture` / `preferences.scanAutoProcess` 发布这两个偏好。
- Debug 角落显示分析帧率与当前结果的 `actualEps`；Logcat `ScanCamera` 输出各阶段耗时、平滑耗时和到达分析器至叠加的延迟。release 不显示/输出这些调试信息。

拍照入口统一打开 `ScanFlow`，不另行申请权限或维护另一套相机缓存。权限和不可用状态统一由 `ScanCameraScreen` 处理；Manifest 的 camera、autofocus 均 required=false，不排除无相机设备。FileProvider 保留附件/导出路径，并使用 `receipt-capture/` 传递扫描输出。

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

低于计划的 30% 门槛，保留单工作线程，不拆分引擎、不加入第二条流水线。以上是用户提供的 ReceiptBench 数据，非主应用实测；相机层的平滑耗时会在 debug 日志中报告。

## 确认与轮廓调整

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
// 相机返回的照片（转移所有权，必须在 cache/receipt-capture/<uuid>/）：
ScanFlowRequest(target = "draft-id", photo = capturedFile, source = ScanPhotoSource.CAMERA)
```

`ScanFlowResult(file: File, processed: Boolean, target: String)`：`processed=true` 是用户确认的处理后 JPEG；相机 `autoProcess=false` 直接移交原 JPEG 字节，`processed=false`，不进入处理或调整。调用方保持 request/target 稳定并在回调后移除 composable；`rememberReceiptCamera` 持久化 Draft/Record 目标并接入现有入库。

- 相机来源可重拍；已有图片来源显示取消。关闭按钮、系统 Back、取消流程都会明确调用 `onCancel`；重拍只回相机，不调用终态回调。
- 原图、自动结果和编辑结果统一暂存在 `cache/receipt-capture/<uuid>/`。已有图片先在后台复制，借用的源文件不受影响；相机照片直接接管拍摄会话目录。确认时将选中的 JPEG 移交到同根目录下新的输出目录，再删除本次原图及其他结果；原图直出也不额外编码。取消/重拍删除整个会话目录。
- `ScanTemporaryFiles` 统一相机和确认页的临时文件逻辑。`YohakuApplication` 启动时在后台清理超过 24 小时未更新的扫描目录（包括未消费的输出）；当前结果用临时文件写入后改名，取消后后台处理不能重新创建已删除目录。调用方应在导入后及时删除输出目录。
- 处理页在专用单线程后台执行 `ReceiptScanner.process`；重新处理仅调用 `render(photo, contour)`。真正的模型/文件/几何错误显示可重试状态，不伪装成未检测到。`NotDetected` 直接显示默认居中轮廓和「没找到小票，请拖动边框」。

### 坐标、拖动与出画判定

`ScanEditFrame` 将 EXIF 归一化的原照片按检测轮廓纵轴转正，照片仅为显示而旋转，不覆盖原始文件。左右边与上/下端的操作都使用这套固定的显示坐标；重新处理时把轮廓逆变换回原图坐标再交给引擎，保留原来的单次 remap。未检测到时仅应用 EXIF 方向，使用 `ScanContour.centered()`。

- 左右各 5 个控制点，上下端线的两端就是对应边的首/末点。沿用引擎 not-a-knot 三次样条；UI 和 mask 使用同一插值，未改 D7a 数值行为。
- 拖动目标先限制到原照片有效范围（不是旋转后外接矩形的空白角）。左右边保持至少 1 像素间距；插值后的每一边自上而下有序；上端的最大 y 小于下端的最小 y，间隔至少 1 像素；另由 `ScanContour` 的多边形校验拒绝边线自交。越过约束时沿拖动方向二分截到最后有效位置，不抛给用户。
- 手指旁显示 2.8 倍局部放大镜及准星；优先放上方，靠上边时放下方，并限制在视口内。照片适配显示，无双指缩放。
- `ScanContourEditor.END_EDGE_FRACTION = 0.01`：手动编辑后，将端线两端逆变换到 EXIF 原图，任一端点到任一原图边缘的距离 **≤原图短边的 1%**，该端即为出画（虚线）。两端都超过阈值才为 `inFrame=true`。这与引擎的语义一致：false 表示小票从图像边缘继续延伸，而非可见的真实纸张端部；自动结果原有判定保持不变，用户编辑后使用上述阈值。
- 自动 JPEG 独立保留，「恢复自动结果」取回原始精确结果，不拿五点近似代替分割 mask。编辑取消回到上次结果；未检测到且还没有结果时，取消调整会取消整个流程。

### 状态恢复与呈现

以 target/照片路径/来源名称作为跨进程稳定的组合键，通过 `rememberSaveable` + kotlinx.serialization JSON 保存照片路径、固定转正变换、自动轮廓、已应用轮廓及文件名、当前编辑轮廓、结果/调整模式、初始化/未检测到标志，以及流程的导入路径/原图直出/已结束标志。`ScanContour`、`ScanPoint`、`ScanEndLine` 可序列化。Activity 或进程重建后从缓存重新解码显示；已应用结果文件丢失时由保存的轮廓重新 render，自动结果缓存丢失时重新 process。位图、模型会话和拖动中的放大镜不保存。

`ScanReviewScreen` / `ScanReviewActions` / `ScanResultImage` 负责页面和操作；`ScanContourCanvas` 中边曲线、端线、控制点、放大镜各有独立绘制函数。页面使用设计 A 的三主题构图、各主题既有字体与控件、中日 `word(zh, ja)`；权限、处理中与失败状态也采用本主题的元素。表单与记录页入口、云端 `entry.prepareDraft(media=RECEIPT_CAMERA)` 均已接入此流程，并发布两个扫描偏好命令。

## 入库与云端偏好

- `MediaPanels.rememberReceiptCamera` 由 `MainActivity` 的 `ReceiptCameraHost` 持有，表单拍照使用 `ReceiptTarget.Draft(session)`，无小票的记录详情使用 `ReceiptTarget.Record(eventId)`。请求以 JSON 保存账本、目标、来源路径、主题/语言及表单保存选项；`ScanFlow` 同时保存相机/确认状态。Activity 重建或进程恢复后，入库使用原请求，不读取当前页面来改派目标。Host 先挂载应用/表单，后挂载扫描弹窗，恢复时扫描保持在最上层。
- 扫描输出通过 FileProvider 的 `receipt-capture/` 路径进入现有 `importFiles(receipt=true)` / `importRecordReceipts`，导入结束（含失败）清理输出目录。`processed=true` 的新照片和 `processed=false` 的原图都按保存开关入库：开时保留输出原字节，关时按现有小票优化规则保存。详情补票仍用全局保存偏好。相机权限只在扫描相机页申请。
- 表单每张缩略图下增加独立一行「处理这张 / 補正する」，删除仍在右上角，前移/后移另占一行，沿用各主题现有颜色与外框。入口使用 `Draft(session, replaceFileId)` 和 `EXISTING_IMAGE`；**无论自动处理开关如何，都进入确认/调整页**。新文件暂存与草稿原位置替换在同一媒体导入事务中完成，入库失败不移除旧图；旧文件沿用暂存/引用清理，编辑已存记录保存后生成新小票修订，旧图片字节和历史修订保留。扫描状态并入 `busy`，禁用其他媒体变更和表单提交。
- `AppPreferences` 增加两个偏好的命令读取与原子应用；`FollowupCommandAdapters` 注册 `preferences.scanAutoCapture` / `preferences.scanAutoProcess`，`enabled` 必填、全账本共享、默认分别 false / true，沿用偏好执行/撤销/冲突/中断恢复。不授予权限，不自动打开相机。名称及开关值覆盖 `CommandBusinessNames`、`CommandFollowupViews`、`CommandEffectViews`、`MonthlyScreens`。`CommandRegistry` 已通过既有 adapter 汇总导出 90 个命令，无额外硬编码注册。
- `entry.prepareDraft(media=RECEIPT_CAMERA)` 预览与续办明确引导用户在表单点「拍照」，由同一个入口打开扫描。云端不能直接指定本机图片路径或代替用户保存表单。
- 应用 assets 协议、`docs/formats/` 和 `docs/android-cloud-handoff-package/` 的协议/schema/capabilities 示例及数量说明同步；schema 和能力示例通过 `CommandCapabilitiesTest` 的 `-Dyohaku.updateCapabilities=true` 生成，不手工拼接。产品设计、实现规格、Android README、覆盖审计与 schema 审计同步。许可文件与模型来源、实测包体积及最终三主题设计已完成文档收尾。

## 三主题设计 A

杂志采用编号刊头、编辑校样框、方形快门与控制点；手账采用横线纸、胶带照片框、朱红笔迹与圈画放大镜；小物采用圆角相机窗口、实体快门与圆钮控制点。确认、调整、权限、无相机、处理中与错误状态都沿用本主题的构图。详见 [产品设计](../../../docs/product-design.md#122-三套主题)。

自动拍摄进度沿取景框外缘留白前进，不遮盖小票文字；定稿后加粗为杂志 4dp、手账 3dp 朱红分段线、小物 6dp 圆角线。快门旁状态为「拍后自动裁剪展平 / 拍后原图直出」，日文为「撮影後に自動補正 / 撮影後そのまま」。开关名称仍为「自动拍摄」「自动处理」，云端对应命令未改名。

## 验证

统一的 JVM、默认仪器流程、三主题视觉、S24 Ultra 与 320dp/fontScale 1.3 命令，以及 `captureSuffix` 的目录/文件名差异，见 [Android README：小票扫描验证](../../README.md#小票扫描验证)。Gradle 串行执行；阶段 6 仅核对文档/许可并构建 APK，不启动模拟器、不重跑视觉。

- `ScannerNumericsTest`：percentile/round/PCG64、PIL 双轴顺序与缩放、网格外延/align_corners、坐标组合/端线剪裁、五点三次轮廓/自交约束。`ScanCapturePolicyTest`：稳定/运动/开关/断帧/不重复触发、提示和平滑。`ScanContourEditorTest`：控制点约束、出画阈值、序列化与坐标往返、过期清理及输出交接。
- `ReceiptScannerTest`：五张合成图完整 process、分开 detect/render、编辑 render 不运行 EdgeSAM、preview 指标及 CPU EP、无候选输入、全部 8 种 EXIF 方向。与 Python JPEG90 金标准按 `compare_device.py` 的 identity/phase/ECC 纯平移候选（每轴 ≤8px）比较，去除 10px 边界后取最低 MAE；mask IoU ≥0.995、尺寸相同、MAE ≤8/255、角差 ≤0.1°、几何最大差 ≤3、端线可见性相同。
- `ScanCameraFlowTest`：真实引擎对合成静态帧做 preview；验证轮廓/提示、偏好持久化、手动与自动出口、权限状态、无相机、返回释放及晚到照片清理。权限为注入状态，不验证真实 CameraX/系统权限/传感器。
- `ScanReviewFlowTest`：已有图片使用、相机重拍、原图直出、拖动后重新处理、精确恢复自动结果、无票直接调整、取消清理、`ActivityScenario.recreate()` 后保留编辑轮廓；另有处理中/失败呈现。测试用与触摸相同的 `MoveScanHandle` 驱动控制点，放大镜截图保持拖动状态。`ScanFlowTestActivity` 仅存在于 debug。
- `MediaFlowTest`：表单/记录拍照入库、两种保存方式、取消与临时文件清理、已有图片原位置替换及旧修订保留、重建并切账本仍落到原目标、云端 `RECEIPT_CAMERA` 续办。重建测试不等同于真实杀进程测试。
- `PhotoStorageTest` / `BackupRoundTripTest`：旧备份默认与偏好往返；`CommandScanPreferencesTest` / `CommandCapabilitiesTest` / `CommandWorkflowExecutionTest`：两个扫描偏好的默认、原子写入、撤销/冲突/恢复和能力产物一致性。

主应用的 QNN 加速、缓存加载、真实相机/传感器、方向和新小票效果仍以阶段 7 真机验收为准，不能把 Python 参考或 APK 构建成功视作真机通过。
