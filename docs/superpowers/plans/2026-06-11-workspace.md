# 工作空间（Workspace）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 zephyr 增加工作空间功能——用户选择本地目录，LLM 生成的文件落在该目录下。工作空间独立于会话，多个会话可共享同一工作空间。

**Architecture:** 新增 `workspace` 模块（Entity → DAO → Service → Ctrl），复用项目现有的五层包结构。会话表增加 `workspace_id` 外键列，ContextBuilder 根据会话关联的 workspace 注入工作目录到 system prompt。前端在 InputArea 模型选择器前增加 workspace 选择器。

**Tech Stack:** Java 17, SpringBoot 3.5.4, MyBatis, H2 (embedded), Vue 3 + TS + Pinia

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `src/main/java/.../workspace/dao/entity/WorkspaceEntity.java` | 新增 | 工作空间实体 |
| `src/main/java/.../workspace/dao/WorkspaceDao.java` | 新增 | DAO 接口 |
| `src/main/java/.../workspace/dao/mapper/common/WorkspaceMapper.xml` | 新增 | DML |
| `src/main/java/.../workspace/dao/mapper/embedded/WorkspaceMapper.xml` | 新增 | H2 DDL |
| `src/main/java/.../workspace/dao/mapper/mysql/WorkspaceMapper.xml` | 新增 | MySQL DDL |
| `src/main/java/.../workspace/dao/mapper/postgresql/WorkspaceMapper.xml` | 新增 | PostgreSQL DDL |
| `src/main/java/.../workspace/service/WorkspaceService.java` | 新增 | 服务接口 |
| `src/main/java/.../workspace/service/impl/WorkspaceServiceImpl.java` | 新增 | 服务实现 |
| `src/main/java/.../workspace/ctrl/WorkspaceCtrl.java` | 新增 | REST 接口 |
| `src/main/java/.../chat/dao/entity/ConversationEntity.java` | 修改 | 增加 workspaceId |
| `src/main/java/.../chat/model/ConversationVO.java` | 修改 | 增加 workspaceId |
| `src/main/java/.../chat/dao/ChatDao.java` | 修改 | insert 增加 workspaceId |
| `src/main/java/.../chat/dao/mapper/common/ChatMapper.xml` | 修改 | DML 增加 workspace_id |
| `src/main/java/.../chat/dao/mapper/embedded/ChatMapper.xml` | 修改 | DDL 增加 workspace_id |
| `src/main/java/.../chat/dao/mapper/mysql/ChatMapper.xml` | 修改 | DDL 增加 workspace_id |
| `src/main/java/.../chat/dao/mapper/postgresql/ChatMapper.xml` | 修改 | DDL 增加 workspace_id |
| `src/main/java/.../chat/service/impl/ConversationServiceImpl.java` | 修改 | create/list/getMessages 处理 workspaceId |
| `src/main/java/.../chat/service/impl/ChatServiceImpl.java` | 修改 | 自动创建会话时处理 workspaceId |
| `src/main/java/.../chat/service/ContextBuilder.java` | 修改 | 注入 workspace 路径到 prompt |
| `src/main/java/.../service/impl/InitialServiceImpl.java` | 修改 | 注册 workspace 表 |
| `src/main/resources/zephyr-zh-CN.sql` | 修改 | conversations 加 workspace_id 列 |
| `src/main/resources/zephyr-en-US.sql` | 修改 | 同上 |
| `src/main/resources/zephyr-ja-JP.sql` | 修改 | 同上 |
| `src/main/resources/static/src/types/chat.ts` | 修改 | 增加 Workspace 类型 |
| `src/main/resources/static/src/store/workspace.ts` | 新增 | workspace Pinia store |
| `src/main/resources/static/src/views/chat/InputArea.vue` | 修改 | 模型选择器前加 workspace 选择器 |
| `src/main/resources/static/src/views/chat/WorkspaceDialog.vue` | 新增 | 新建 workspace 对话框 |

---

### Task 1: 创建 WorkspaceEntity

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/workspace/dao/entity/WorkspaceEntity.java`

- [ ] **Step 1: 编写实体类**

```java
package com.github.hbq969.ai.zephyr.workspace.dao.entity;

import lombok.Data;

@Data
public class WorkspaceEntity {
    private String id;
    private String name;
    private String path;
    private String userName;
    private Long createdAt;
    private Long updatedAt;
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/workspace/dao/entity/WorkspaceEntity.java
git commit -m "feat: 新增 WorkspaceEntity 实体类"
```

---

### Task 2: 创建 WorkspaceDao + Mapper XML

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/workspace/dao/WorkspaceDao.java`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/workspace/dao/mapper/common/WorkspaceMapper.xml`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/workspace/dao/mapper/embedded/WorkspaceMapper.xml`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/workspace/dao/mapper/mysql/WorkspaceMapper.xml`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/workspace/dao/mapper/postgresql/WorkspaceMapper.xml`

- [ ] **Step 1: 编写 DAO 接口**

```java
package com.github.hbq969.ai.zephyr.workspace.dao;

import com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity;
import com.github.hbq969.code.common.datasource.DS;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
@DS
public interface WorkspaceDao {

    void createWorkspacesTable();

    List<WorkspaceEntity> queryByUserName(@Param("userName") String userName);
    void insert(WorkspaceEntity entity);
    void delete(@Param("id") String id, @Param("userName") String userName);
    WorkspaceEntity queryById(@Param("id") String id);
}
```

- [ ] **Step 2: 编写 common Mapper XML（DML）**

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd" >
<mapper namespace="com.github.hbq969.ai.zephyr.workspace.dao.WorkspaceDao">

    <select id="queryByUserName" resultType="com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity">
        select id, name, path, user_name as userName,
               created_at as createdAt, updated_at as updatedAt
        from zephyr_workspaces
        where user_name = #{userName}
        order by updated_at desc
    </select>

    <insert id="insert">
        insert into zephyr_workspaces (id, name, path, user_name, created_at, updated_at)
        values (#{id}, #{name}, #{path}, #{userName}, #{createdAt}, #{updatedAt})
    </insert>

    <delete id="delete">
        delete from zephyr_workspaces where id = #{id} and user_name = #{userName}
    </delete>

    <select id="queryById" resultType="com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity">
        select id, name, path, user_name as userName,
               created_at as createdAt, updated_at as updatedAt
        from zephyr_workspaces where id = #{id}
    </select>

</mapper>
```

- [ ] **Step 3: 编写 embedded Mapper XML（H2 DDL）**

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd" >
<mapper namespace="com.github.hbq969.ai.zephyr.workspace.dao.WorkspaceDao">

  <update id="createWorkspacesTable">
    create table if not exists zephyr_workspaces (
      id varchar(64) primary key,
      name varchar(128) not null,
      path varchar(512) not null,
      user_name varchar(64) not null,
      created_at bigint,
      updated_at bigint
    );
    create index if not exists idx_ws_user on zephyr_workspaces(user_name);
  </update>

</mapper>
```

- [ ] **Step 4: 编写 mysql Mapper XML（DDL）**

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd" >
<mapper namespace="com.github.hbq969.ai.zephyr.workspace.dao.WorkspaceDao">

  <update id="createWorkspacesTable">
    create table if not exists zephyr_workspaces (
      id varchar(64) primary key,
      name varchar(128) not null,
      path varchar(512) not null,
      user_name varchar(64) not null,
      created_at bigint,
      updated_at bigint
    );
    create index if not exists idx_ws_user on zephyr_workspaces(user_name);
  </update>

</mapper>
```

- [ ] **Step 5: 编写 postgresql Mapper XML（DDL）**

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd" >
<mapper namespace="com.github.hbq969.ai.zephyr.workspace.dao.WorkspaceDao">

  <update id="createWorkspacesTable">
    create table if not exists zephyr_workspaces (
      id varchar(64) primary key,
      name varchar(128) not null,
      path varchar(512) not null,
      user_name varchar(64) not null,
      created_at bigint,
      updated_at bigint
    );
    create index if not exists idx_ws_user on zephyr_workspaces(user_name);
  </update>

</mapper>
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/workspace/dao/
git commit -m "feat: 新增 WorkspaceDao 及三方言 Mapper XML"
```

---

### Task 3: 创建 WorkspaceService + WorkspaceCtrl

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/workspace/service/WorkspaceService.java`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/workspace/service/impl/WorkspaceServiceImpl.java`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/workspace/ctrl/WorkspaceCtrl.java`

- [ ] **Step 1: 编写 Service 接口**

```java
package com.github.hbq969.ai.zephyr.workspace.service;

import com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity;

import java.util.List;
import java.util.Map;

public interface WorkspaceService {
    List<WorkspaceEntity> list(String userName);
    WorkspaceEntity create(Map<String, String> body, String userName);
    void delete(String id, String userName);
}
```

- [ ] **Step 2: 编写 Service 实现**

```java
package com.github.hbq969.ai.zephyr.workspace.service.impl;

import cn.hutool.core.lang.UUID;
import com.github.hbq969.ai.zephyr.workspace.dao.WorkspaceDao;
import com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity;
import com.github.hbq969.ai.zephyr.workspace.service.WorkspaceService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class WorkspaceServiceImpl implements WorkspaceService {

    @Resource
    private WorkspaceDao workspaceDao;

    @Override
    public List<WorkspaceEntity> list(String userName) {
        return workspaceDao.queryByUserName(userName);
    }

    @Override
    @Transactional
    public WorkspaceEntity create(Map<String, String> body, String userName) {
        String name = body.get("name");
        String path = body.get("path");
        if (path == null || path.isBlank()) {
            throw new RuntimeException("目录路径不能为空");
        }
        if (name == null || name.isBlank()) {
            name = java.nio.file.Path.of(path).getFileName().toString();
        }
        long now = System.currentTimeMillis() / 1000;
        WorkspaceEntity entity = new WorkspaceEntity();
        entity.setId(UUID.fastUUID().toString(true).substring(0, 12));
        entity.setName(name);
        entity.setPath(path);
        entity.setUserName(userName);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        workspaceDao.insert(entity);
        return entity;
    }

    @Override
    @Transactional
    public void delete(String id, String userName) {
        workspaceDao.delete(id, userName);
    }
}
```

- [ ] **Step 3: 编写 Controller**

```java
package com.github.hbq969.ai.zephyr.workspace.ctrl;

import com.github.hbq969.ai.zephyr.workspace.service.WorkspaceService;
import com.github.hbq969.code.common.restful.ReturnMessage;
import com.github.hbq969.code.common.spring.context.UserInfo;
import com.github.hbq969.code.sm.login.session.UserContext;
import com.github.hbq969.code.sm.perm.api.SMRequiresPermissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "工作空间接口")
@RestController
@RequestMapping(path = "/zephyr-ui/workspace")
public class WorkspaceCtrl {

    @Resource
    private WorkspaceService workspaceService;

    private String userName() {
        UserInfo ui = UserContext.getNoCheck();
        return ui != null ? ui.getUserName() : "admin";
    }

    @Operation(summary = "获取工作空间列表")
    @RequestMapping(path = "/list", method = RequestMethod.GET)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "workspace_list", apiDesc = "工作空间_获取列表")
    public ReturnMessage<?> list() {
        return ReturnMessage.success(workspaceService.list(userName()));
    }

    @Operation(summary = "新建工作空间")
    @RequestMapping(path = "/create", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "workspace_create", apiDesc = "工作空间_新建")
    public ReturnMessage<?> create(@RequestBody Map<String, String> body) {
        return ReturnMessage.success(workspaceService.create(body, userName()));
    }

    @Operation(summary = "删除工作空间")
    @RequestMapping(path = "/delete", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "workspace_delete", apiDesc = "工作空间_删除")
    public ReturnMessage<?> delete(@RequestBody Map<String, String> body) {
        workspaceService.delete(body.get("id"), userName());
        return ReturnMessage.success("ok");
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/workspace/service/ src/main/java/com/github/hbq969/ai/zephyr/workspace/ctrl/
git commit -m "feat: 新增 WorkspaceService 和 WorkspaceCtrl"
```

---

### Task 4: 注册 workspace 表到 InitialServiceImpl

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/service/impl/InitialServiceImpl.java`

- [ ] **Step 1: 注入 WorkspaceDao 并注册建表**

在 `InitialServiceImpl.java` 中添加：

```java
@Resource
private com.github.hbq969.ai.zephyr.workspace.dao.WorkspaceDao workspaceDao;
```

在 `tableCreate0()` 方法末尾（最后一个 `chatDao.createMessagesTable()` 之后）添加：

```java
com.github.hbq969.code.common.utils.ThrowUtils.call("zephyr_workspaces",
        () -> workspaceDao.createWorkspacesTable());
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/service/impl/InitialServiceImpl.java
git commit -m "feat: InitialServiceImpl 注册 workspace 表创建"
```

---

### Task 5: Conversation 表增加 workspace_id 列

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/dao/entity/ConversationEntity.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/model/ConversationVO.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/dao/ChatDao.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/dao/mapper/common/ChatMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/dao/mapper/embedded/ChatMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/dao/mapper/mysql/ChatMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/dao/mapper/postgresql/ChatMapper.xml`
- Modify: `src/main/resources/zephyr-zh-CN.sql`
- Modify: `src/main/resources/zephyr-en-US.sql`
- Modify: `src/main/resources/zephyr-ja-JP.sql`

- [ ] **Step 1: ConversationEntity 增加 workspaceId 字段**

```java
package com.github.hbq969.ai.zephyr.chat.dao.entity;

import lombok.Data;

@Data
public class ConversationEntity {
    private String id;
    private String userName;
    private String title;
    private String workspaceId;
    private Long createdAt;
    private Long updatedAt;
}
```

- [ ] **Step 2: ConversationVO 增加 workspaceId 字段**

在 `ConversationVO.java` 的 private 字段区域增加：

```java
private String workspaceId;
```

- [ ] **Step 3: ChatDao.insertConversation 参数更新**

`insertConversation` 方法签名不变（entity 有 workspaceId 字段即可），但需要确认 XML 中 insert 语句包含 workspace_id。无需修改 Java 接口。

- [ ] **Step 4: 更新 embedded ChatMapper.xml**

`createConversationsTable` 的 DDL 中，`updated_at bigint` 后增加：

```xml
workspace_id varchar(64)
```

即在 `updated_at bigint` 后面加一行。

`insertConversation` 的 DML 改为：

```xml
<insert id="insertConversation">
    insert into conversations (id, user_name, title, workspace_id, created_at, updated_at)
    values (#{id}, #{userName}, #{title}, #{workspaceId}, #{createdAt}, #{updatedAt})
</insert>
```

`queryConversations` 和 `queryConversationById` 的 select 子句中增加 `workspace_id as workspaceId`。

- [ ] **Step 5: 同样更新 mysql/postgresql ChatMapper.xml DDL 和 common ChatMapper.xml DML**

三个方言 DDL 都增加 `workspace_id varchar(64)` 列，common DML 中 insert/select 同步更新。

- [ ] **Step 6: SQL 增量迁移文件**

三个 SQL 文件都追加：

```sql
alter table conversations add column if not exists workspace_id varchar(64);
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/dao/ src/main/java/com/github/hbq969/ai/zephyr/chat/model/ src/main/resources/
git commit -m "feat: conversations 表增加 workspace_id 列"
```

---

### Task 6: ConversationService 处理 workspaceId

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ConversationServiceImpl.java`

- [ ] **Step 1: create 方法支持 workspaceId**

在 `create` 方法中，`entity.setTitle(title)` 之后增加：

```java
entity.setWorkspaceId(body.get("workspaceId"));
```

- [ ] **Step 2: list 方法返回 workspaceId**

在 `ConversationVO.builder()` 链中增加：

```java
.workspaceId(e.getWorkspaceId())
```

- [ ] **Step 3: getMessages 方法返回 workspaceId**

在返回结果 `result` 列表构建完成后（return 之前），将当前会话的 workspaceId 附加到响应中。但当前接口返回 `List<Map>`，不方便附加元数据。

改为返回一个包含 messages 和 workspaceId 的 Map：

```java
Map<String, Object> response = new HashMap<>();
response.put("messages", result);
response.put("workspaceId", conv.getWorkspaceId());
return response;
```

**注意：** 前端 `ChatView.vue` 的 `restoreConversation` 方法中解析 `res.data.body` 为消息数组，需同步修改。此处先改后端，前端在 Task 9 中一起调整。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ConversationServiceImpl.java
git commit -m "feat: ConversationService 支持 workspaceId 的创建/查询/返回"
```

---

### Task 7: ChatServiceImpl 自动创建会话时传递 workspaceId

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/model/ChatRequest.java`

- [ ] **Step 1: ChatRequest 增加 workspaceId**

检查 `ChatRequest.java` 当前字段：

```java
package com.github.hbq969.ai.zephyr.chat.model;

import lombok.Data;

@Data
public class ChatRequest {
    private String conversationId;
    private String message;
}
```

增加字段：

```java
private String workspaceId;
```

- [ ] **Step 2: ChatServiceImpl.send 在自动创建会话时设置 workspaceId**

在 `send` 方法中，创建 `ConversationEntity` 后、insert 之前，增加从 ChatRequest 获取 workspaceId 的逻辑。需要在方法签名中获取到 ChatRequest 对象。

查看 `ChatCtrl` 如何调用 `send`：

```java
@RequestMapping(path = "/send", method = RequestMethod.POST)
public SseEmitter send(@RequestBody ChatRequest request) {
    return chatService.send(userName(), request.getConversationId(), request.getMessage());
}
```

需要改为传递 `ChatRequest`，或增加 `workspaceId` 参数。为保持最小改动，在 `send` 方法签名中增加 `workspaceId` 参数：

```java
// ChatService 接口
SseEmitter send(String userName, String conversationId, String workspaceId, String originalMessage);

// ChatServiceImpl
public SseEmitter send(String userName, String conversationId, String workspaceId, String originalMessage) {
```

在自动创建会话的代码块中，`conv.setTitle(...)` 之后增加：

```java
conv.setWorkspaceId(workspaceId);
```

- [ ] **Step 3: ChatCtrl 传递 workspaceId**

修改 `ChatCtrl.send` 方法：

```java
@RequestMapping(path = "/send", method = RequestMethod.POST)
public SseEmitter send(@RequestBody ChatRequest request) {
    return chatService.send(userName(), request.getConversationId(), request.getWorkspaceId(), request.getMessage());
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/
git commit -m "feat: 聊天请求支持传递 workspaceId"
```

---

### Task 8: ContextBuilder 注入 workspace 路径到 system prompt

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/ContextBuilder.java`

- [ ] **Step 1: 注入 WorkspaceDao 并追加 workspace 提示**

在 `ContextBuilder` 中注入：

```java
@Resource
private com.github.hbq969.ai.zephyr.workspace.dao.WorkspaceDao workspaceDao;
```

在 `build` 方法中，`systemPrompt` 组装完成后、`toolDefs` 添加内置工具之前（即在 `// 6. 添加内置工具` 之前），增加：

```java
// 4.5 工作空间
if (conversationId != null && !conversationId.isEmpty()) {
    ConversationEntity conv = chatDao.queryConversationById(conversationId);
    if (conv != null && conv.getWorkspaceId() != null && !conv.getWorkspaceId().isEmpty()) {
        WorkspaceEntity ws = workspaceDao.queryById(conv.getWorkspaceId());
        if (ws != null) {
            systemPrompt.append("\n\n## 工作空间\n")
                .append("当前工作目录: ").append(ws.getPath()).append("\n")
                .append("使用文件系统工具时，请将文件路径限定在此目录下。\n");
        }
    }
}
```

需要在文件顶部增加 ConversationEntity 和 WorkspaceEntity 的 import（或者直接用全限定名引用的方式避免 import 冲突）。检查 ContextBuilder 已有的 import，增加：

```java
import com.github.hbq969.ai.zephyr.chat.dao.entity.ConversationEntity;
import com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity;
```

实际上 `ConversationEntity` 在 ContextBuilder 中尚未被 import，因为 `buildMessages` 用 `chatDao` 查询，不直接引用 Entity 类。但这里需要用它，需要新增 import。

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/ContextBuilder.java
git commit -m "feat: ContextBuilder 根据 workspace 注入工作目录到 system prompt"
```

---

### Task 9: 前端类型和 store

**Files:**
- Modify: `src/main/resources/static/src/types/chat.ts`
- Create: `src/main/resources/static/src/store/workspace.ts`
- Modify: `src/main/resources/static/src/views/chat/ChatView.vue`

- [ ] **Step 1: chat.ts 增加 Workspace 类型**

在 `chat.ts` 末尾增加：

```typescript
export interface Workspace {
  id: string
  name: string
  path: string
  createdAt: number
  updatedAt: number
}
```

同时更新 `Conversation` 接口增加 `workspaceId?: string`。

- [ ] **Step 2: 创建 workspace store**

```typescript
import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Workspace } from '@/types/chat'

export const useWorkspaceStore = defineStore('workspace', () => {
  const workspaces = ref<Workspace[]>([])
  const currentId = ref<string | null>(null)

  const current = computed(() =>
    workspaces.value.find(w => w.id === currentId.value) ?? null
  )

  function setWorkspaces(list: Workspace[]) {
    workspaces.value = list
  }

  function selectWorkspace(id: string | null) {
    currentId.value = id
  }

  function addWorkspace(ws: Workspace) {
    workspaces.value.unshift(ws)
    currentId.value = ws.id
  }

  function removeWorkspace(id: string) {
    workspaces.value = workspaces.value.filter(w => w.id !== id)
    if (currentId.value === id) currentId.value = null
  }

  return { workspaces, currentId, current, setWorkspaces, selectWorkspace, addWorkspace, removeWorkspace }
})
```

- [ ] **Step 3: ChatView.vue 加载 workspace 列表 + 适配 getMessages 响应变更**

在 `onMounted` 中增加加载 workspace：

```typescript
axios({ url: '/workspace/list', method: 'get' })
  .then(res => {
    if (res.data.state === 'OK') workspaceStore.setWorkspaces(res.data.body)
  })
```

修改 `restoreConversation` 方法，适配 Task 6 中 getMessages 返回格式变更（从数组变为 `{messages, workspaceId}`）：

```typescript
function restoreConversation(id: string) {
  axios({ url: `/conversations/${id}/messages`, method: 'get' })
    .then(res => {
      if (res.data.state === 'OK') {
        const body = res.data.body
        const msgs = (body.messages || body || []).map(...)
        // ... 后续处理不变
        // 恢复 workspace 选择
        if (body.workspaceId) {
          workspaceStore.selectWorkspace(body.workspaceId)
        }
      }
    })
}
```

注入 `useWorkspaceStore`：

```typescript
import { useWorkspaceStore } from '@/store/workspace'
const workspaceStore = useWorkspaceStore()
```

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/static/src/types/chat.ts src/main/resources/static/src/store/workspace.ts src/main/resources/static/src/views/chat/ChatView.vue
git commit -m "feat: 前端 workspace 类型定义、store 及 ChatView 集成"
```

---

### Task 10: InputArea workspace 选择器

**Files:**
- Modify: `src/main/resources/static/src/views/chat/InputArea.vue`

- [ ] **Step 1: 在模型选择器前增加 workspace 选择器**

修改 `InputArea.vue` 的 `<script>` 部分：

```typescript
import { useWorkspaceStore } from '@/store/workspace'
const workspaceStore = useWorkspaceStore()
const showWorkspaceList = ref(false)
const showNewWorkspace = ref(false)

function toggleWorkspaceList() {
  closeAll(); showWorkspaceList.value = !showWorkspaceList.value
}

function selectWorkspace(id: string | null) {
  workspaceStore.selectWorkspace(id)
  showWorkspaceList.value = false
}
```

在模板中，`input-left` 的第一个元素（模型选择器 `.tool-pick`）之前，增加：

```html
<!-- 工作空间选择 -->
<div class="tool-pick" @click.stop="toggleWorkspaceList">
  <template v-if="workspaceStore.current">
    <Icon icon="lucide:folder" class="ws-icon" />
    <span>{{ workspaceStore.current.name }}</span>
  </template>
  <template v-else>
    <Icon icon="lucide:folder" class="ws-icon dim" />
  </template>
  <Icon icon="lucide:chevron-down" class="pick-arrow" />
  <div v-if="showWorkspaceList" class="pick-dropdown" @click.stop>
    <div v-for="ws in workspaceStore.workspaces" :key="ws.id"
         class="pick-option"
         :class="{ current: workspaceStore.currentId === ws.id }"
         @click="selectWorkspace(ws.id)">
      <span class="ws-name">{{ ws.name }}</span>
      <span class="ws-path">{{ ws.path }}</span>
    </div>
    <div v-if="workspaceStore.workspaces.length > 0" class="pick-divider"></div>
    <div class="pick-option" @click="showWorkspaceList = false; showNewWorkspace = true">
      <Icon icon="lucide:plus" />新建工作空间
    </div>
  </div>
</div>
```

- [ ] **Step 2: 发送消息时传递 workspaceId**

在 `doSend` 方法中，目前通过 SSE 的 `axios` post 传递 `{ conversationId, message }`。需要增加 `workspaceId`：

修改 `ChatView.vue` 中 `onSend` 的 axios 请求 data：

```typescript
data: { conversationId: convStore.currentId, message: text, workspaceId: workspaceStore.currentId },
```

- [ ] **Step 3: 增加 workspace 选择器样式**

```css
/* scoped 样式中 */
.ws-icon { font-size: 14px; color: var(--el-text-color-secondary); }
.ws-icon.dim { color: var(--el-text-color-placeholder); }
.ws-name { font-weight: 500; font-size: 13px; }
.ws-path { color: var(--el-text-color-placeholder); font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 180px; }
.pick-divider { height: 1px; background: var(--el-border-color); margin: 4px 0; }
```

- [ ] **Step 4: Teleport 遮罩层增加 workspace 关闭**

在 Teleport 区域增加：

```html
<div v-if="showWorkspaceList" class="model-overlay" @click="showWorkspaceList = false"></div>
```

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/src/views/chat/InputArea.vue src/main/resources/static/src/views/chat/ChatView.vue
git commit -m "feat: InputArea 增加 workspace 选择器"
```

---

### Task 11: Workspace 新建对话框

**Files:**
- Create: `src/main/resources/static/src/views/chat/WorkspaceDialog.vue`

- [ ] **Step 1: 编写 WorkspaceDialog 组件**

```vue
<script lang="ts" setup>
import { ref } from 'vue'
import { useWorkspaceStore } from '@/store/workspace'
import { Icon } from '@iconify/vue'
import axios from '@/network'
import { msg } from '@/utils/Utils'

const emit = defineEmits<{ close: [] }>()
const workspaceStore = useWorkspaceStore()
const name = ref('')
const path = ref('')
const saving = ref(false)

function onSubmit() {
  if (!path.value.trim()) { msg('请填写目录路径', 'warning'); return }
  saving.value = true
  axios({
    url: '/workspace/create',
    method: 'post',
    data: { name: name.value.trim() || undefined, path: path.value.trim() }
  })
    .then(res => {
      if (res.data.state === 'OK') {
        workspaceStore.addWorkspace(res.data.body)
        emit('close')
      } else {
        msg(res.data.errorMessage, 'warning')
      }
    })
    .catch(err => msg(err?.response?.data?.errorMessage, 'error'))
    .finally(() => { saving.value = false })
}
</script>

<template>
  <Teleport to="body">
    <div class="ws-dialog-overlay" @click="emit('close')"></div>
    <div class="ws-dialog">
      <div class="ws-dialog-header">
        <span>新建工作空间</span>
        <button class="ws-dialog-close" @click="emit('close')">
          <Icon icon="lucide:x" />
        </button>
      </div>
      <div class="ws-dialog-body">
        <label class="ws-field">
          <span>名称</span>
          <input v-model="name" class="ws-input" placeholder="选填，默认取目录最后一级名" @keydown.enter="onSubmit" />
        </label>
        <label class="ws-field">
          <span>目录</span>
          <input v-model="path" class="ws-input" placeholder="/Users/hbq/my-project" @keydown.enter="onSubmit" />
        </label>
      </div>
      <div class="ws-dialog-footer">
        <button class="ws-btn ws-btn-cancel" @click="emit('close')">取消</button>
        <button class="ws-btn ws-btn-confirm" :disabled="saving" @click="onSubmit">
          {{ saving ? '创建中...' : '创建' }}
        </button>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.ws-dialog-overlay { position: fixed; inset: 0; z-index: 1000; background: rgba(0,0,0,0.2); backdrop-filter: blur(2px); }
.ws-dialog { position: fixed; top: 30%; left: 50%; transform: translate(-50%, -50%); width: 420px; max-width: 90vw; background: var(--el-bg-color); border: 1px solid var(--el-border-color); border-radius: 12px; box-shadow: 0 12px 48px rgba(0,0,0,0.12); z-index: 1001; }
.ws-dialog-header { display: flex; align-items: center; justify-content: space-between; padding: 16px 20px 0; font-size: 16px; font-weight: 600; color: var(--el-text-color-primary); }
.ws-dialog-close { width: 30px; height: 30px; border-radius: 50%; border: none; background: transparent; color: var(--el-text-color-secondary); cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 16px; }
.ws-dialog-close:hover { background: var(--el-fill-color-light); }
.ws-dialog-body { padding: 16px 20px; display: flex; flex-direction: column; gap: 12px; }
.ws-field { display: flex; flex-direction: column; gap: 4px; }
.ws-field span { font-size: 13px; color: var(--el-text-color-secondary); }
.ws-input { width: 100%; padding: 8px 12px; border: 1px solid var(--el-border-color); border-radius: 8px; background: var(--el-bg-color); color: var(--el-text-color-primary); font-size: 14px; outline: none; font-family: inherit; box-sizing: border-box; }
.ws-input:focus { border-color: var(--el-color-primary); }
.ws-input::placeholder { color: var(--el-text-color-placeholder); }
.ws-dialog-footer { display: flex; justify-content: flex-end; gap: 8px; padding: 0 20px 16px; }
.ws-btn { padding: 7px 18px; border-radius: 8px; border: 1px solid var(--el-border-color); font-size: 13px; cursor: pointer; transition: background 0.15s; }
.ws-btn-cancel { background: var(--el-bg-color); color: var(--el-text-color-regular); }
.ws-btn-cancel:hover { background: var(--el-fill-color-light); }
.ws-btn-confirm { background: var(--el-color-primary); color: #fff; border-color: var(--el-color-primary); }
.ws-btn-confirm:hover { background: var(--el-color-primary-dark-2); }
.ws-btn-confirm:disabled { opacity: 0.6; cursor: default; }
</style>
```

- [ ] **Step 2: InputArea 集成对话框**

在 `InputArea.vue` 中引入并添加：

```html
<WorkspaceDialog v-if="showNewWorkspace" @close="showNewWorkspace = false" />
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/src/views/chat/WorkspaceDialog.vue src/main/resources/static/src/views/chat/InputArea.vue
git commit -m "feat: 新建 workspace 对话框"
```

---

### Task 12: 端到端验证

- [ ] **Step 1: 构建后端并启动**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean package -DskipTests
cp -rf src/main/resources/*.yml target/classes/
cp -rf src/main/resources/*.xml target/classes/
mvn spring-boot:run -Dspring-boot.run.profiles=me
```

- [ ] **Step 2: curl 测试 workspace 接口**

```bash
# 列表（初始为空）
curl -u admin:1 -H "X-SM-Test: 1" "http://localhost:30733/zephyr/zephyr-ui/workspace/list"

# 新建（不填名称）
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/workspace/create" \
  -d '{"path":"/Users/hbq/Codes/me/github/zephyr"}'

# 新建（填名称）
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/workspace/create" \
  -d '{"name":"测试项目","path":"/tmp/test-workspace"}'

# 列表（应有两条）
curl -u admin:1 -H "X-SM-Test: 1" "http://localhost:30733/zephyr/zephyr-ui/workspace/list"
```

- [ ] **Step 3: curl 测试带 workspaceId 的会话创建**

```bash
# 假设 workspaceId 为上面创建的
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/conversations/create" \
  -d '{"title":"测试工作空间对话","workspaceId":"<WORKSPACE_ID>"}'
```

- [ ] **Step 4: 前端构建验证**

```bash
cd src/main/resources/static
npm run type-check
npm run build
```

- [ ] **Step 5: 浏览器验证**

```bash
# 复制前端产物
mkdir -p ../../../target/classes/static && cp -rf zephyr-ui ../../../target/classes/static/
open http://localhost:30733/zephyr/zephyr-ui/index.html
```

验证：
- 输入框左侧显示文件夹图标
- 点击弹出 workspace 列表（初始为空）
- 点击"新建工作空间"，填写名称和路径
- 选择 workspace 后，发送消息，检查 ContextBuilder 日志是否注入了 workspace 路径
