# Changelog

本项目遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)，格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/)。

## [1.0.0] - 2026-06-12

### 新增

- **聊天对话**：SSE 流式 LLM 对话，支持 thinking blocks 折叠展示、工具调用实时可视化卡片（含计时器和状态动画）、请求取消
- **MCP 管理**：MCP Server CRUD、工具自动发现（listTools）、连接池管理、工具调用超时强制终止
- **模型配置**：模型 API 配置 CRUD（AES 加密存储敏感字段）、API 可用性探测
- **Skill 管理**：Skill 导入安装、从 Claude/Codex/OpenCode 同步、Skill 列表查询
- **Memory 管理**：会话记忆读写、持久化存储
- **Workspace**：工作目录管理，支持目录选择器和原生 showDirectoryPicker，system prompt 注入工作目录信息
- **文件上传**：聊天文件上传，chip 卡片展示（内嵌 cmd-tag），去重只保留最后一次
- **输入框**：命令菜单（合并会话/操作），模型选择器（含上下文窗口和推理能力标识）
- **会话持久化**：对话历史保存与还原，合并连续消息的 toolCalls
- **配置管理**：ZephyrConfigProperties 统一配置类，替代散落 @Value 注解
- **国际化**：中文/英文/日文三语支持

### 基础设施

- Spring Boot 3.5.4 + Vue 3 + TypeScript + Element Plus
- MyBatis 多方言 DDL（embedded/mysql/postgresql）
- H2 嵌入式数据库，表自动创建
- i18n 国际化消息系统
