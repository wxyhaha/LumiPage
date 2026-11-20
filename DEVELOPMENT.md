# LumiPage 开发规范与运行说明 (DEVELOPMENT.md)

> 最后更新：2026-11-20  
> 当前阶段：Phase 6（全功能模块已实现，Debug/Release 构建与 JVM 单测验证中，待真机端到端验收）

## 1. 本地开发环境要求

当前工作区已验证环境如下：
- **OS**：Windows
- **JDK**：OpenJDK 17 (`17.0.9`)
- **Android SDK**：`C:\Users\wxy\AppData\Local\Android\Sdk`
  - 已安装平台：`android-35`, `android-36`
  - 已安装 Build Tools：`34.0.0`, `35.0.0`, `36.1.0`, `37.0.0`
- **Gradle**：Phase 1 初始化时配置 Gradle Wrapper (`8.11.x`)，无需全局安装 Gradle。
- **调试设备**：当前未连接真机且未配置 AVD；后续真机相机与集成测试需通过 `adb` 连接真机或创建 Android 15 (API 35) 模拟器。

## 2. 常用构建与验证命令（Phase 1 初始化后生效）

在项目根目录 `d:\studyPlace\LumiPage` 下执行：

```powershell
# 编译 Debug APK
.\gradlew.bat assembleDebug

# 运行全部 JVM 单元测试
.\gradlew.bat testDebugUnitTest

# 运行指定模块单元测试（例如成像核心模块）
.\gradlew.bat :core:imaging:testDebugUnitTest

# 运行静态检查（Android Lint）
.\gradlew.bat lintDebug

# 运行已连接设备/模拟器的 Instrumentation 测试（含 OpenCV 真实图片测试）
.\gradlew.bat connectedDebugAndroidTest
```

## 3. 代码与工程规范

### 3.1 Kotlin 与 Compose 规范
- 遵循官方 Kotlin Coding Conventions；禁止硬编码魔法数字，算法参数集中定义在配置常量对象中并附注释说明。
- Compose UI 组件保持无状态（Stateless），状态提升至 `ViewModel` 并通过 `StateFlow<UiState>` 暴露，一次性事件使用 `SharedFlow` 或状态消费模式。
- 所有用户可见文案统一提取至 `strings.xml`（默认支持中文与英文基础资源）。

### 3.2 模块依赖规范
- `:core:model` 严禁依赖 Android SDK。
- `:feature:*` 模块之间严禁直接依赖，页面跳转统一由 `:app` 的导航图编排。
- 所有依赖版本集中管理于 `gradle/libs.versions.toml`。

### 3.3 图像处理与内存规范
- 所有 OpenCV `Mat` 对象必须在作用域结束时释放（`release()`），推荐使用统一的 `MatScope` / `use` 扩展函数。
- 任何需要读取原图的场景，均需通过 `BitmapLoader` 控制采样尺寸并处理 EXIF 方向。
- 严禁覆盖 `originals/{pageId}.jpg`；编辑操作仅更新数据库参数并重新生成 `processed/` 与 `thumbnails/` 文件。

### 3.4 AI 协作与文档维护规范
- 严禁创建空壳页面、假数据占位或未接入真实逻辑的按钮。
- 每完成一个阶段或重要功能后，必须同步更新：
  - `TASKS.md`（更新真实完成状态与遗留项）
  - `CHANGELOG.md`（记录实际变更内容）
  - `TESTING.md`（记录实际执行的构建、测试结果、真实图片验证结论与已知问题）
  - 如涉及架构或需求调整，同步更新 `ARCHITECTURE.md` / `PRODUCT.md`。
- 禁止在文档中将“计划内容”写成“已完成内容”。
