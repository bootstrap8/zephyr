# Changelog

本项目遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)，格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/)。

## [1.1.0] - 2026-06-12

### 新增

- **知识库功能**：支持创建知识库、上传文档（PDF/Word/Markdown/TXT 等），自动解析、切分、向量化存储
- **知识库检索**：`search_knowledge` 内置工具，对话中勾选知识库后模型可主动检索相关文档片段
- **Embedding 模型支持**：模型配置支持对话模型和 Embedding 模型分类管理
- **Chroma 向量数据库集成**：本地 embed 模式 + 生产独立部署，OkHttp HTTP 客户端封装
- **文档处理流水线**：Apache Tika 文本提取 → 递归字符切分 → Embedding 向量化 → Chroma 存储
- **对话级知识库选择**：对话中可勾选/取消知识库，切换对话自动恢复勾选状态

## [1.0.2] - 2026-06-12

### 新增

- **Skill 用户隔离与共享机制**：支持 `user`/`shared` scope，个人 Skill 仅自己可见，共享 Skill 所有用户可见

### 变更

- 记忆管理页面去掉项目维度过滤和类型选项

### 文档

- 更新 README 访问地址和默认密码
- Skill 用户隔离 + 共享机制设计文档

## [1.0.1] - 2026-06-12

### 新增

- **聊天模式切换**：Default/Accept Edits/Bypass 三种模式，三套文件系统安全提示词
- **文件系统安全强化**：路径规范化（强制解析相对路径、消除 `..`、解析符号链接）、边界精确前缀检查、禁用路径遍历

### 修复

- 聊天页面 loading 闪屏
- 重命名输入框自动聚焦全选
- Zephyr 菜单图标从 agent 改为 HAProxyIcon
- 切换 workspace 时更新对话归属

### 优化

- 清除所有 Vue 文件中硬编码中文，补全三语 i18n key
- 表名统一添加 `zephyr_` 前缀

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
