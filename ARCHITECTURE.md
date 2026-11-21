# LumiPage 技术架构与模块设计 (ARCHITECTURE.md)

> 最后更新：2026-11-20  
> 当前阶段：Phase 0 — 架构设计已完成，尚未开始代码实现

## 1. 技术选型总览

| 维度 | 选型 | 选型理由 |
| --- | --- | --- |
| 开发语言 | Kotlin 2.1.x | Android 原生首选，协程与强类型模型提升可靠性 |
| UI 框架 | Jetpack Compose + Material 3 | 声明式 UI，便于构建相机叠加层、四顶点交互裁剪与现代化列表 |
| 架构模式 | Clean Architecture + UDF (MVI/MVVM) | 状态单向流动，UI 与业务/算法解耦，易于测试与维护 |
| 依赖注入 | 手动 DI (`AppContainer` + `ViewModelProvider.Factory`) | 零注解处理器开销、构建快、依赖关系透明，避免过度复杂化 |
| 相机框架 | CameraX (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`) | 抹平机型碎片化，原生支持 Preview + ImageAnalysis + ImageCapture 并发绑定 |
| 计算机视觉 | OpenCV Android 官方 AAR (`org.opencv:opencv:4.x`) | 成熟稳定的边缘检测、透视变换与图像增强算子，无需本地配置 NDK 编译链 |
| 本地持久化 | Room + Kotlin Coroutines Flow | 类型安全的 SQLite 抽象，响应式查询文档/文件夹/页面变更 |
| 图片加载 | Coil 3 (Compose) | 高效异步缩略图解码、磁盘/内存缓存与 Compose 原生集成 |
| PDF 处理 | Android `PdfDocument` / `PdfRenderer` + 必要时引入轻量开源 PDF 库（如 `pdfbox-android` 用于外部 PDF 合并/拆分） | 原生 API 处理图片转 PDF 与预览零额外体积；针对任意外部 PDF 合并/拆分在 Phase 5 评估最轻量稳定方案 |
| 最低/目标 SDK | `minSdk = 24`, `compileSdk = 35`, `targetSdk = 35` | 覆盖 Android 7.0+ 主流设备，适配 Android 15 边缘到边缘与分区存储 |
| 构建环境 | JDK 17, AGP 8.9.x, Gradle 8.11.x, Kotlin DSL + Version Catalog (`libs.versions.toml`) | 与当前工作区 JDK 17 及 Android SDK 35 完全匹配 |

## 2. 模块划分与职责边界

采用多模块 Gradle 结构，严格遵循单向依赖（Feature → Core → Model，Feature 之间不直接互相依赖）：

```text
LumiPage/
├── app/                   # 应用壳、Application、AppContainer、Navigation Graph、FileProvider
├── core/
│   ├── model/             # 纯 Kotlin 领域模型（无 Android 依赖，快速 JVM 单测）
│   ├── common/            # 协程调度器、统一错误/结果类型、内存安全 Bitmap/EXIF 工具
│   ├── imaging/           # OpenCV 引擎：文档检测、透视矫正、图像增强管线
│   ├── data/              # Room 数据库、DAO、本地文件存储管理、Repository 实现
│   └── pdf/               # PDF 生成、预览渲染、合并、拆分、压缩与导出服务
└── feature/
    ├── scanner/           # CameraX 扫描页、实时边框叠加层、批量队列、四顶点精调裁剪页
    ├── editor/            # 非破坏性图像增强编辑页（滤镜、滑块、原图对比、旋转）
    ├── documents/         # 文档库首页、文件夹、搜索、收藏、文档详情与页面管理
    └── pdf/               # PDF 预览器、导出/压缩配置、合并/拆分交互流程
```

### 各模块核心接口约定

1. **`:core:model`**
   - `Document`, `DocumentPage`, `Folder`
   - `NormalizedPoint(x: Float, y: Float)`（0f..1f 归一化坐标，消除不同分辨率下坐标换算误差）
   - `DocumentQuad(topLeft, topRight, bottomRight, bottomLeft)`
   - `EnhancementFilter` (`ORIGINAL`, `AUTO_ENHANCE`, `DOCUMENT_COLOR`, `GRAYSCALE`, `BLACK_AND_WHITE`)
   - `EnhancementParams(filter, brightness, contrast, sharpness)`
   - `PageEditState(quad: DocumentQuad, rotationDegrees: Int, enhancement: EnhancementParams)`
   - `PdfExportConfig(pageSize, marginDp, compressionPreset, outputFileName)`

2. **`:core:imaging`**
   - `DocumentDetector`：输入帧/图像（降采样至分析分辨率，如长边 640px）→ 输出 `DetectionResult(quad: DocumentQuad?, confidence: Float)`。
   - `QuadStabilizer`：对实时检测序列执行平滑滤波（EMA）与帧间 IoU/位移稳定性判定，用于驱动“自动拍摄”倒计时与防抖。
   - `PerspectiveCorrector`：根据归一化 `DocumentQuad` 计算目标矫正宽高（基于透视后真实纵横比），在高清原图上执行 `warpPerspective`。
   - `ImageEnhancer`：支持双分辨率管线——预览态（长边 ≤ 1600px，响应 < 150ms）与导出态（全分辨率，后台调度）。
   - **算法关键策略**：
     - 边缘检测采用多通路策略：LAB 亮度通道 / 灰度光照归一化 + 高斯/双边滤波 + 自适应 Canny 阈值与形态学闭运算，结合面积占比、凸性、内角范围（60°–120°）与长宽比进行候选四边形评分。
     - 阴影去除与背景净化采用大核形态学闭运算估计背景光照场，通过 `src / background * 255`（除法归一化）消除桌面阴影与光照不均，再执行对比度增强与轻微非锐化掩蔽（Unsharp Mask）。
     - 严格遵循 OpenCV `Mat` 生命周期管理：所有中间 `Mat` 使用 `use` / `try-finally` 及时 `release()`，杜绝 Native 内存泄漏。

3. **`:core:data`**
   - **存储策略（非破坏性）**：应用私有目录 `filesDir/lumipage/` 下按文档与页面 UUID 组织：
     ```text
     filesDir/lumipage/
     ├── originals/{pageId}.jpg       # 原始拍摄/导入图（仅写入一次，永不覆盖）
     ├── processed/{pageId}.jpg       # 当前编辑态渲染后的成品图
     ├── thumbnails/{pageId}.jpg      # 列表与网格用低分辨率缩略图（长边 480px）
     └── exports/                     # 临时导出与分享缓存（定期清理）
     ```
   - **Room 数据库表**：
     - `folders` (`id`, `name`, `parentId`, `createdAt`, `updatedAt`)
     - `documents` (`id`, `title`, `folderId`, `isFavorite`, `createdAt`, `updatedAt`, `coverPageId`)
     - `pages` (`id`, `documentId`, `orderIndex`, `originalPath`, `processedPath`, `thumbnailPath`, `quadJson`, `rotationDegrees`, `filterType`, `brightness`, `contrast`, `sharpness`, `width`, `height`, `createdAt`, `updatedAt`)
   - **一致性保障**：删除文档/页面时先通过事务更新数据库，再清理关联磁盘文件；支持孤儿文件清理机制。

4. **`:core:pdf`**
   - 流式逐页生成 PDF：每次仅解码并写入 1 页 Bitmap，写入完毕立即 `recycle()`，确保 50+ 页高清文档导出不发生 OOM。
   - 压缩策略：根据预设（`HIGH` 300dpi/90%, `BALANCED` 180dpi/78%, `COMPACT` 120dpi/65%）控制目标像素与 JPEG 压缩率。

## 3. 性能与内存管理规范

1. **实时分析帧率控制**：`ImageAnalysis` 使用 `STRATEGY_KEEP_ONLY_LATEST`，分析图限制长边 ≤ 640px，避免阻塞相机管线。
2. **高清图片解码**：严禁直接全尺寸解码用于 UI 展示；统一通过 `:core:common` 的 `BitmapLoader` 按目标尺寸计算 `inSampleSize` 并自动矫正 EXIF 旋转。
3. **后台线程隔离**：所有 OpenCV 运算、磁盘 I/O 与 PDF 生成均在 `Dispatchers.Default` / `Dispatchers.IO` 执行，严禁阻塞主线程。
4. **Native 内存安全**：OpenCV `Mat` 封装统一释放工具，处理大图前检查可用堆内存并限制单页最大处理边长（如 4096px）。

## 4. 关键架构决策记录 (ADR)

- **ADR-001 (2026-11-20)**：采用 OpenCV 官方 Maven Central AAR (`org.opencv:opencv:4.x`)，不引入自定义 NDK/C++ 层。原因：当前环境未安装 NDK，且 OpenCV Java/Kotlin 绑定已完整覆盖文档检测、透视矫正与图像增强所需的全部 C++ 底层算子，构建维护成本显著更低。
- **ADR-002 (2026-11-20)**：采用手动 `AppContainer` 依赖注入替代 Hilt。原因：减少 KSP/注解处理器版本耦合，加快增量编译，降低多 AI 工具协同修改时的配置摩擦。
- **ADR-003 (2026-11-20)**：裁剪四顶点统一以归一化坐标 `(0f..1f)` 持久化存储。原因：同一页面存在预览图、分析帧与原始高清图三种分辨率，归一化坐标可无损映射至任意分辨率并天然支持非破坏性重新裁剪。
- **ADR-004 (2026-11-20)**：`:core:pdf` 采用 `com.tom-roush:pdfbox-android:2.0.27.0` 配合 Android 原生 `PdfRenderer`。原因：原生 `PdfDocument` 无法直接嵌入压缩 JPEG 流且不支持合并/拆分/编辑已有 PDF；`pdfbox-android` 无 JNI 依赖、体积可控，结合 `PdfRenderer` 可完整覆盖多页流式生成、页面重排/旋转/删除、合并、拆分与重采样压缩。
- **ADR-005 (2026-11-21)**：文档边缘检测升级为 **FairScan DeepLabV3+ (LiteRT `2.3.2`, 4.92MB, GPLv3) 语义分割 + 凸包 Huber 直线拟合求交点 + `SEARCHING→LOCKED` 滞回追踪状态机**。原因：此前 `FastViT-SA24` (83.1MB) 对翻开书本与手指压角场景置信度不足且双引擎互抢导致闪动；语义分割模型可完整分割书页与单页文档，配合凸包过滤手指凹陷、Huber 四边直线拟合求交点及滞回锁定/死区防抖/60fps 阻尼动画，兼顾了极高精度、零闪动稳定性与更小 APK 体积。
