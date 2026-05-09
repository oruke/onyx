# Onyx 架构改进清单

## 目标

Onyx 的长期目标是桌面端专业文件管理器，不是示例应用。后续要支撑本地文件、压缩包、SMB、WebDAV、S3、预览、缩略图、后台任务和多面板多标签，架构必须先把 UI、组件、业务服务、协议实现的边界固定下来。

本清单按优先级推进，要求每一阶段都能编译、能回退、能通过基础测试。

## 当前主要风险

- UI 层仍有直接 `java.io` / `java.nio` / `ProcessBuilder` 调用，导致文件系统细节泄漏到 Composable。
- `RootComponent` 和 `PaneComponent` 接口过宽，新增功能容易继续堆方法。
- VFS API 只覆盖 `list` 和基础命令，预览、缩略图、路径运算、同卷判断、监听等能力散落在 UI 和组件实现中。
- core/app 层存在 Compose Resources 依赖，业务消息和 UI 资源尚未完全解耦。

## 目标分层

```text
core
  VFile、OnyxError、任务模型、设置模型
  不依赖 Compose、Jewel、Decompose、java.nio

vfs-api
  FileRepository、FileCommandService、FileContentService、VfsPathService
  PreviewService、ThumbnailService、FileTypeService

vfs-local / vfs-archive
  本地文件和压缩包协议实现

app/component
  RootComponent、PaneComponent、UseCase、TaskOrchestrator、Reducer
  允许使用 Decompose，但不直接写 UI

ui
  Compose Multiplatform + Jewel
  只消费状态、发送事件，不直接执行 I/O
```

## P0：封住边界

- [x] 新增本清单文档。
- [x] 新增统一服务接口：
  - `VfsPathService`
  - `TerminalLauncherService`
  - `PreviewService`
  - `ThumbnailService`
  - `ImageMetadataService`
- [x] 将终端打开从 `PaneSurface` 移到 `RootComponent -> TerminalLauncherService`。
- [x] 将文本预览从 `PreviewPane` 移到 `PreviewService`。
- [x] 将图片分辨率读取从 `ImageViewer` 移到 `ImageMetadataService`。
- [x] 将缩略图读取从 `ui.theme` 移到应用服务层。
- [x] 将 `ExternalDragHelper` 迁出 `ui` 包。
- [x] 新增边界测试，禁止 `ui/**` 直接依赖 `java.io`、`java.nio`、`ProcessBuilder`、`ArchiveService`。
- [x] 编译和 JVM 测试通过。

## P1：收敛状态与事件模型

- [x] 拆分 `PaneState` 与 `PaneTabState` 重复字段，提取 `TabState` 与 `PaneChromeState`。
- [x] 引入 `PaneIntent` 与 `PaneComponent.dispatch` 入口。
- [x] 引入 `RootIntent` 与 `RootComponent.dispatch` 入口。
- [x] UI 命令调用迁移为发送 `RootIntent` / `PaneIntent`。
- [x] 把选择、排序、过滤的派生状态先拆成纯 helper。
- [x] 把树状展开拆成纯 reducer/helper。
- [x] 把内联编辑拆成纯 reducer/helper。
- [x] 将导航与加载状态变更拆成纯 helper。
- [x] 将选择操作拆成独立 helper/delegate。
- [x] 将 Tab 操作拆成独立 helper/delegate。
- [x] 继续控制 `DefaultPaneComponent` 体积，保留组件调度职责。

## P1：Tab 改为 Decompose ChildStack

- [x] 每个 Tab 建立 `TabComponent`。
- [x] `PaneComponent` 只管理 `ChildStack<TabConfig, TabComponent>`。
- [x] 跨面板标签拖拽传递 `TabConfig` / `TabSessionSnapshot`。
- [x] Session 持久化改为 `TabSnapshot` 映射。

## P2：VFS provider 化

- [ ] `LocalVfsProvider` 和 `ArchiveVfsProvider` 通过统一 provider registry 路由。
- [ ] `VFile.location` 不再被 UI 当成本地路径解析。
- [ ] 增加 provider capability：`WATCH`、`TRASH`、`EXTERNAL_OPEN`、`READ_PREVIEW`、`THUMBNAIL`。
- [ ] 为 SMB、WebDAV、S3 预留协议级错误模型和认证上下文。

## P2：任务系统解耦

- [ ] `TaskOrchestrator` 只保留任务状态和 Job 管理。
- [ ] 复制、移动、删除、解压、批量重命名改为 UseCase，输出 `Flow<TaskProgress>`。
- [ ] 冲突检测失败不能静默吞掉。
- [ ] 目录复制进度通过递归统计或 provider 回调提供真实进度。

## P3：国际化与错误模型清理

- [ ] core/app 层用 `MessageKey` 或 `DomainMessage`，不直接依赖 `StringResource`。
- [ ] UI 层统一映射 `MessageKey -> Res.string.xxx`。
- [ ] `PaneEntriesState.Failure(reason: String?)` 改为结构化错误。
- [ ] 清理业务层裸字符串错误。

## 验证要求

- 每个阶段至少运行 `./gradlew :composeApp:jvmTest`。
- P0 后新增边界测试应能防止 UI 重新引入直接 I/O。
- 涉及 VFS 或任务行为的改动必须补单元测试。
