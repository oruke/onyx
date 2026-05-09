# Onyx 架构改进清单

## 目标

Onyx 的长期目标是桌面端专业文件管理器，不是示例应用。后续要支撑本地文件、压缩包、SMB、WebDAV、S3、预览、缩略图、后台任务和多面板多标签，架构必须先把 UI、组件、业务服务、协议实现的边界固定下来。

本清单按优先级推进，要求每一阶段都能编译、能回退、能通过基础测试。

## 当前状态

P0-P3 已完成，P4 已完成首轮 Gradle 模块拆分与边界测试。P5 已补入 SMB、WebDAV、S3 的基础 provider 模块，`smb://`、`webdav://` / `webdavs://`、`s3://` 已进入统一 `VfsProviderRegistry`。`smb://host` 地址规范化、远程路径面包屑/父子关系判断、SMB/WebDAV 认证失败后的凭据弹窗、远程连接测试服务、远程凭据保存策略、连接管理 UI、SMB 跨 provider 文件传输和 WebDAV 基础写命令已经接入。UI 直接文件系统依赖、Tab 状态模型、任务 UseCase、业务层资源依赖等首轮问题已经收敛。当前主要风险不再是单点边界泄漏，而是真实系统钥匙串/密钥环适配、跨 provider 命令、任务持久化和运行时验证矩阵。

## 当前主要风险

- 项目已拆出 `:core`、`:vfs-api`、`:vfs-local`、`:vfs-archive`、`:vfs-smb`、`:vfs-webdav`、`:vfs-s3`、`:app`、`:composeApp`，但组件接口和部分平台服务仍留在 `composeApp`，后续还需要继续缩窄 UI 模块职责。
- `RootComponent` 和 `PaneComponent` 已有 `dispatch` 入口，常规命令已迁移为 intent 扩展函数；后续功能如果继续直接挂接口，会再次膨胀。
- `VfsProviderRegistry` 已能路由本地、压缩包、SMB、WebDAV、S3 provider；远程连接资料已进入设置持久化和侧边栏入口，凭据策略支持不保存/会话保存/系统钥匙串占位，但仍缺真实系统钥匙串/密钥环实现和真实服务运行时验证。
- `FileCommandService` 已增加按 location 路由的 `ProviderBackedFileCommandService`，本地与 SMB 基础命令可分发，WebDAV 已接入新建文件、新建目录、删除和重命名；S3 写入命令会返回明确的协议不支持错误；目录级跨 provider 复制/移动、WebDAV 复制/移动、S3 真实写入命令和远程到本地导出语义仍不完整。
- 错误模型仍偏粗：`OnyxError` 只有少量类型，业务进度和异常详情仍大量使用 `MessageKey.MSG_STRING_LITERAL` 承载原始字符串。
- 后台任务已从纯内存态推进到可持久化历史：暂停、恢复、取消、失败重试、失败明细和任务历史保存/恢复已具备基础能力；仍缺真正可续跑的持久化执行队列、限速和并发限制。
- 桌面窗口、拖拽、外部打开、回收站、终端等平台行为需要实际运行时验证；编译和单元测试不能证明窗口装饰、尺寸记忆、拖拽命中区域可用。
- 大文件仍集中在 UI 和组件入口，例如 `DetailsView`、`DefaultPaneComponent`、`DefaultRootComponent`、`Dialogs`，后续维护成本会继续上升。

## 目标分层

```text
core
  VFile、OnyxError、任务模型、设置模型
  不依赖 Compose、Jewel、Decompose、java.nio

vfs-api
  FileRepository、FileCommandService、FileContentService、VfsPathService
  PreviewService、ThumbnailService、FileTypeService

vfs-local / vfs-archive / vfs-smb / vfs-webdav / vfs-s3
  本地文件、压缩包和远程协议实现

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

- [x] `LocalVfsProvider` 和 `ArchiveVfsProvider` 通过统一 provider registry 路由。
- [x] `VFile.location` 不再被 UI 当成本地路径解析。
- [x] 增加 provider capability：`WATCH`、`TRASH`、`EXTERNAL_OPEN`、`READ_PREVIEW`、`THUMBNAIL`。
- [x] 为 SMB、WebDAV、S3 预留协议级错误模型和认证上下文。

## P2：任务系统解耦

- [x] `TaskOrchestrator` 只保留任务状态和 Job 管理。
- [x] 复制、移动、删除、解压、批量重命名改为 UseCase，输出 `Flow<TaskProgress>`。
- [x] 冲突检测失败不能静默吞掉。
- [x] 目录复制进度通过递归统计或 provider 回调提供真实进度。

## P3：国际化与错误模型清理

- [x] core/app 层用 `MessageKey` 或 `DomainMessage`，不直接依赖 `StringResource`。
- [x] UI 层统一映射 `MessageKey -> Res.string.xxx`。
- [x] `PaneEntriesState.Failure(reason: String?)` 改为结构化错误。
- [x] 清理业务层裸字符串错误。

## 当前结构扫描（2026-05-09）

- Gradle 结构：`settings.gradle.kts` 已包含 `:core`、`:vfs-api`、`:vfs-local`、`:vfs-archive`、`:vfs-smb`、`:vfs-webdav`、`:vfs-s3`、`:app`、`:composeApp`，核心模型、VFS API、本地 provider、压缩包 provider、远程 provider、任务 UseCase 已具备构建级边界。
- Source set 结构：`composeApp` 仍承载 UI、DI、组件接口、组件默认实现和部分平台服务；`RootComponent` 仍暴露 `ImageBitmap` / `IntSize`，因此组件接口暂未整体迁入 `:app`。
- 包密度：`jvmMain/ui` 文件数量最多，`jvmMain/app/component` 和 `jvmMain/app/filesystem` 次之；核心模型数量较少，说明业务规则仍有不少留在 JVM app/UI 层。
- 大文件热点：`DetailsView`、`DefaultPaneComponent`、`DefaultRootComponent`、`Dialogs`、`PaneSurface`、`BatchRenameDialog`、`ArchiveService`、`TaskCenter` 都超过 500 行，适合后续按子组件、控制器或状态 reducer 拆分。
- 测试覆盖：已有 UI、core、app、vfs-api、vfs-local、vfs-archive 边界测试，以及 `VfsProviderRegistryTest`、`JvmLocalFileProviderTest`、任务详情 helper 测试、`TaskOrchestratorTest`、`ClipboardManagerTest`、`ImageViewerControllerTest`；还缺少完整文件任务 UseCase 流程测试、跨 provider 假实现测试和 UI 状态 reducer 测试。
- 代码债信号：未发现 TODO/FIXME，但存在局部静默 `catch`，例如文件监听异常被忽略；这类问题应该进入错误模型或日志策略。

## 应用可用度扫描

### P1：影响专业文件管理器可用性的缺口

- [ ] SMB、WebDAV、S3 仍未达到完整可用：基础 provider 已接入 registry，SMB 支持同认证上下文内基础命令和本地/SMB 文件级跨 provider 传输，WebDAV 支持列表、新建文件、新建目录、删除、重命名和内容流读写，本地/SMB/WebDAV 之间的文件级跨 provider copy/move 已复用统一传输通道，S3 支持列表和对象内容读取，可作为跨 provider 文件传输源端但仍不支持写入；SMB/WebDAV 已有认证失败凭据弹窗，远程凭据已区分不保存/会话保存/系统钥匙串，Linux 通过 `secret-tool`、macOS 通过 `security` 存取系统钥匙串，Windows 仍明确不支持；SMB/WebDAV/S3 已有统一连接测试服务和连接管理 UI，但仍缺真实服务验收和 Windows Credential Manager 适配。
- [ ] 搜索能力缺失：当前已有递归名称/扩展名搜索、大小过滤、修改时间过滤、文件/目录类型过滤、本地 `content:文本` 内容过滤、搜索结果面板、取消和扫描进度；远程内容搜索会给出不支持提示，仍缺索引策略、远程内容搜索和更细的协议能力过滤。
- [ ] 命令体系不完整：基础文件操作已有 `OnyxCommandRegistry`、可替换快捷键映射、命令状态快照、事件匹配、命令面板和提示生成入口；命令面板已支持按标签、命令名和快捷键检索；设置窗口已支持用户录制、恢复默认和禁用命令快捷键，并持久化到 `OnyxSettings`；但仍缺完整菜单状态同步和跨 Root/Pane 的统一命令调度。
- [ ] 跨 provider 文件命令不足：本地、SMB 和 WebDAV 已能通过 `ProviderBackedFileCommandService` 分发同 provider 基础命令，并新增统一内容传输 API 支持本地/SMB/WebDAV 双向文件级跨 provider copy/move 和 S3 对象导出；S3 写入能力差异已有明确错误；目录递归、压缩包内部写入、WebDAV 同 provider copy/move、S3 写入和远程导出临时文件语义仍不完整。
- [ ] 任务系统缺少持久化队列：任务中心可显示、暂停、取消、失败明细和失败任务重试，任务历史会保存到本地 JSON 并在启动时恢复，未完成任务会作为已取消历史项恢复；仍缺真正可续跑的持久化执行队列、历史归档、限速和并发限制。
- [ ] 错误可见性仍需加强：文件监听降级、批量重命名正则错误、远程连接测试、面板加载、搜索、常见文件任务、平台打开方式失败和文本预览失败已接入可见或结构化错误消息，可区分认证、权限、网络、未找到、已存在和协议不支持；缩略图异常已进入日志，仍需继续收敛图片元数据、部分缩略图占位和更多平台服务失败的 UI 展示。
- [ ] 平台能力不均衡：终端打开已按 Windows、macOS、Linux 生成平台候选命令并保留 `TERMINAL` 环境变量优先；`OpenWithService` 已增加 JVM 平台分发，Linux 保留 `.desktop` 应用列表，macOS 可枚举系统应用并使用系统应用选择，Windows 使用系统 Open With 对话框；但 Windows 应用列表枚举、回收站 Desktop API 和跨平台行为仍需要真实系统验证。
- [ ] 运行时窗口行为需要验证矩阵：设置窗口、图片查看器窗口、批量重命名窗口已经有尺寸字段，但窗口可调整、最大化、尺寸缓存和窗口管理器装饰必须实际运行验证。

### P2：影响可维护性和扩展性的缺口

- [x] Gradle 模块化：拆出 `:core`、`:vfs-api`、`:vfs-local`、`:vfs-archive`、`:app`、`:composeApp`，用模块依赖强制边界。
- [x] 接口瘦身：`RootComponent`、`PaneComponent` 保留 `state`、`dispatch`、子组件入口，把常规命令迁移到 intent 扩展函数。
- [ ] VFS 命令 provider 化：`copy`、`move`、`delete`、`rename`、`create` 已开始通过可路由命令服务收敛，本地与 SMB 已接入，同步新增文件级内容读写服务以承载本地/SMB/WebDAV 跨 provider 传输和 S3 源端导出；WebDAV 已接入 create/delete/rename/read content/write content，copy/move 仍明确返回不支持并由跨 provider 内容传输兜底，S3 缺少写入命令时会返回明确不支持错误；`watch`、`openExternal`、`preview`、`thumbnail`、目录级跨 provider 传输以及 WebDAV/S3 完整写入命令仍需继续 provider 化。
- [x] 文件类型能力下沉：新增统一 `FileTypeService`，图片、压缩包、可预览文本识别由组件层服务提供，画廊、预览、检查器和打开行为不再维护各自的扩展名判断 helper。
- [ ] 大目录性能：当前目录列表会一次性读取并排序，超大目录需要分页、增量加载或后台索引策略。
- [ ] 预览能力扩展：当前偏文本、图片、缩略图，检查器已支持本地文件和压缩包条目的 SHA-256 摘要；仍缺少 PDF、音视频元数据、编码选择和大文件取消策略。
- [ ] 压缩包能力扩展：当前重点是浏览和解压，缺少压缩包内写入、删除、重命名、追加、加密格式能力差异提示。
- [ ] 外部拖拽抽象：当前已有 `ExternalDragHelper` 平台化，但压缩包条目导出、本地临时文件生命周期、远程文件导出都应沉淀为 provider export API。

### P3：体验和质量补强

- [x] 快捷键提示国际化：菜单里的 `Enter`、`Ctrl+C`、`F5` 等提示已改为由 `OnyxCommand` 快捷键元数据和三语资源生成，筛选按钮与创建文件夹弹窗也复用同一快捷键提示。
- [x] 文件监听错误处理：`FileWatcher` 自动刷新异常会写入 `OnyxLogger.warn`，当前面板展示自动刷新已降级提示，不再静默吞掉监听失败。
- [ ] Undo/Redo：删除、移动、重命名、批量重命名等破坏性操作缺少撤销模型。
- [ ] 收藏与历史增强：已有收藏和最近位置字段，侧栏已支持按名称/路径筛选收藏、连接和最近位置；设置窗口已提供不存在本地收藏/最近位置的清理入口；但还缺少书签管理和固定位置。
- [ ] 可访问性和键盘流：已有基础快捷键，但焦点顺序、屏幕阅读标签、无鼠标完整操作还未系统验证。

## 后续阶段建议

以下阶段作为 P0-P3 完成后的新增改进队列，优先级从边界硬化到运行时验收逐步推进。

## P4：模块化与边界硬化

- [x] 拆分 Gradle 模块，先迁出 `core` 与 `vfs-api`，再迁出 `vfs-local`、`vfs-archive` 和 `app`。
- [x] 新增架构测试，禁止 `core` 依赖 Compose/Jewel/Decompose，禁止 `vfs-api` 依赖 JVM 平台 API，禁止 UI 依赖 provider 具体实现。
- [x] 将 `app/component/usecase` 移到更明确的 `app/usecase` 或独立模块，组件只负责状态协调和 intent 派发。
- [x] 将 Root 设置归一化与最近位置更新拆成 `RootSettingsReducer`，并补 reducer 测试。
- [x] 将 `RootComponent` / `PaneComponent` 常规命令从接口契约中移除，改为 `RootIntent` / `PaneIntent` 扩展函数转发，并补架构测试。
- [ ] 将 `DefaultRootComponent`、`DefaultPaneComponent` 继续拆成 controller、delegate、reducer，并以测试覆盖更多关键 reducer。

## P5：远程协议与认证

- [x] 实现 SMB provider：`smb://` 已接入 registry，覆盖 list、同认证上下文内基础命令、认证失败、权限失败、网络失败映射和基础单元测试。
- [x] 实现 WebDAV provider：`webdav://` / `webdavs://` 已接入 registry，复用 Ktor Client，支持 Basic/Bearer 认证、TLS/网络错误映射、路径编码和 PROPFIND XML 解析测试。
- [x] 实现 S3 provider：`s3://bucket/prefix` 已接入 registry，支持 region、访问密钥、session token、ListObjectsV2 分页、bucket/prefix 解析、对象列表解析和基础错误映射。
- [x] 修复远程 URI 地址栏导航：`smb://host` 自动规范化为 `smb://host/`，远程路径父级、标题、面包屑、同级判断不再误走本地 `Path`。
- [x] 补 SMB/WebDAV 认证失败恢复入口：provider 返回 `AuthenticationRequired` / `AuthenticationRejected` 后，Pane 上报 Root，Root 弹出凭据输入框并写入会话级 `RemoteAuthStore` 后刷新当前面板。
- [x] 补远程协议连接测试入口：新增 `VfsConnectionTestService`、`VfsConnectionTestRequest`、`VfsConnectionTestResult` 和 provider 级 `VfsConnectionTester`，SMB/WebDAV/S3 不再只能用 `list` 失败表示连接测试结果。
- [x] 补远程协议凭据保存策略：已区分不保存、会话内保存、系统钥匙串/密钥环保存；不保存使用一次性凭据并在下一次请求后消费，会话保存只写入内存，系统钥匙串暂不可用时返回明确错误，禁止默认明文持久化密码和密钥。
- [x] 补 WebDAV/S3 写入命令或明确能力差异提示：`ProviderBackedFileCommandService` 在 provider 存在但缺少命令服务时返回 `UnsupportedOperation`，避免把 WebDAV/S3 写入能力缺失误报为 provider 不存在。
- [x] 补 WebDAV 基础写命令：WebDAV provider 已实现 `PUT` 空文件创建、`MKCOL` 创建目录、`DELETE` 删除、`MOVE` 重命名、`GET` 内容读取和 `PUT` 内容写入，并通过可路由命令服务与内容服务接入；copy/move 到其他目录仍作为后续能力。
- [x] 补 S3 对象内容读取：S3 provider 已实现签名 `GET` 对象请求并接入统一内容服务，可作为本地/SMB/WebDAV 文件级跨 provider 传输源端；S3 目标写入仍返回明确不支持。
- [x] 新增连接管理 UI：设置窗口新增连接分类，支持新建、编辑、测试、删除、打开远程连接和选择凭据保存策略；连接资料进入 `OnyxSettings.remoteConnections`，侧边栏显示已保存连接，密码和密钥不写入设置文件。

## P6：专业文件管理可用性

- [ ] 建立统一命令系统和快捷键配置，并接入菜单、上下文菜单、标题栏按钮和命令面板；当前已完成基础命令注册表、可替换快捷键映射、事件匹配、提示生成、命令状态快照、面板内命令面板和设置内快捷键持久化配置，仍缺全局菜单状态同步。
- [ ] 实现递归搜索和搜索结果面板，已支持名称/扩展名、大小、修改时间、文件/目录类型、本地内容过滤、取消、扫描进度和结果打开；仍需补索引策略、远程内容搜索和协议能力过滤。
- [ ] 完善任务队列：已补失败明细、失败任务重试、速度估算、剩余时间和任务历史跨启动恢复；仍缺真正可续跑的持久化执行队列、历史归档、限速和并发限制。
- [ ] 增加 Undo/Redo 操作日志，至少覆盖移动、重命名、批量重命名和删除到回收站。
- [ ] 增强预览和检查器：本地文件和压缩包条目的 SHA-256 摘要已接入；仍需补 PDF、音视频、编码选择、权限、EXIF 和更完整的大文件取消策略。

## P7：运行时 UX 验证矩阵

- [ ] 在 Linux/KDE、Linux/GNOME、Windows、macOS 上验证窗口尺寸记忆、可调整边框、最大化、设置窗口和图片查看器窗口行为。
- [ ] 验证面板分割器拖拽、跨面板标签拖拽、外部文件拖出、文件拖入和压缩包条目拖出。
- [ ] 验证高 DPI、不同 `uiScale`、中英日三语言、长路径、长文件名、大目录下的布局稳定性。
- [x] 将关键运行时验证沉淀为脚本化检查或手工验收清单：已新增 `docs/runtime-ux-validation.md`，覆盖桌面窗口、面板拖拽、远程连接、外部平台能力、DPI/i18n/长文本和键盘流验收项。

## 验证要求

- 每个阶段至少运行 `./gradlew :composeApp:jvmTest`。
- P0 后新增边界测试应能防止 UI 重新引入直接 I/O。
- 涉及 VFS 或任务行为的改动必须补单元测试。
- 涉及窗口、拖拽、外部打开、系统回收站、终端启动等桌面集成行为时，必须补运行时验证记录。
