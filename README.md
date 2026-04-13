# Onyx

Onyx 是一个面向桌面端的专业级文件管理器，目标是复刻并逐步超越 Directory Opus
的核心体验。项目当前处于从模板工程向正式架构重建的阶段，优先级是先建立稳定、可扩展、可约束 AI
协作的工程基线，再逐步实现本地文件浏览、多面板、多标签、预览、压缩与网络存储支持。

## 项目目标

- 纯桌面端 JVM 应用，使用 Kotlin 2.2+ 与 Compose Multiplatform Desktop。
- 使用 JetBrains Jewel 构建高密度、IDE 风格 UI，禁止继续沿用 Material 3 模板组件。
- 以 Decompose + MVI 管理窗口、布局、双面板、多标签页与后台任务生命周期。
- 通过统一的 `VFile` 抽象屏蔽本地、SMB、WebDAV、S3 等不同存储协议差异。
- 严格执行国际化、线程隔离、异常分发和虚拟化渲染规范。

## 当前状态

仓库目前仍是 Compose 模板工程：

- 仅存在 `composeApp` 单模块骨架。
- `README` 与源码仍保留模板内容。
- 仍在使用 `Material 3`，与项目规范冲突。
- `docs/` 目录为空，尚未建立正式架构文档。

因此本仓库的第一目标不是继续往模板里堆功能，而是按文档定义的方案进行重头实现。

## 文档索引

- [开发规范与 AI 协作约束](/home/oruke/Projects/onyx/AGENTS.md)
- [架构说明](/home/oruke/Projects/onyx/docs/architecture.md)
- [重构实现方案](/home/oruke/Projects/onyx/docs/rebuild-plan.md)
- [TODO 清单](/home/oruke/Projects/onyx/docs/todo.md)
- [ADR-001：桌面单平台与核心栈决策](/home/oruke/Projects/onyx/docs/adrs/ADR-001-desktop-jvm-stack.md)

## 建议执行顺序

1. 完成 Phase 0：清理模板代码，移除 Material 3，补齐 Jewel、Decompose、Koin、资源体系与文档约束。
2. 完成 Phase 1：建立 `VFile`、应用状态树、动态外观系统与 I18N 基建。
3. 完成 Phase 2：交付可用的本地文件浏览 MVP，再向双面板、多标签、预览、压缩和网络协议扩展。

## 运行

当前仍可按模板方式启动桌面程序：

```bash
./gradlew :composeApp:run
```

但在重构完成前，这个入口只代表模板工程可运行，不代表 Onyx 已具备目标功能。
