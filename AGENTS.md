# Repository Guidelines

## 项目概述

基于SpringBoot后端、React前端的由AI驱动的数据搜集和画图的项目

## 项目结构与模块组织
本仓库由 Java 后端和 Next.js 前端组成。根目录 `pom.xml` 聚合了 `data-visualizer-api`、`data-visualizer-app`、`data-visualizer-domain`、`data-visualizer-trigger`、`data-visualizer-infrastructure` 和 `data-visualizer-types`。各 Java 模块源码位于 `src/main/java`，测试主要位于 `data-visualizer-app/src/test/java`，运行配置、MyBatis 和 agent 配置位于 `data-visualizer-app/src/main/resources`。前端位于 `data-visualizer-front`，App Router 文件在 `app/`，静态资源在 `public/`。部署脚本和环境示例集中在 `docs/dev-ops/`。

## 构建、测试与开发命令

使用pwsh执行指令

不需要执行构建、测试命令，由开发人员手动执行

## 代码风格与命名约定
Java 使用 4 空格缩进，并遵循现有的 `top.lbwxxc.ai` 包结构。类名使用 `PascalCase`，方法和字段使用 `camelCase`，测试类命名为 `*Test.java`。`data-visualizer-front` 中的 TypeScript 沿用现有风格：2 空格缩进、开启严格类型检查、React 组件使用 `PascalCase`，框架约定文件使用 `page.tsx`、`layout.tsx` 等命名。提交前端改动前请先运行 ESLint；若涉及前端实现，也请同时阅读 `data-visualizer-front/AGENTS.md`。

## 测试指南
新增或修改功能时，应在对应模块补充聚焦明确的 JUnit 或 Spring Boot 测试；需要夹具文件时放在 `src/test/resources`。优先覆盖 agent 工作流、控制器和配置类等关键路径。当前前端尚未配置测试框架，因此 UI 改动至少应通过 `npm run lint`，并在 `npm run dev` 下完成手动验证。

## 提交与 Pull Request 规范
新提交也应保持简洁、使用祈使语气，并尽量一次只处理一个主题。Pull Request 需要说明影响的模块、列出本地验证命令、关联相关 issue，并为 UI 或 API 变更附上截图或请求响应示例。不要提交密钥或凭证，镜像仓库账号等敏感信息应保存在 `.local-config` 这类本地文件中。
