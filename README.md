# LumiPage

**LumiPage** 是一款 Android 原生文档扫描与本地管理应用，基于 Kotlin、Jetpack Compose、CameraX、OpenCV、ONNX Runtime 与 Room 构建，致力于将纸质文档、书页与图片转化为清晰、规整、易于管理和分享的数字文档与 PDF。

> **本地优先（Local-First）· 非破坏性编辑（Non-Destructive）· 16 KB 页面对齐兼容**

---

## 核心功能

### 1. 文档扫描 (`:feature:scanner`)
- **标准 `3:4` 专业取景框**：基于 CameraX `UseCaseGroup + ViewPort` 实现预览、分析与拍照视野 1:1 对齐。
- **端侧双引擎边缘检测**：
  - 主引擎：**DocAligner `FastViT-SA24` ONNX**（`onnxruntime-android`，Letterbox 等比缩放 + 5×5 高锐度热力图角点定位）
  - 兜底引擎：**OpenCV 霍夫直线相交 + 多通路边缘轮廓评分**（应对翻开书本、手指压角等复杂场景）
- **交互式四顶点精调裁剪**：支持四顶点拖拽、角落局部放大镜（Loupe）、一键自动识别 / 全图、90° 旋转、相册多选导入与连续多页扫描队列。

### 2. 非破坏性图像增强 (`:feature:editor`)
- **五种增强模式**：原图、自动增强（背景光照场去阴影 + 对比度拉伸 + 色彩保留）、彩色文档、灰度增强、黑白文档（自适应二值化）。
- **实时参数微调**：亮度、对比度、锐化滑块调节，支持按住对比原图、旋转、基于原图重裁及“应用到全部页面”。
- **原图永久保留**：编辑状态（四顶点、旋转角度、滤镜与参数）独立持久化，随时可无损重新裁剪或切换滤镜。

### 3. 本地文档管理 (`:core:data`, `:feature:documents`)
- **Room 持久化与隔离存储**：`originals/`、`processed/`、`thumbnails/` 分离管理。
- **完整文档库交互**：网格/列表视图切换、搜索、排序（修改时间/创建时间/名称）、收藏、文件夹分类管理、长按多选批量移动/删除/合并导出。
- **文档详情管理**：页面排序、删除、追加扫描、单页再编辑与重命名。

### 4. PDF 引擎与导出分享 (`:core:pdf`, `:feature:pdf`)
- **图片转 PDF**：支持自适应原图、A4、Letter 页面尺寸与三档体积压缩（高清 / 均衡 / 极限压缩）。
- **PDF 工具箱**：支持任意本地 PDF 的页面预览、排序、旋转、删除，以及多 PDF 合并、按页码区间拆分与重采样压缩。
- **导出与分享**：支持保存至系统 `Download/LumiPage/`（MediaStore）及通过 `FileProvider` 调用系统分享。

---

## 技术栈与模块结构

- **语言与 UI**：Kotlin `2.1.20`、Jetpack Compose (Material 3)、Navigation、Coil 3
- **视觉与推理**：OpenCV `4.14.0`、ONNX Runtime Android `1.31.1` (`FastViT-SA24`)、CameraX `1.5.0`
- **存储与 PDF**：Room `2.7.1` (KSP)、`pdfbox-android:2.0.27.0` + Android `PdfRenderer`
- **构建环境**：JDK 17、AGP `8.9.1`、Gradle `8.14.3`、`minSdk = 24`、`compileSdk / targetSdk = 35`（支持 16 KB ELF 对齐）

```text
LumiPage/
├── app/                   # 应用壳、AppContainer 手动 DI、导航编排、FileProvider
├── core/
│   ├── model/             # 纯 Kotlin 领域模型（DocumentQuad、EnhancementParams 等）
│   ├── common/            # 协程调度器、安全 Bitmap/EXIF 解码、AppResult
│   ├── imaging/           # ONNX Runtime + OpenCV 边缘检测、透视矫正、图像增强
│   ├── data/              # Room 数据库、DAO、非破坏性 FileStorageManager、Repository
│   └── pdf/               # 流式 PDF 生成、渲染预览、合并/拆分/压缩与导出
└── feature/
    ├── scanner/           # CameraX 扫描、实时边框叠加、交互式放大镜裁剪
    ├── editor/            # 双分辨率非破坏性图像增强与重裁
    ├── documents/         # 文档库首页、文件夹、搜索、收藏、详情页
    └── pdf/               # PDF 预览导出与 PDF 工具箱
```

---

## 构建与测试

在项目根目录执行：

```powershell
# 构建 Debug APK
.\gradlew.bat assembleDebug

# 构建 Release APK（含 R8 混淆与资源压缩）
.\gradlew.bat assembleRelease

# 运行全部 JVM 单元测试
.\gradlew.bat testDebugUnitTest :core:model:test

# 运行已连接真机的 Instrumentation 集成测试
.\gradlew.bat :app:connectedDebugAndroidTest
```

---

## 项目文档

- [PRODUCT.md](PRODUCT.md) — 产品定位与功能规划
- [ARCHITECTURE.md](ARCHITECTURE.md) — 技术架构、模块边界与架构决策记录 (ADR)
- [DEVELOPMENT.md](DEVELOPMENT.md) — 开发环境与协作规范
- [TASKS.md](TASKS.md) — 分阶段任务清单与完成状态
- [CHANGELOG.md](CHANGELOG.md) — 开发变更记录
- [TESTING.md](TESTING.md) — 测试方案、实测数据与已知问题
