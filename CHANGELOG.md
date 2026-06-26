# Changelog

## [1.3.1] — 2026-06-26

### Added
- 安全规则配置页面增强：刷新按钮、序号列、前端分页、批量删除
- 硬阻断/软阻断规则支持启停状态筛选
- 设置面板新增安全规则入口，显示各类型规则计数（白/查/硬/软）
- 后端新增安全规则统计接口 `GET /security/stats`
- 后端新增批量删除接口 `POST /security/{type}/batch-delete`
- 安全规则配置页面仅限 ADMIN 角色访问

### Changed
- 安全规则列表默认每页 10 条（原全量无分页）
- 设置面板打开时加载安全规则统计和管理员身份信息

## [1.3.0] — 2026-06-25

### Added
- SecuritySettings 安全规则配置页面（4 Tab：命令白名单/默认允许/硬阻断/软阻断）
- SecurityConfigCtrl REST API（含权限校验、参数验证、审计日志）
- SecurityConfigService + volatile ConfigSnapshot 缓存
- SecurityRuleEntity + SecurityConfigDao + 四方言 Mapper XML
- 安全配置 SQL 初始化脚本（YAML 种子数据迁移到 DB）
- 系统 tmp workspace 自动创建与管理

### Changed
- 移除 YAML 安全配置，规则全部迁移至 DB
- 重构系统提示词组装逻辑，模板变量替代字符串拼接

### Fixed
- 移除重复 logger 定义，消除控制台日志双份输出
- PostgreSQL tinyint 改为 smallint，兼容跨数据库类型

## [1.2.1] — 2026-06-22

### Added
- 新建对话默认绑定 tmp workspace

## [1.2.0] — 2026-06-20

### Added
- Knowledge Base 管理（CRUD + 文档上传 + 召回测试）
- Workspace 管理页面
- Memory 管理页面

## [1.1.2] — 2026-06-15

### Fixed
- 多个 UI 修复和暗黑模式适配

## [1.1.1] — 2026-06-10

### Added
- 初始版本：多模型对话、MCP 管理、Skill 管理
