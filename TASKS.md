# LumiPage 任务清单与进度追踪 (TASKS.md)

> 最后更新：2026-11-20  
> 状态说明：`[x]` 已完成并验证 | `[-]` 进行中 | `[ ]` 待开始 | `[!]` 阻塞

## 当前总体进度

- **当前阶段**：Phase 1 ~ Phase 5 代码实现与 Debug/Release 构建、JVM 单元测试已全部完成；Phase 6 进入**真机端到端验收阶段**。
- **代码状态**：全部 10 个 Gradle 模块（`:app`, `:core:model`, `:core:common`, `:core:imaging`, `:core:data`, `:core:pdf`, `:feature:scanner`, `:feature:editor`, `:feature:documents`, `:feature:pdf`）编译通过，Debug APK 与 Release R8 APK 均打包成功。

---

## Phase 0：需求分析、架构设计与文档基线
- [x] 检查本地开发环境（JDK 17、Android SDK 35/36、adb、AVD 状态）
- [x] 编写 `PRODUCT.md`（产品定位、核心功能与交互规范）
- [x] 编写 `ARCHITECTURE.md`（技术选型、多模块架构、核心接口与 ADR）
- [x] 编写 `DEVELOPMENT.md`（环境说明、构建命令与协作规范）
- [x] 编写 `TASKS.md`、`CHANGELOG.md`、`TESTING.md` 基线文档

## Phase 1：Android 多模块工程搭建与计算机视觉核心 (`:core:model`, `:core:common`, `:core:imaging`)
- [x] 初始化 Gradle Wrapper (`8.14.3`)、Version Catalog (`libs.versions.toml`) 与多模块骨架
- [x] 实现 `:core:model` 领域模型（`Document`, `DocumentPage`, `Folder`, `DocumentQuad`, `EnhancementParams` 等）
- [x] 实现 `:core:common` 协程调度器、安全 Bitmap/EXIF 解码工具、通用结果封装
- [x] 在 `:core:imaging` 集成 OpenCV Android AAR (`4.11.0`) 与安全 `Mat` 资源管理封装 (`MatScope`)
- [x] 实现文档边缘检测算法 (`DocumentDetector`) 与帧间稳定器 (`QuadStabilizer`)
- [x] 实现四顶点排序与透视矫正算法 (`PerspectiveCorrector`)
- [x] 实现五种图像增强模式 (`ImageEnhancer`：原图、自动增强、彩色文档、灰度、黑白 + 亮度/对比度/锐化)
- [x] 准备文档测试样本集并完成算法图片验证（发现并修复浅色桌面低对比度漏检，已记录至 `TESTING.md`）

## Phase 2：核心扫描流程 (`:feature:scanner`)
- [x] 实现相机权限申请与降级引导（含相册导入入口）
- [x] 基于 CameraX 实现相机预览、高分辨率拍照与实时分析帧绑定
- [x] 实现实时文档四顶点检测叠加层（含 `QuadCoordinateMapper` FILL_CENTER 坐标映射）与自动拍摄稳定触发机制
- [x] 实现闪光灯、网格线、自动/手动拍摄切换、单页/多页连续扫描队列
- [x] 实现交互式四顶点手动裁剪页（含角落局部放大镜 Loupe、自动识别/全图切换、90° 旋转）

## Phase 3：非破坏性图像增强编辑 (`:feature:editor`)
- [x] 实现页面编辑页 UI（双分辨率预览、五种滤镜缩略图切换、亮度/对比度/锐化微调）
- [x] 实现按住对比原图、旋转、基于原图重新裁剪
- [x] 实现多页批量编辑与“应用到全部页面”
- [ ] 真机实测高清导出与预览双分辨率管线耗时与内存占用

## Phase 4：本地持久化与文档管理 (`:core:data`, `:feature:documents`)
- [x] 实现 Room 数据库（`folders`, `documents`, `pages` 表、DAO、关联统计查询）
- [x] 实现 `FileStorageManager`（非破坏性原图/成品图/缩略图存储与原子清理）
- [x] 实现文档库首页（网格/列表切换、缩略图、排序、收藏、搜索）
- [x] 实现文件夹管理（新建、移动文档、删除）
- [x] 实现文档详情页（页面网格、页面顺序调整、追加扫描、单页再编辑、删除、重命名）
- [x] 实现多选批量操作（批量移动、删除、合并导出 PDF）

## Phase 5：PDF 引擎与导出分享 (`:core:pdf`, `:feature:pdf`)
- [x] 实现流式多页图片转 PDF 引擎（A4/Letter/自适应、边距、压缩预设）
- [x] 实现 PDF 预览页与页面操作（排序、旋转、删除）
- [x] 实现 PDF 合并、拆分与压缩功能（`pdfbox-android` + `PdfRenderer`）
- [x] 实现导出到本地（MediaStore Downloads/LumiPage）与系统分享（`FileProvider`）

## Phase 6：稳定性、兼容性与回归验收
- [x] Release 构建与 R8/ProGuard 规则验证（OpenCV、PDFBox、Room、CameraX）
- [x] 真机安装与自动化集成测试验证（`2512BPNDAC` Android 17 上 OpenCV Native、5 种增强与 PDF 引擎全通过）
- [ ] 用户真机手持体验验收（CameraX 实时检测流畅度、自动拍摄、Scoped Storage 下载与系统分享）并更新最终结论
