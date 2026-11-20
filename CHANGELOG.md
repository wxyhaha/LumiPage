# LumiPage 开发变更记录 (CHANGELOG.md)

所有实质性的架构决策、模块新增、功能实现、算法调优与问题修复均按日期倒序记录于此。

## [Unreleased]

### 2026-11-20 — 修复扫描草稿状态残留、返回手势与旋转裁剪坐标同步问题
- **修复退出扫描再进入仍显示上一张未保存图片的 Bug**：
  - 根因：`MainActivity` 原本将所有 `viewModel(key = ...)` 挂载在同一个 Activity 级 `ViewModelStore` 下且 `key` 仅依赖 `backStack.size`；退出扫描再进入时 `key` 相同导致直接复用上一次未销毁的 `ScannerViewModel` 实例。
  - 修复：在 [MainActivity.kt](file:///d:/studyPlace/LumiPage/app/src/main/kotlin/com/lumipage/MainActivity.kt) 中为每个导航栈节点分配唯一 `sessionId` 与独立 `ViewModelStoreOwner`，出栈 (`popBack`) 或切换页面时立即调用 `ViewModelStore.clear()` 彻底销毁旧实例。
- **修复单页模式返回重拍导致多张图片混杂的 Bug**：
  - 在 [ScannerViewModel.kt](file:///d:/studyPlace/LumiPage/feature/scanner/src/main/kotlin/com/lumipage/feature/scanner/ScannerViewModel.kt) 中，单页模式（`!isBatchMode`）下从裁剪页返回相机时自动清空并删除当前未确认的临时草稿文件；退出扫描或从 `EditorNewScan` 返回时自动清理未保存的 `drafts/` 临时文件。
- **修复系统返回手势（`BackHandler`）缺失导致的返回混乱**：
  - 在 `MainActivity`、`ScannerRoute`（裁剪页返回相机、相机返回首页）、`EditorRoute`（重裁返回编辑页）统一接入 `BackHandler`。
- **修复旋转 90° 与裁剪四顶点坐标空间不一致的 Bug**：
  - 在 [DocumentQuad.kt](file:///d:/studyPlace/LumiPage/core/model/src/main/kotlin/com/lumipage/core/model/DocumentQuad.kt) 新增 `rotate90Clockwise()`，旋转页面时同步旋转 `quad` 坐标，并将 [PerspectiveCorrector.kt](file:///d:/studyPlace/LumiPage/core/imaging/src/main/kotlin/com/lumipage/core/imaging/PerspectiveCorrector.kt) 统一为先旋转原图再按 `quad` 透视裁剪，确保与裁剪页/重裁页视觉坐标系完全一致。

### 2026-11-20 — 全面支持 Android 16 KB 页面对齐
- 将 `org.opencv:opencv` 升级至 `4.14.0`、`androidx.camera:*` 升级至 `1.5.0`，消除 Android 17 真机启动时的 16 KB ELF 未对齐兼容性警告。
- 校验 APK 内全部 7 个 `arm64-v8a` `.so`（含 `libopencv_java4.so`, `libc++_shared.so`, `libsurface_util_jni.so`, `libimage_processing_util_jni.so`, `libonnxruntime.so`, `libonnxruntime4j_jni.so`, `libandroidx.graphics.path.so`）的 `PT_LOAD` 对齐均为 `16384`。

### 2026-11-20 — 升级旗舰模型 FastViT-SA24 (83.1MB) 与移除自动拍摄
- **模型升级**：将端侧 ONNX 模型从 `FastViT-T8` (13.2MB) 升级为旗舰级 `FastViT-SA24` (83.1MB, 20.8M 参数)，在您实拍的翻开书本样本上 4 角点热力图峰值由 `0.005` 提升至 `0.77~0.85`，桌面样本达 `0.90+`。
- **角点贴合度优化**：将热力图角点质心邻域从 `radius=14` 收窄至 `radius=2`（5x5 高锐度加权），避免角点向内拉偏，并增加内角合法性校验过滤缺角误检。
- **交互优化**：移除自动拍摄触发与顶部开关，改为纯手动专业快门配合实时边框辅助。
- **新增 ONNX Runtime 端侧推理**：在 `:core:imaging` 集成 `com.microsoft.onnxruntime:onnxruntime-android:1.31.1` 与 DocAligner `fastvit_t8.onnx`（13.2MB, Apache 2.0），新增 [OnnxDocumentDetector.kt](file:///d:/studyPlace/LumiPage/core/imaging/src/main/kotlin/com/lumipage/core/imaging/OnnxDocumentDetector.kt) 实现亚像素热力图质心角点定位。
- **双引擎融合策略**：`DocumentDetector` 优先使用 `FastViT-T8` 深度学习角点定位（覆盖桌面单页、浅色低对比度桌面、复杂背景），在翻开书本等未覆盖场景置信度不足时自动回退至霍夫直线相交与多通路轮廓检测。

### 2026-11-20 — 基于真机实拍样本的边缘检测方案升级（霍夫直线相交 + ViewPort 对齐）
- **根因诊断**：拉取真机实拍的翻开书本样本分析发现，纯传统 `findContours` 在“双页白纸相连 + 书脊折痕 + 手指压住边角”场景下会将相邻书页与手指融为一体导致漏检/误检；且 `Preview` 与 `ImageAnalysis` 未通过 `ViewPort` 绑定会导致预览视野裁剪不一致。
- **方案升级**：
  - `DocumentDetector` 新增“大核闭运算去文字 + 敏感 Canny + 霍夫直线段聚类求交点 (`detectByHoughLines`)”主引擎，与多通路轮廓检测择优，实测在翻开书本与手指遮挡场景下依然可精准重建四边交点。
  - `ScannerScreen` 升级为 `previewView.post { UseCaseGroup.setViewPort(viewPort) }` 统一绑定 `Preview`、`ImageCapture` 与 `ImageAnalysis`，保证实时框视野与屏幕预览 100% 对齐。

### 2026-11-20 — 真机实测 Bug 修复与边缘检测稳定性升级
- **修复裁剪页点击“下一步”无响应**：将 `ScannerEvent.CompletedScan` 的事件收集从 `CameraScannerScreen` 提升至顶层 `ScannerRoute`，避免进入 `CropScreen` 后因 `CameraScannerScreen` 退出组合而导致事件丢失。
- **修复实时文档识别闪动与难检出问题**：
  - `ScannerViewModel` 增加 `AtomicBoolean` 忙帧丢弃门控与分析帧及时 `recycle()`，消除多协程并发乱序覆盖 `QuadStabilizer` 造成的边框剧烈闪烁。
  - `QuadStabilizer` 增加单帧大幅跳变抑制（需连续 2 帧确认新区域才切换），提升稳态平滑度。
  - `DocumentDetector` 新增中值滤波降噪、Otsu 纸张前景分割通路及高矩形度最小外接矩形兜底，大幅提升真机摄像头在真实桌面环境下的纸张检出稳定性。

### 2026-11-20 — Phase 2 ~ Phase 6（全功能模块开发与 Release 构建验证）
- **Phase 2 (`:feature:scanner`)**：
  - 实现 CameraX (`1.4.1`) 实时预览、`ImageAnalysis` 实时文档检测与 `ImageCapture` 高清拍照，集成相机权限申请与相册多选导入降级。
  - 实现 `QuadCoordinateMapper`（精确处理 `FILL_CENTER` 预览裁剪下的归一化坐标映射）与 `DocumentQuadOverlay`（平滑动态边框、网格线、自动拍摄稳定进度）。
  - 实现 `CropScreen` 交互式四顶点拖拽精调与角落局部放大镜 (`CornerLoupe`)，支持自动识别/全图/90° 旋转与多页队列管理。
- **Phase 3 (`:feature:editor`)**：
  - 实现双分辨率非破坏性增强编辑页 `EditorRoute`，缓存透视矫正基准图以实现毫秒级五种滤镜与亮度/对比度/锐化微调预览，支持“按住对比原图”、90° 旋转、基于原图重裁及“应用到全部页面”。
- **Phase 4 (`:core:data`, `:feature:documents`)**：
  - 基于 Room (`2.7.1` + KSP `2.1.20-2.0.1`) 实现 `folders`、`documents`、`pages` 三表持久化与 `FileStorageManager` 非破坏性原图/成品图/缩略图隔离存储。
  - 实现文档库首页（网格/列表视图、搜索、排序、收藏、文件夹筛选与新建、长按多选批量移动/删除/合并导出）与文档详情页（页面排序、删除、追加扫描、单页再编辑、重命名）。
- **Phase 5 (`:core:pdf`, `:feature:pdf`)**：
  - 集成 `com.tom-roush:pdfbox-android:2.0.27.0` 与原生 `PdfRenderer`，实现可控尺寸（A4/Letter/自适应）与三档画质压缩的图片转 PDF、PDF 预览、页面排序/旋转/删除、多 PDF 合并、页码区间拆分、PDF 压缩、MediaStore 下载保存与 `FileProvider` 系统分享。
- **Phase 6（构建与 R8 验证）**：
  - 清理 Compose `AutoMirrored` 图标弃用警告，完善 `app/proguard-rules.pro` 混淆规则。
  - 跑通全量 10 模块 `assembleDebug assembleRelease testDebugUnitTest :core:model:test`（654 个任务全数通过），待连接真机完成端到端验收。

### 2026-11-20 — Phase 1：多模块工程搭建与计算机视觉核心
- **工程骨架**：
  - 初始化 Gradle Wrapper `8.14.3`、AGP `8.9.1`、Kotlin `2.1.20`、Compose BOM `2025.02.00` 及 Version Catalog (`gradle/libs.versions.toml`)。
  - 建立 `:app`、`:core:model`、`:core:common`、`:core:imaging` 四个模块，打通 `assembleDebug`（成功打包 `libopencv_java4.so`）。
- **核心领域与基础工具 (`:core:model`, `:core:common`)**：
  - 实现 `NormalizedPoint`、`DocumentQuad`（含顺时针排序、凸性校验、面积与插值）、`EnhancementParams`、`Document` / `DocumentPage` / `Folder`。
  - 实现 `BitmapLoader`（基于 `inSampleSize` 采样 + `ExifInterface` 方向矫正）、`DispatcherProvider` 与 `AppResult`。
- **计算机视觉引擎 (`:core:imaging`)**：
  - 集成 `org.opencv:opencv:4.11.0`，实现 `OpenCvInitializer` 与 `MatScope` 自动 Native 内存释放。
  - 实现 `DocumentDetector`（灰度、LAB+CLAHE、HSV-S 三通路边缘检测与多维度几何评分）、`QuadStabilizer`、`PerspectiveCorrector`、`ImageEnhancer`（五种滤镜 + 降采样背景光照场去阴影 + 非锐化掩蔽）。
  - 通过 3 类典型场景图片（深色木纹+强阴影、浅色桌面低对比度、杂乱纹理桌面）验证并调优算法：针对浅色桌面白纸低对比度漏检问题，将 LAB 通路升级为 `13x13` 闭运算 + 低阈值敏感 Canny `(18, 55)`，三类场景均实现 0.26% 以内角点误差精准检出。

### 2026-11-20 — Phase 0：项目启动与文档基线
- **环境盘点**：确认本地具备 JDK 17 (`17.0.9`)、Android SDK 35/36、Build Tools 34–37 及 `adb`；确认当前无已连接真机、无 AVD 且未安装 NDK。
- **架构与产品文档建立**：
  - 新增 `PRODUCT.md`：明确 LumiPage 产品定位、四大核心模块功能规划与交互原则。
  - 新增 `ARCHITECTURE.md`：确定多模块 Clean Architecture + Compose + CameraX + OpenCV AAR + Room + 手动 DI 技术方案与核心数据/存储规范（ADR-001 ~ ADR-003）。
  - 新增 `DEVELOPMENT.md`：建立本地构建说明、代码规范、内存管理规范与 AI 协作文档同步规范。
  - 新增 `TASKS.md`：拆分 Phase 0 ~ Phase 6 阶段性开发任务清单。
  - 新增 `TESTING.md`：制定单元测试、真实图片算法验证、PDF 验证与端到端回归测试方案。
- **代码状态**：尚未生成 Android 工程代码，等待进入 Phase 1 实施。
