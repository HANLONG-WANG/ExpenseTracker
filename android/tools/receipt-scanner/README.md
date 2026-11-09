# 小票扫描处理引擎（阶段 1）

实现位于 `app/src/main/java/com/yohaku/expensetracker/receipt/scan/`。这是无 UI 的同步处理引擎；现有系统相机、附件入库和云端办理入口未改。所有构造、调用和关闭在同一个后台工作线程上执行。

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
- 无合格候选返回 `ScanDetection.NotDetected` / `ScanProcessResult.NotDetected`；几何、模型、解码等真正错误仍抛出，不隐藏为「未检测到」。`ScanContour.centered()` 可供后续调整页初始化。
- `process` 在同一次解码中执行 detect + render；未编辑的 `Found` 保存原分割 mask 与 source geometry。单独 `render(photo, found)` 只运行 SyntheticDoc。只有 `render(photo, contour)` 将编辑轮廓回填为 mask 后重跑几何和展平，不运行 EdgeSAM。
- 轮廓使用 not-a-knot 三次样条连接五个控制点，能再现参考的三次边缘；上下端线连接左右端点，`withEnd` 可整体移动端线。越界或自交编辑会被拒绝。端线可见性由编辑数据传给展平器。
- 本阶段单工作线程，不含相机帧调度、时间平滑、自动拍摄判断或预览流水线并行。预览提供面积、重心、模型分数、拟合残差/离群率、端线可见性/宽松闭合标记、各阶段耗时，供后续相机层制定策略。
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
