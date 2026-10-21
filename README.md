# Onyx

Onyx 是一个面向桌面端的高密度文件管理器，目标是逐步接近 Directory Opus 这类专业工具，而不是示例级文件浏览器。当前版本为 `1.4.6`，使用 Kotlin Multiplatform、Compose Desktop 与 JetBrains Jewel 构建。

## 当前状态

- 平台：Windows、macOS、Linux，当前开发和验证重点在 Windows。
- UI：沉浸式标题栏、单面板、双竖向面板、双横向面板、多标签页、详情列表、画廊视图、预览与动态外观系统。
- 本地文件：基于 `java.nio`，支持列表、复制、移动、删除、重命名、新建、拖放、收藏与最近位置。
- 系统位置：侧栏“此电脑／电脑”展示标准目录、设备和容量，接入标签与历史，支持磁盘概览和紧凑列表；原生操作按平台能力提供，具体范围与验收限制见[桌面设备架构](docs/platform-storage.md)。
- 右键菜单：已接入统一右键菜单模型，并在 Windows 侧实现 Shell COM 菜单读取、级联菜单和执行。
- 压缩包：支持浏览和解压常见格式；`.tar.zst` / `.tzst` 使用系统 `tar` fallback；ZIP 系列支持内部新建目录、删除、重命名、追加文件和保存失败回滚。
- 远程协议：SMB、WebDAV、S3 已拆分为独立 VFS provider，并接入统一 `VFile`、命令服务、内容读写服务和连接测试模型。

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 语言 | Kotlin 2.3.x |
| UI | Compose Multiplatform Desktop 1.12.1 |
| 组件 | JetBrains Jewel Standalone |
| 导航与状态 | Decompose + 组件化 MVI |
| 依赖注入 | Koin |
| 本地文件 | `java.nio` |
| 远程协议 | SMB `jcifs-ng`、WebDAV / S3 `Ktor 3.x` |
| 压缩包 | 7-Zip-JBinding + 系统 `tar` fallback |
| 图像 | Coil 3 |
| 质量检查 | detekt 基础规则 |

## 模块结构

```text
onyx/
├─ core/          # 跨模块基础模型
├─ shared/        # 共享业务用例与任务模型
├─ composeApp/    # Compose Desktop UI、组件实现、平台服务
├─ vfs-api/       # VFS 统一抽象、命令服务、内容服务、右键菜单模型
├─ vfs-local/     # 本地文件系统 provider
├─ vfs-archive/   # 压缩包 provider 与压缩包服务
├─ vfs-smb/       # SMB provider、认证与 jcifs-ng client
├─ vfs-webdav/    # WebDAV provider、认证、Ktor client、PROPFIND 解析
└─ vfs-s3/        # S3 provider、认证、签名、Ktor client、XML 解析
```

包名约定：`shared` 使用 `com.oruke.onyx.shared.*`，`vfs-*` 使用 `com.oruke.onyx.vfs.*`，`composeApp` 中桌面应用层继续使用 `com.oruke.onyx.app.*`。

## 远程协议状态

- SMB：支持认证、列表、读取、写入、复制、移动、删除、重命名、新建文件和新建目录。
- WebDAV：支持 Basic / Bearer 认证、PROPFIND 列表、读取、写入、复制、移动、删除、重命名、新建文件和新建目录。
- S3：支持 AWS Signature V4、列表、分页列表、读取、写入、复制、移动、删除、重命名、新建文件和目录占位对象。
- 跨 provider 传输：通过统一内容流完成复制/移动，支持进度、协程取消、冲突策略和错误聚合。

## 构建与验证

```bash
./gradlew :composeApp:run
./gradlew compileKotlinJvm
./gradlew allTests
./gradlew detekt
```

本机推荐使用 JDK 21，当前开发环境使用 JetBrains Runtime 21。

### 热重载与 MCP

应用模块已接入 Compose Hot Reload `1.2.0`。编译使用 JDK 21；热重载任务默认使用 JBR 25。没有可用运行时时，已有 Foojay resolver 会自动下载所需运行时。

在项目根目录启动应用并启用自动热重载：

```powershell
.\gradlew.bat :composeApp:hotRunJvm --auto --console=plain
```

需要由 MCP 控制重载时，启动应用时省略 `--auto`。随后由 MCP 客户端启动 stdio 服务；支持 `mcpServers` 配置的 Windows 客户端可使用：

```json
{
  "mcpServers": {
    "compose-hot-reload": {
      "command": "cmd",
      "args": [
        "/d",
        "/c",
        "gradlew.bat",
        "--no-daemon",
        "--quiet",
        "--console=plain",
        ":composeApp:hotMcpServerJvm"
      ]
    }
  }
}
```

客户端工作目录必须为项目根目录。macOS/Linux 使用 `./gradlew` 启动上述任务；MCP 配置的 `command` 改为 `./gradlew`，并移除 `args` 中的 `/d`、`/c` 和 `gradlew.bat`。

连接后先调用 `status` 和 `list_windows`，再通过语义树执行交互。手动模式使用 `reload`；自动模式使用 `await_reload`。重载后检查 `get_ui_error`、`get_logs` 和截图。MCP 服务会等待应用启动，并在应用重启后自动重连。

Hot Reload `1.2.0` 的截图通过屏幕捕获实现，截图前需让目标窗口处于前台，避免被其他窗口遮挡。

插件和 MCP 的能力、运行限制见 [Compose Hot Reload 官方文档](https://github.com/JetBrains/compose-hot-reload/tree/v1.2.0#MCP-server-for-AI-agents)。

## 开发规范

开发约束见 [AGENTS.md](AGENTS.md)。关键原则：

- UI 层不直接操作 `Path`、`File`、`SmbFile` 等底层对象，文件对象统一通过 `VFile` 流转。
- 新功能优先扩展现有统一 API，不能绕过组件层、VFS 抽象或任务模型。
- 用户可见字符串必须接入资源文件，支持英文、简体中文和日语。
- I/O 必须放在 `Dispatchers.IO`，错误不能被静默吞掉。
- 代码变更需要保持模块职责清晰，公共 API 和业务函数补中文 KDoc。

## 许可

Onyx 自有代码采用 [MIT License](LICENSE) 开源。

第三方依赖、组件和资源仍遵循各自的许可证；本项目的 MIT License 不替代这些第三方许可条款。
