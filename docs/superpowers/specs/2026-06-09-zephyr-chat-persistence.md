# 会话/消息持久化 设计规格

## 概述

将 conversations 和 messages 从 mock 数据替换为数据库持久化，按用户隔离。

## 数据模型

### conversations 表

| 列 | 类型 | 说明 |
|----|------|------|
| id | varchar(64) | 主键，UUID |
| user_name | varchar(64) | 用户名 |
| title | varchar(256) | 会话标题 |
| created_at | bigint | Unix 秒 |
| updated_at | bigint | Unix 秒 |

### messages 表

| 列 | 类型 | 说明 |
|----|------|------|
| id | varchar(64) | 主键，UUID |
| conversation_id | varchar(64) | 会话 ID |
| role | varchar(16) | `user` / `assistant` / `system` / `tool` |
| content | text | 消息正文 |
| thinking | text | 思考过程（仅 assistant，可空） |
| tool_calls_json | text | 工具调用 JSON（仅 assistant，可空） |
| tool_call_id | varchar(128) | 关联的工具调用 ID（仅 tool role，可空） |
| created_at | bigint | Unix 秒 |

### 前端类型映射

```
Message.id         → messages.id
Message.role       → messages.role
Message.content    → messages.content
Message.thinking   → messages.thinking
Message.toolCalls  → JSON.parse(messages.tool_calls_json)
Message.timestamp  → messages.created_at
```

## API 设计

Base path: `/conversations`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/list` | 当前用户会话列表，按 updated_at 倒序 |
| POST | `/create` | 新建会话（body: `{title}`） |
| POST | `/rename` | 重命名（body: `{id, title}`） |
| POST | `/delete` | 删除会话+级联删消息（body: `{id}`） |
| GET | `/{id}/messages` | 获取会话历史消息，按 created_at 正序 |

## 包结构

```
com.github.hbq969.ai.zephyr.chat/
├── ctrl/ConversationCtrl.java      # 修改：去掉 mock，注入 ConversationService
├── service/ConversationService.java
├── service/impl/ConversationServiceImpl.java
├── dao/ChatDao.java
├── dao/entity/ConversationEntity.java
├── dao/entity/MessageEntity.java
└── dao/mapper/
    ├── common/ChatMapper.xml
    ├── embedded/ChatMapper.xml
    ├── mysql/ChatMapper.xml
    └── postgresql/ChatMapper.xml
```

## 开发 Checklist

- [ ] Mapper XML DDL：三方言（embedded/postgresql/mysql）添加 `createConversations` + `createMessages`
- [ ] InitialServiceImpl.tableCreate0() 注册两个表
- [ ] DDL 必须 `if not exists`
- [ ] ConversationEntity + MessageEntity 实体类
- [ ] ChatDao 接口 + Mapper XML（common：insert/update/delete/select）
- [ ] ConversationService + ConversationServiceImpl
- [ ] ConversationCtrl 改为注入 Service，去掉 mock 数据
- [ ] ConversationVO 字段对齐实体（去掉 messageCount，改为查询时 count）
- [ ] 前端无改动（接口路径和返回结构不变）

## 安全要求

- 所有查询/修改操作校验 user_name，禁止越权访问
- 删除会话时级联删除消息
