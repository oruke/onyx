# Onyx

<p align="center">
  <strong>专业级桌面文件管理器</strong><br>
  面向高级用户的高密度、多面板、多协议文件管理工具
</p>

<p align="center">
  <img src="https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-blue" alt="Platform">
  <img src="https://img.shields.io/badge/kotlin-2.2+-purple" alt="Kotlin">
  <img src="https://img.shields.io/badge/UI-Compose%20Desktop%20%2B%20Jewel-green" alt="UI">
  <img src="https://img.shields.io/badge/version-1.1.1-orange" alt="Version">
</p>

---

## 简介

Onyx 是一个面向桌面端的专业级文件管理器，灵感来源于 Directory Opus，使用 Kotlin + Compose Multiplatform Desktop 构建。采用 JetBrains Jewel 组件库打造高密度 IDE 风格界面，支持多面板、多标签页、多协议存储。

## 功能特性

> ✅ 已实现 · 🚧 开发中 · 📋 计划中

### 核心功能
- ✅ **多面板布局** — 单栏 / 竖向双栏 / 横向双栏，可拖拽分割
- ✅ **多标签页** — 每个面板独立标签栈，后台标签状态保持
- ✅ **双视图模式** — 详情列表 / 画廊缩略图，实时切换
- ✅ **预览面板** — 右侧即时预览选中文件
- ✅ **侧栏导航** — 快速访问 / 收藏 / 目录树
- ✅ **地址栏** — 面包屑导航 + 文本编辑混合模式
- ✅ **图片查看器** — 独立窗口浏览图片
- 📋 **跨面板标签拖拽** — 标签在面板间移动

### 文件操作
- ✅ **复制 / 移动 / 删除** — 字节级进度显示、实时速度、预计完成时间
- ✅ **批量重命名** — 支持正则、序号、大小写转换等规则
- ✅ **压缩包管理** — 浏览、解压到此处、解压到独立目录、智能解压、加密压缩包
- ✅ **拖放操作** — 跨面板拖放复制/移动
- ✅ **右键菜单** — 文件操作 + 空白处菜单
- ✅ **目录回退选中** — 返回上级时自动选中并滚动到之前的目录
- ✅ **新建文件/目录** — 支持从右键菜单或快捷键创建

### 定制与体验
- ✅ **动态外观系统** — 运行时调节 UI 缩放、字号、行高、配色
- ✅ **沉浸式标题栏** — 导航控制、布局切换、设置入口
- ✅ **快捷键体系** — 键盘驱动操作 (Ctrl+, 打开设置)
- ✅ **国际化** — 英文 / 简体中文 / 日语
- ✅ **窗口状态记忆** — 记住窗口大小和位置
- ✅ **筛选器** — 快捷键触发的文件名过滤
- 📋 **自定义快捷键** — 用户可配置快捷键映射

### 存储协议
- ✅ **本地文件系统** — java.nio 完整支持
- 📋 **SMB/CIFS** — 局域网共享文件夹 (jcifs-ng)
- 📋 **WebDAV** — 网络存储 (Ktor 3.x)
- 📋 **S3** — 对象存储 (Ktor 3.x)

## 技术栈

| 层级 | 技术 |
|------|------|
| 语言 | Kotlin 2.2+ |
| UI 框架 | Compose Multiplatform Desktop |
| UI 组件 | JetBrains Jewel (IntelliJ 风格) |
| 导航与状态 | Decompose + MVI |
| 依赖注入 | Koin |
| 本地文件 | java.nio |
| 压缩/解压 | 7-Zip-JBinding |
| 图像加载 | Coil 3 |
| 网络协议 | SMB (jcifs-ng) / WebDAV / S3 (Ktor 3.x) — 计划中 |

## 安装

### 从 Release 下载

前往 [Releases](../../releases) 页面下载对应平台安装包：

| 平台 | 格式 |
|------|------|
| Windows | `.msi` |
| macOS | `.dmg` |
| Linux (Debian/Ubuntu) | `.deb` |
| Linux (Fedora/RHEL) | `.rpm` |
| 跨平台 | `.jar` (需要 JDK 21+) |

### 从源码构建

```bash
# 克隆仓库
git clone https://github.com/oruke/onyx.git
cd onyx

# 运行
./gradlew :composeApp:run

# 打包当前平台安装包
./gradlew packageDistributionForCurrentOS

# 打包 UberJar
./gradlew packageUberJarForCurrentOS
```

> 需要 JDK 21+（推荐 JetBrains Runtime）

## 项目结构

```
onyx/
├── composeApp/
│   ├── src/
│   │   ├── commonMain/          # 跨平台共享代码
│   │   │   ├── composeResources/  # 国际化资源 (en/zh/ja)
│   │   │   └── kotlin/
│   │   │       └── com/oruke/onyx/
│   │   │           ├── app/component/  # 组件接口 (RootComponent, PaneComponent)
│   │   │           └── core/model/     # 数据模型 (VFile, Settings, State)
│   │   └── jvmMain/             # JVM 平台实现
│   │       └── kotlin/
│   │           └── com/oruke/onyx/
│   │               ├── app/
│   │               │   ├── component/  # 组件实现 (DefaultRootComponent)
│   │               │   └── filesystem/ # 文件系统服务 (ArchiveService)
│   │               └── ui/            # UI 层 (App, Sidebar, DetailsView...)
│   └── build.gradle.kts
├── .github/workflows/
│   └── release.yml              # CI/CD: 多平台自动打包发布
├── AGENTS.md                    # AI 协作开发规范
└── gradle.properties            # 版本配置
```

## 发布流程

推送版本 tag 即自动触发多平台构建并发布到 GitHub Releases：

```bash
git tag v1.1.1
git push origin v1.1.1
```

CI 会在 Linux / Windows / macOS 三个平台分别构建，生成 deb、rpm、msi、dmg、jar 五种格式，自动附加到 Release。

## 开发规范

详见 [AGENTS.md](AGENTS.md)，核心要求：

- UI 层严禁直接操作底层文件对象，统一通过 `VFile` 抽象
- 所有字符串走国际化，禁止硬编码
- I/O 操作必须在 `Dispatchers.IO`，UI 更新在主线程
- 文件列表必须使用虚拟化容器

## License

本项目为私有项目。
