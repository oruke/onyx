# AGENTS.md - Onyx 项目开发规范

你正在参与开发 `Onyx`，一个面向桌面端的专业级文件管理器。目标不是做一个通用示例应用，而是构建一个高密度、高性能、强定制、支持多协议存储的类
Directory Opus 桌面工具。

## 0. 核心要求

- 所有计划、注释、文档、任务拆解、代码解释与最终回复必须使用中文。
- 沟通风格要求直接、专业、简洁，避免空泛建议。
- 默认以“可落地实现”为目标，不输出只有概念没有执行路径的方案。

## 1. 技术栈

- 语言：Kotlin 2.2+
- 目标平台：Desktop JVM
- UI：Compose Multiplatform Desktop
- UI 组件：JetBrains Jewel Standalone
- 禁止：Material 3，除非 Jewel 明确缺失且必须自定义封装
- 导航与状态：Decompose
- 状态组织：基于组件的 MVI
- 依赖注入：Koin
- 本地文件：`java.nio`
- SMB：`jcifs-ng`
- WebDAV / S3：Ktor 3.x
- 压缩与解压：7-Zip-JBinding
- 图像加载：Coil 3
- 资源与国际化：官方 `composeResources`

## 2. 架构边界

- UI 层严禁直接使用 `java.io.File`、`java.nio.Path`、`SmbFile` 等底层对象。
- 所有文件对象必须收敛到统一抽象：`VFile`。
- 每个独立面板必须封装为独立组件，拥有自己的：
    - 地址栏
    - 文件列表
    - 状态栏
    - 浏览历史
    - 标签栈
- 多标签页必须由 Decompose `ChildStack` 管理，后台标签状态不可因切换而丢失。
- 跨面板标签拖拽必须通过配置对象流转实现，不能只做表面动画。

## 3. UI / UX 准则

- 整体风格必须保持高密度、偏 IntelliJ IDEA 风格，拒绝大留白和大圆角。
- 窗口顶部使用沉浸式标题栏，承载导航控制、布局切换、设置入口。
- 支持三种布局：
    - `SINGLE`
    - `DUAL_VERTICAL`
    - `DUAL_HORIZONTAL`
- 分割器必须支持拖拽，并根据方向展示正确的调整光标。
- 点击非活跃面板内元素时，必须同时完成“激活面板 + 执行业务动作”，不能要求二次点击。
- 地址栏必须采用混合模式：
    - 默认面包屑
    - 点击空白切换文本编辑
    - 长路径优先展示后部目录
- 文件视图至少支持：
    - `DETAILS`
    - `GALLERY`
- 表头与内容列必须严格对齐，文件名长文本必须省略显示。

## 4. 动态外观系统

- 不允许把颜色、字号、行高、缩放等视觉参数散落在各个 Composable 中。
- 必须建立 `OnyxAppearance` / `OnyxColors` 等 CompositionLocal 外观系统。
- 所有核心组件必须响应以下运行时变化：
    - `uiScale`
    - 列表字号
    - 行高
    - 选中背景色
    - 斑马纹
    - 文件列表背景 / 文件名颜色

## 5. 国际化

- UI 代码中严禁硬编码任何用户可见字符串。
- 所有字符串必须通过 `stringResource(Res.string.xxx)` 获取。
- 必须支持：
    - 英文：`values`
    - 简体中文：`values-zh`
    - 日语：`values-ja`
- 资源 key 统一使用带语义前缀的 `snake_case`，例如：
    - `action_copy`
    - `label_file_size`
    - `nav_back`

## 6. 线程与错误处理

- 所有 I/O 操作必须放在 `withContext(Dispatchers.IO)` 中执行。
- UI 状态更新必须在主线程完成。
- 严禁吞掉异常。
- 数据层返回值必须使用 `Result<T>` 或自定义 `sealed class` 错误模型。
- 文件列表与画廊渲染必须使用虚拟化容器，例如 `LazyColumn` / `LazyVerticalGrid`。

## 7. 禁止事项

- 不要继续扩展模板里的 Material 3 代码。
- 不要引入 `androidx.navigation`。
- 不要在 UI 中直接操作真实文件系统对象。
- 不要使用 `File.listFiles()` 进行目录遍历。
- 不要把协议实现细节泄漏到 UI 状态层。
- 不要在未接入 I18N 的情况下临时硬编码文本占位。

## 8. 交付标准

一项功能只有同时满足以下条件才算完成：

- 有对应状态模型与事件模型。
- I/O 线程隔离正确。
- 错误状态可见且可处理。
- 字符串已接入国际化。
- 能与当前布局、焦点与外观系统兼容。
- 至少有基础验证：编译通过，必要时补单元测试或组件测试。
