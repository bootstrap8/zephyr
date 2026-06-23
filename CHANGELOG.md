# Changelog

本项目遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)，格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/)。

## [1.1.2] - 2026-06-23

### 新增

- **MCP 连接生命周期管理**：connect 启动持久进程 + listTools 自动发现工具 + DB 状态清理，重启/重连保证状态一致
- **MCP 进程树追踪与清理**：动态查找所有子孙 PID，kill 时先杀子进程再杀父进程，逐个记录 kill 结果便于排查
- **启动自动重连**：Zephyr 启动时自动重连之前处于 connected 状态的 MCP 服务器
- **知识库混合检索**：BM25 关键词检索（文档级 IDF）+ 向量检索 RRF 融合
- **上下文窗口扩展**：RRF 融合后获取相邻 chunk 合并排序返回，提高上下文完整性
- **查询增强**：embedding 前自动提取查询关键词拼接，拉近与 chunk 的语义距离
- **文本清洗管线**：TextCleaner 统一处理文本清理、低质量 chunk 过滤
- **Chroma 路径可配置**：chroma dataDir/binPath/baseUrl 通过配置文件管理
- **知识库文档列表刷新按钮**：方便查看文档处理状态

### 修复

- 删除知识库时同步清理 Chroma 向量数据和文件目录
- TikaParser 解除 100000 字符截断限制
- 启动重连跳过权限检查
- 模型管理空状态无添加按钮

### 测试

- 知识库召回率验证框架：Recall@3 跑分 + baseline + p95 延迟测试 + 15 条数据集

## [1.1.1] - 2026-06-14

### 变更

- **LightRAG sidecar 配置外置**：所有配置项从代码默认值移至 `.env.lightrag` 环境文件，防止敏感信息泄露
- **启动脚本**：新增 `start_lightrag.sh`，自动检查必需环境变量、创建 venv、安装依赖并启动
- **环境配置模板**：新增 `.env.lightrag.example`（可安全提交），`.env.lightrag` 加入 gitignore

## [1.1.0] - 2026-06-14

### 新增

- **知识库功能**：支持创建知识库、上传文档（PDF/Word/Markdown/TXT 等），自动解析、切分、向量化存储
- **知识库检索**：`search_knowledge` 内置工具，对话中勾选知识库后模型可主动检索相关文档片段
- **Embedding 模型支持**：模型配置支持对话模型和 Embedding 模型分类管理
- **Chroma 向量数据库集成**：本地 embed 模式 + 生产独立部署，OkHttp HTTP 客户端封装
- **文档处理流水线**：Apache Tika 文本提取 → 递归字符切分 → Embedding 向量化 → Chroma 存储
- **对话级知识库选择**：对话中可勾选/取消知识库，切换对话自动恢复勾选状态
- **模型共享**：模型配置支持 `user`/`shared` scope，私有模型仅自己可见，共享模型所有用户可见；新增 `user_model_prefs` 偏好表存储默认模型选择
- **MCP/Skill scope 资源隔离**：Skill 和 MCP 支持按 scope 分栏（共享/我的），安装时自动检测同名冲突

### 修复

- **SSE 流式响应偶发丢失**：axios `onDownloadProgress` 节流导致快速 LLM 响应时 assistant 消息被误删，改为 `fetch` + `ReadableStream` 行缓冲解析
- Skill 安装/同步失败时前端显示明确错误提示
- Skill 全选/取消全选按 tab 页（共享/个人）区分
- MCP 工具重名检查只查共享+当前用户，不误拦他人私有工具
- MCP 连接/创建失败时前端显示错误提示
- Embedding 模型隐藏"使用"按钮，仅对话模型可设为默认

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
