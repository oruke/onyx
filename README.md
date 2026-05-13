# Onyx

Onyx 是一个面向桌面端的高密度文件管理器，目标是逐步接近 Directory Opus 这类专业工具，而不是示例级文件浏览器。当前版本为 `1.2.1`，使用 Kotlin Multiplatform、Compose Desktop 与 JetBrains Jewel 构建。

## 当前状态

- 平台：Windows、macOS、Linux，当前开发和验证重点在 Windows。
- UI：沉浸式标题栏、单面板、双竖向面板、双横向面板、多标签页、详情列表、画廊视图、预览与动态外观系统。
- 本地文件：基于 `java.nio`，支持列表、复制、移动、删除、重命名、新建、拖放、收藏与最近位置。
- 右键菜单：已接入统一右键菜单模型，并在 Windows 侧实现 Shell COM 菜单读取、级联菜单和执行。
- 压缩包：支持浏览和解压常见格式；`.tar.zst` / `.tzst` 使用系统 `tar` fallback；ZIP 系列支持内部新建目录、删除、重命名、追加文件和保存失败回滚。
- 远程协议：SMB、WebDAV、S3 已拆分为独立 VFS provider，并接入统一 `VFile`、命令服务、内容读写服务和连接测试模型。

## 技术栈

| 层级 | 技术 |
| --- | --- |
| 语言 | Kotlin 2.3.x |
| UI | Compose Multiplatform Desktop |
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
├─ app/           # 应用层用例与任务模型
├─ composeApp/    # Compose Desktop UI、组件实现、平台服务
├─ vfs-api/       # VFS 统一抽象、命令服务、内容服务、右键菜单模型
├─ vfs-local/     # 本地文件系统 provider
├─ vfs-archive/   # 压缩包 provider 与压缩包服务
├─ vfs-smb/       # SMB provider、认证与 jcifs-ng client
├─ vfs-webdav/    # WebDAV provider、认证、Ktor client、PROPFIND 解析
└─ vfs-s3/        # S3 provider、认证、签名、Ktor client、XML 解析
```

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

## 开发规范

开发约束见 [AGENTS.md](AGENTS.md)。关键原则：

- UI 层不直接操作 `Path`、`File`、`SmbFile` 等底层对象，文件对象统一通过 `VFile` 流转。
- 新功能优先扩展现有统一 API，不能绕过组件层、VFS 抽象或任务模型。
- 用户可见字符串必须接入资源文件，支持英文、简体中文和日语。
- I/O 必须放在 `Dispatchers.IO`，错误不能被静默吞掉。
- 代码变更需要保持模块职责清晰，公共 API 和业务函数补中文 KDoc。

## 许可

私有项目。
