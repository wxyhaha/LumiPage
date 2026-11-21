# LumiPage 测试方案、验证记录与已知问题 (TESTING.md)

> 最后更新：2026-11-20  
> 当前状态：Phase 0 完成，尚无可执行代码与自动化测试运行记录。

## 1. 测试策略

遵循“重要算法必须通过真实图片验证，不能仅以编译通过作为验收标准”的原则，LumiPage 采用四层测试体系：

### 1.1 JVM 单元测试 (`testDebugUnitTest`)
- `:core:model`：四顶点排序、归一化坐标转换、边界裁剪逻辑。
- `:core:imaging`（纯几何与状态机逻辑）：`QuadStabilizer` 帧间 IoU/防抖判定、目标透视尺寸计算、参数边界校验。
- `:core:data`：Repository 业务逻辑与路径映射规则。

### 1.2 真实图片计算机视觉验证（核心验收项）
在 Phase 1 建立真实文档测试样本集（覆盖以下场景），通过自动化测试或独立验证工具输出可视化结果图（检测框叠加图、透视矫正图、五种增强效果图）并进行人工/指标双审查：
1. **浅色桌面上的白纸文档**（低对比度边缘挑战）
2. **深色桌面上的白纸文档**（常规高对比度场景）
3. **存在明显斜向阴影或局部反光的文档**（验证背景光照归一化与去阴影效果）
4. **大角度倾斜拍摄的文档**（验证透视矫正几何精度与纵横比还原）
5. **含彩色印章、表格或荧光笔批注的文档**（验证彩色模式色彩保留与黑白模式文字清晰度）
6. **手写笔记 / 细小字体合同**（验证锐化与自适应二值化是否出现断笔或噪点）

### 1.3 Instrumentation / 集成测试 (`connectedDebugAndroidTest`)
- OpenCV Native 库在真机/模拟器上的加载与 `Mat` 内存释放压力测试（连续处理 50 张高清图无 OOM）。
- Room 数据库 CRUD、关联删除与迁移测试。
- `:core:pdf` 多页高清图片生成 PDF、合并、拆分、压缩及 `PdfRenderer` 读回校验测试。

### 1.4 端到端真机/模拟器手动验收
- CameraX 实时检测流畅度、自动拍摄稳定性、前后旋转与生命周期切换。
- 扫描 → 裁剪 → 增强 → 保存 → 文档管理 → PDF 导出与系统分享完整链路。

---

## 2. 测试执行记录

| 日期 | 阶段 | 执行项 | 结果 | 备注 |
| --- | --- | --- | --- | --- |
| 2026-11-20 | Phase 0 | 环境检测 (`java -version`, `ANDROID_HOME`, `adb devices`, `emulator -list-avds`) | 通过（仅环境盘点） | JDK 17 + SDK 35/36 就绪；当前无真机连接、无 AVD |
| 2026-11-20 | Phase 1 | `.\gradlew.bat assembleDebug testDebugUnitTest --no-daemon` | 通过 | `:app`, `:core:common`, `:core:imaging` 编译与单测通过，`libopencv_java4.so` 已打包 |
| 2026-11-20 | Phase 1 | 计算机视觉管线图片验证（Python OpenCV 4.10 等价移植，3 类典型场景） | 通过（调优后全过） | `dark_wood_strong_shadow` (score 0.900, err 0.0026)、`light_desk_low_contrast` (调优前失败，调优后 score 0.908, err 0.0026)、`textured_desk` (score 0.805, err 0.0026)；去阴影背景标准差由 9.5 降至 2.9 |
| 2026-11-20 | Phase 2 | `.\gradlew.bat :feature:scanner:testDebugUnitTest :app:assembleDebug --no-daemon` | 通过 | `QuadCoordinateMapperTest` 通过，CameraX 与扫描裁剪流程编译打包通过 |
| 2026-11-20 | Phase 3–6 | `.\gradlew.bat assembleDebug assembleRelease testDebugUnitTest :core:model:test --no-daemon` | 通过（654 tasks） | 全部 10 个模块编译、JVM 单元测试（含 `QuadSerializerTest`, `PdfRangeParserTest`）、`lintVitalRelease` 与 R8 `minifyReleaseWithR8` 均通过 |
| 2026-11-20 | Phase 6 | 真机安装启动 + `.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon` | 通过（2/2 tests on `2512BPNDAC` Android 17） | `LumiPageInstrumentedTest` 验证真机 OpenCV Native 加载、文档检测/透视矫正/5 种增强滤镜、PDF 生成/渲染/拆分/压缩全通过 |

| 2026-11-20 | Phase 6 | 真机实拍书本样本诊断 + 霍夫直线相交方案验证 | 通过（`real_2` 与 `dark_wood` 精准框出） | 实测翻开书本双页相连+手指压边角场景下，霍夫直线相交重建四顶点精准锁定书页；`real_1` 因上边缘超出画面外未构成闭合四边 |
| 2026-11-20 | Phase 6 | DocAligner `FastViT-T8` vs `FastViT-SA24` ONNX 模型评测 | `FastViT-SA24` 全面胜出 | `SA24` 在翻开书本 `real_2` 样本上 4 角点峰值达 `[0.767, 0.845, 0.789, 0.413]` 并精准锁定右页书；`dark_wood`/`light_desk` 达 `0.90+`（PC CPU ~45ms） |
| 2026-11-20 | Phase 6 | 16 KB ELF 页面对齐校验（OpenCV 4.14.0 + CameraX 1.5.0） | 通过（7/7 `.so` = `16384`） | APK 内全部 `arm64-v8a` 原生库均为 16 KB 对齐且 `compress=0` |
| 2026-11-21 | Phase 6 | FairScan LiteRT 分割模型 + 凸包 Huber 直线拟合验证 | 通过（4/4 样本全精准贴合） | 在翻开左/右页书本+手指压角 (`real_1`, `real_2`) 与桌面样本 (`dark_wood`, `light_desk`) 上均精准穿透手指还原真实书角 |
| 2026-11-21 | Phase 6 | 新版图像增强对比验证（`real_1`, `real_2`, `contract_stamp`） | 通过（效果质变） | 彻底消除米黄纸偏色与背面透字，黑字扎实锐利；印章/荧光笔/蓝笔 100% 保留色彩；Sauvola 黑白零黑斑 |
| 2026-11-21 | Phase 6 | `PP-LCNet_x1_0_doc_ori` TFLite 转换与 4 向朝向测试 + `±6°` 精细纠偏与外缘清理验证 | 通过（12/12 = 100%，精细纠偏误差 `<0.1°`） | `doc_ori.tflite`（6.44 MB）在 `real_1`/`real_2`/`dark_wood` 的 0/90/180/270° 旋转上 12/12 准确；`-3.5°` 注入倾斜还原为 `+3.50°` 摆正且左/底暗边清理干净 |
| 2026-11-21 | Phase 6 | `.\gradlew.bat :core:model:test testDebugUnitTest :app:assembleDebug --no-daemon` | 通过（227 tasks） | 包含 `PageDeskewer` 投影方差单测与 `TextOrientationDetector.OrientationResult` 单测；当前沙箱环境 `adb` 守护进程端口受限未能推送安装到真机 |

---

## 3. 已知问题与风险清单

1. **手动 UI 与相机真机体验待人工确认**：真机自动化集成测试已验证底层 OpenCV 与 PDF 引擎；CameraX 实时预览框贴合度、自动拍摄手感、暗色模式 UI 与系统分享面板需用户手持真机实际拍摄一份纸质文档完成最终交互确认。
2. **真实手机拍摄照片补充验证**：目前 Phase 1 验证使用的是高保真合成场景图（含斜向渐变阴影、椭圆遮挡、低对比度灰桌、印章与荧光笔）；后续接入真机后需补充真实手机拍摄的复杂折痕、手指遮挡与强反光样本回归。
3. **外部 PDF 合并/拆分方案待定（技术选型项）**：Android 原生 `PdfDocument` 仅支持生成新 PDF，`PdfRenderer` 仅支持渲染为 Bitmap；如需无损合并/拆分任意外部 PDF，将在 Phase 5 评估并引入合适的轻量 PDF 库（如 `pdfbox-android` 或 `openpdf`）并验证其 APK 体积与兼容性。
