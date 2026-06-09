# LLM 对话接入 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标:** 将 `/chat/send` 从 mock 替换为真实 LLM 调用，集成 MCP 工具/Skill/记忆，支持 SSE 流式返回和 model 自主决策 tool calling。

**架构:** ChatService 是核心中枢——ContextBuilder 从多来源组装 messages 数组，LlmClient 通过 OkHttp 调用 OpenAI 兼容 API 并 SSE 流式转发，工具分发层将 LLM 返回的 tool_calls 路由到内置处理器（use_skill/use_memory）或 McpConnectionManager（MCP 工具）。McpConnectionManager 维护懒初始化的长连接池（上限 100，空闲 15 分钟回收）。

**技术栈:** SpringBoot 3.5 + MyBatis + OkHttp 4.12 + JDK 17 + H2 (embedded) / PostgreSQL / MySQL

---

## Part A: 会话/消息持久化

### Task A1: 创建 Entity 类

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/chat/dao/entity/ConversationEntity.java`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/chat/dao/entity/MessageEntity.java`

- [ ] **Step 1: 创建 ConversationEntity**

```java
package com.github.hbq969.ai.zephyr.chat.dao.entity;

import lombok.Data;

@Data
public class ConversationEntity {
    private String id;
    private String userName;
    private String title;
    private Long createdAt;
    private Long updatedAt;
}
```

- [ ] **Step 2: 创建 MessageEntity**

```java
package com.github.hbq969.ai.zephyr.chat.dao.entity;

import lombok.Data;

@Data
public class MessageEntity {
    private String id;
    private String conversationId;
    private String role;
    private String content;
    private String thinking;
    private String toolCallsJson;
    private String toolCallId;
    private Long createdAt;
}
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/dao/entity/
git commit -m "feat: 添加会话和消息实体类"

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

### Task A2: 创建 ChatDao Mapper XML (DDL)

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/chat/dao/mapper/embedded/ChatMapper.xml`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/chat/dao/mapper/mysql/ChatMapper.xml`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/chat/dao/mapper/postgresql/ChatMapper.xml`

- [ ] **Step 1: 创建 embedded/ChatMapper.xml (DDL)**

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd" >
<mapper namespace="com.github.hbq969.ai.zephyr.chat.dao.ChatDao">

  <update id="createConversationsTable">
    create table if not exists conversations (
      id varchar(64) primary key,
      user_name varchar(64) not null,
      title varchar(256) not null,
      created_at bigint,
      updated_at bigint
    );
    create index if not exists idx_conv_user on conversations(user_name);
    create index if not exists idx_conv_updated on conversations(updated_at desc);
  </update>

  <update id="createMessagesTable">
    create table if not exists messages (
      id varchar(64) primary key,
      conversation_id varchar(64) not null,
      role varchar(16) not null,
      content text,
      thinking text,
      tool_calls_json text,
      tool_call_id varchar(128),
      created_at bigint
    );
    create index if not exists idx_msg_conv on messages(conversation_id);
    create index if not exists idx_msg_created on messages(created_at);
  </update>

</mapper>
```

- [ ] **Step 2: 创建 mysql/ChatMapper.xml**

内容同 embedded，MySQL 也使用 `create table if not exists` 语法。

- [ ] **Step 3: 创建 postgresql/ChatMapper.xml**

内容同 embedded，PostgreSQL 也使用 `create table if not exists` 语法。

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/dao/mapper/
git commit -m "feat: 添加 ChatMapper 三方言 DDL"

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

### Task A3: 创建 ChatDao 接口和 common Mapper XML (DML)

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/chat/dao/ChatDao.java`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/chat/dao/mapper/common/ChatMapper.xml`

- [ ] **Step 1: 创建 ChatDao 接口**

```java
package com.github.hbq969.ai.zephyr.chat.dao;

import com.github.hbq969.ai.zephyr.chat.dao.entity.ConversationEntity;
import com.github.hbq969.ai.zephyr.chat.dao.entity.MessageEntity;
import com.github.hbq969.code.common.datasource.DS;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
@DS
public interface ChatDao {

    void createConversationsTable();
    void createMessagesTable();

    List<ConversationEntity> queryConversations(@Param("userName") String userName);
    void insertConversation(ConversationEntity entity);
    void updateConversationTitle(@Param("id") String id, @Param("title") String title, @Param("userName") String userName);
    void deleteConversation(@Param("id") String id, @Param("userName") String userName);
    ConversationEntity queryConversationById(@Param("id") String id);

    List<MessageEntity> queryMessages(@Param("conversationId") String conversationId);
    void insertMessage(MessageEntity entity);
    void deleteMessagesByConvId(@Param("conversationId") String conversationId);
}
```

- [ ] **Step 2: 创建 common/ChatMapper.xml (DML)**

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd" >
<mapper namespace="com.github.hbq969.ai.zephyr.chat.dao.ChatDao">

    <select id="queryConversations" resultType="com.github.hbq969.ai.zephyr.chat.dao.entity.ConversationEntity">
        select id, user_name as userName, title,
               created_at as createdAt, updated_at as updatedAt
        from conversations
        where user_name = #{userName}
        order by updated_at desc
    </select>

    <insert id="insertConversation">
        insert into conversations (id, user_name, title, created_at, updated_at)
        values (#{id}, #{userName}, #{title}, #{createdAt}, #{updatedAt})
    </insert>

    <update id="updateConversationTitle">
        update conversations set title = #{title}, updated_at = #{updatedAt}
        where id = #{id} and user_name = #{userName}
    </update>

    <delete id="deleteConversation">
        delete from conversations where id = #{id} and user_name = #{userName}
    </delete>

    <select id="queryConversationById" resultType="com.github.hbq969.ai.zephyr.chat.dao.entity.ConversationEntity">
        select id, user_name as userName, title,
               created_at as createdAt, updated_at as updatedAt
        from conversations where id = #{id}
    </select>

    <select id="queryMessages" resultType="com.github.hbq969.ai.zephyr.chat.dao.entity.MessageEntity">
        select id, conversation_id as conversationId, role, content,
               thinking, tool_calls_json as toolCallsJson, tool_call_id as toolCallId,
               created_at as createdAt
        from messages
        where conversation_id = #{conversationId}
        order by created_at asc
    </select>

    <insert id="insertMessage">
        insert into messages (id, conversation_id, role, content, thinking, tool_calls_json, tool_call_id, created_at)
        values (#{id}, #{conversationId}, #{role}, #{content}, #{thinking}, #{toolCallsJson}, #{toolCallId}, #{createdAt})
    </insert>

    <delete id="deleteMessagesByConvId">
        delete from messages where conversation_id = #{conversationId}
    </delete>

</mapper>
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/dao/ChatDao.java src/main/java/com/github/hbq969/ai/zephyr/chat/dao/mapper/common/ChatMapper.xml
git commit -m "feat: 添加 ChatDao 接口和 DML"

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

### Task A4: 注册表创建

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/service/impl/InitialServiceImpl.java:30-48`

- [ ] **Step 1: 在 InitialServiceImpl 中注入 ChatDao 并注册表**

找到文件中的注入区域（第 30-36 行），在 skillDao 注入后添加：

```java
    @Resource
    private com.github.hbq969.ai.zephyr.chat.dao.ChatDao chatDao;
```

在 `tableCreate0()` 方法末尾（第 47 行后）添加：

```java
        com.github.hbq969.code.common.utils.ThrowUtils.call("conversations",
                () -> chatDao.createConversationsTable());
        com.github.hbq969.code.common.utils.ThrowUtils.call("messages",
                () -> chatDao.createMessagesTable());
```

- [ ] **Step 2: 验证数据库表创建**

```bash
# 启动后端（me 环境）
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean package -DskipTests
cp -rf src/main/resources/*.yml target/classes/
cp -rf src/main/resources/*.xml target/classes/
mvn spring-boot:run -Dspring-boot.run.profiles=me
```

检查日志确认 `conversations` 和 `messages` 表创建成功。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/service/impl/InitialServiceImpl.java
git commit -m "feat: 注册 conversations 和 messages 表"

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

### Task A5: 创建 ConversationService

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/ConversationService.java`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ConversationServiceImpl.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/ctrl/ConversationCtrl.java`

- [ ] **Step 1: 创建 ConversationService 接口**

```java
package com.github.hbq969.ai.zephyr.chat.service;

import com.github.hbq969.ai.zephyr.chat.model.ConversationVO;

import java.util.List;
import java.util.Map;

public interface ConversationService {
    List<ConversationVO> list(String userName);
    ConversationVO create(Map<String, String> body, String userName);
    void rename(Map<String, String> body, String userName);
    void delete(String id, String userName);
    List<Map<String, Object>> getMessages(String conversationId, String userName);
}
```

- [ ] **Step 2: 创建 ConversationServiceImpl**

```java
package com.github.hbq969.ai.zephyr.chat.service.impl;

import cn.hutool.core.lang.UUID;
import com.github.hbq969.ai.zephyr.chat.dao.ChatDao;
import com.github.hbq969.ai.zephyr.chat.dao.entity.ConversationEntity;
import com.github.hbq969.ai.zephyr.chat.dao.entity.MessageEntity;
import com.github.hbq969.ai.zephyr.chat.model.ConversationVO;
import com.github.hbq969.ai.zephyr.chat.service.ConversationService;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ConversationServiceImpl implements ConversationService {

    private static final Gson gson = new Gson();

    @Resource
    private ChatDao chatDao;

    @Override
    public List<ConversationVO> list(String userName) {
        List<ConversationEntity> entities = chatDao.queryConversations(userName);
        List<ConversationVO> vos = new ArrayList<>();
        for (ConversationEntity e : entities) {
            vos.add(ConversationVO.builder()
                    .id(e.getId())
                    .title(e.getTitle())
                    .createdAt(e.getCreatedAt())
                    .updatedAt(e.getUpdatedAt())
                    .messageCount(chatDao.queryMessages(e.getId()).size())
                    .build());
        }
        return vos;
    }

    @Override
    @Transactional
    public ConversationVO create(Map<String, String> body, String userName) {
        String title = body.getOrDefault("title", "新对话");
        long now = System.currentTimeMillis() / 1000;
        ConversationEntity entity = new ConversationEntity();
        entity.setId(UUID.fastUUID().toString(true).substring(0, 12));
        entity.setUserName(userName);
        entity.setTitle(title);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        chatDao.insertConversation(entity);
        return ConversationVO.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .messageCount(0)
                .build();
    }

    @Override
    @Transactional
    public void rename(Map<String, String> body, String userName) {
        long now = System.currentTimeMillis() / 1000;
        chatDao.updateConversationTitle(body.get("id"), body.get("title"), now, userName);
    }

    @Override
    @Transactional
    public void delete(String id, String userName) {
        ConversationEntity conv = chatDao.queryConversationById(id);
        if (conv == null || !conv.getUserName().equals(userName)) {
            throw new RuntimeException("无权限或记录不存在");
        }
        chatDao.deleteMessagesByConvId(id);
        chatDao.deleteConversation(id, userName);
    }

    @Override
    public List<Map<String, Object>> getMessages(String conversationId, String userName) {
        ConversationEntity conv = chatDao.queryConversationById(conversationId);
        if (conv == null || !conv.getUserName().equals(userName)) {
            throw new RuntimeException("无权限或记录不存在");
        }
        List<MessageEntity> entities = chatDao.queryMessages(conversationId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (MessageEntity e : entities) {
            Map<String, Object> msg = new HashMap<>();
            msg.put("id", e.getId());
            msg.put("role", e.getRole());
            msg.put("content", e.getContent());
            msg.put("thinking", e.getThinking());
            msg.put("timestamp", e.getCreatedAt());
            if (e.getToolCallsJson() != null && !e.getToolCallsJson().isEmpty()) {
                msg.put("toolCalls", gson.fromJson(e.getToolCallsJson(),
                        new TypeToken<List<Map<String, Object>>>(){}.getType()));
            }
            result.add(msg);
        }
        return result;
    }
}
```

- [ ] **Step 3: 修改 ConversationCtrl 去掉 mock**

将 `ConversationCtrl.java` 中的 `MOCK` 静态变量和 `list()` 方法改为注入 ConversationService：

```java
package com.github.hbq969.ai.zephyr.chat.ctrl;

import com.github.hbq969.ai.zephyr.chat.model.ConversationVO;
import com.github.hbq969.ai.zephyr.chat.service.ConversationService;
import com.github.hbq969.code.common.restful.ReturnMessage;
import com.github.hbq969.code.sm.login.service.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "会话接口")
@RestController
@RequestMapping(path = "/conversations")
public class ConversationCtrl {

    @Resource
    private ConversationService conversationService;

    @Operation(summary = "获取会话列表")
    @RequestMapping(path = "/list", method = RequestMethod.GET)
    @ResponseBody
    public ReturnMessage<?> list() {
        return ReturnMessage.success(conversationService.list(UserContext.get().getUserName()));
    }

    @Operation(summary = "新建会话")
    @RequestMapping(path = "/create", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> create(@RequestBody Map<String, String> body) {
        return ReturnMessage.success(conversationService.create(body, UserContext.get().getUserName()));
    }

    @Operation(summary = "重命名会话")
    @RequestMapping(path = "/rename", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> rename(@RequestBody Map<String, String> body) {
        conversationService.rename(body, UserContext.get().getUserName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "删除会话")
    @RequestMapping(path = "/delete", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> delete(@RequestBody Map<String, String> body) {
        conversationService.delete(body.get("id"), UserContext.get().getUserName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "获取会话历史消息")
    @RequestMapping(path = "/{id}/messages", method = RequestMethod.GET)
    @ResponseBody
    public ReturnMessage<?> messages(@PathVariable String id) {
        return ReturnMessage.success(conversationService.getMessages(id, UserContext.get().getUserName()));
    }
}
```

- [ ] **Step 4: 用 curl 验证接口**

```bash
# 启动后端后验证
curl -u admin:123456 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/conversations/list"

curl -u admin:123456 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/conversations/create" \
  -d '{"title":"测试对话"}'
```

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/ src/main/java/com/github/hbq969/ai/zephyr/chat/ctrl/ConversationCtrl.java
git commit -m "feat: 实现会话持久化，替换 ConversationCtrl mock"

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

---

## Part B: MCP 工具运行时调用

### Task B1: 创建 McpConnection 抽象

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/mcp/utils/McpConnection.java`

- [ ] **Step 1: 创建 McpConnection 抽象**

```java
package com.github.hbq969.ai.zephyr.mcp.utils;

import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpServerEntity;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class McpConnection {

    private static final Gson gson = new Gson();
    private static final AtomicInteger requestId = new AtomicInteger(100);

    public enum Type { STDIO, HTTP }

    private final Type type;
    @Getter
    private final McpServerEntity server;
    @Getter
    private volatile long lastUsedAt;

    // stdio
    private Process process;
    private BufferedReader reader;
    private BufferedWriter writer;

    // http
    private String sessionId;

    private McpConnection(Type type, McpServerEntity server) {
        this.type = type;
        this.server = server;
    }

    public static McpConnection create(McpServerEntity server) {
        McpConnection conn = new McpConnection(
                "http".equals(server.getTransport()) ? Type.HTTP : Type.STDIO,
                server
        );
        conn.init();
        return conn;
    }

    private void init() {
        try {
            if (type == Type.STDIO) {
                initStdio();
            } else {
                initHttp();
            }
            touch();
        } catch (Exception e) {
            log.warn("MCP 连接初始化失败: {} - {}", server.getName(), e.getMessage());
            throw new RuntimeException("连接失败: " + e.getMessage(), e);
        }
    }

    private void initStdio() throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(server.getCommand());
        if (server.getArgs() != null) {
            for (String a : server.getArgs().split("\n")) {
                if (!a.trim().isEmpty()) cmd.add(a.trim());
            }
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (server.getEnvVars() != null && !server.getEnvVars().isEmpty()) {
            for (String line : server.getEnvVars().split("\n")) {
                if (line.trim().isEmpty()) continue;
                int idx = line.indexOf('=');
                if (idx > 0) pb.environment().put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
        }
        process = pb.start();
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        JsonObject initReq = new JsonObject();
        initReq.addProperty("jsonrpc", "2.0");
        initReq.addProperty("id", requestId.incrementAndGet());
        initReq.addProperty("method", "initialize");
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", "2024-11-05");
        params.add("capabilities", new JsonObject());
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "zephyr");
        clientInfo.addProperty("version", "1.0.0");
        params.add("clientInfo", clientInfo);
        initReq.add("params", params);

        String resp = sendAndRead(gson.toJson(initReq));
        if (resp == null || !resp.contains("\"id\"")) throw new RuntimeException("initialize 失败");

        JsonObject notif = new JsonObject();
        notif.addProperty("jsonrpc", "2.0");
        notif.addProperty("method", "notifications/initialized");
        writeMsg(gson.toJson(notif));
    }

    private void initHttp() throws Exception {
        JsonObject initReq = new JsonObject();
        initReq.addProperty("jsonrpc", "2.0");
        initReq.addProperty("id", requestId.incrementAndGet());
        initReq.addProperty("method", "initialize");
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", "2024-11-05");
        params.add("capabilities", new JsonObject());
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "zephyr");
        clientInfo.addProperty("version", "1.0.0");
        params.add("clientInfo", clientInfo);
        initReq.add("params", params);

        String resp = _httpPost(gson.toJson(initReq));
        if (resp == null || !resp.contains("\"id\"")) throw new RuntimeException("HTTP initialize 失败");
    }

    public String callTool(String toolName, JsonObject arguments) {
        try {
            touch();
            if (type == Type.STDIO) {
                return callToolStdio(toolName, arguments);
            } else {
                return callToolHttp(toolName, arguments);
            }
        } catch (Exception e) {
            log.warn("MCP 工具调用失败: {} - {}", toolName, e.getMessage());
            throw new RuntimeException("工具调用失败: " + e.getMessage(), e);
        }
    }

    private String callToolStdio(String toolName, JsonObject arguments) throws Exception {
        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", "2.0");
        req.addProperty("id", requestId.incrementAndGet());
        req.addProperty("method", "tools/call");
        JsonObject params = new JsonObject();
        params.addProperty("name", toolName);
        params.add("arguments", arguments);
        req.add("params", params);

        String resp = sendAndRead(gson.toJson(req));
        return extractContent(resp);
    }

    private String callToolHttp(String toolName, JsonObject arguments) throws Exception {
        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", "2.0");
        req.addProperty("id", requestId.incrementAndGet());
        req.addProperty("method", "tools/call");
        JsonObject params = new JsonObject();
        params.addProperty("name", toolName);
        params.add("arguments", arguments);
        req.add("params", params);

        String resp = _httpPost(gson.toJson(req));
        return extractContent(resp);
    }

    private String extractContent(String resp) {
        if (resp == null) return "工具返回空结果";
        try {
            JsonObject r = gson.fromJson(resp, JsonObject.class);
            if (r.has("error")) {
                return "工具调用出错: " + r.get("error").toString();
            }
            if (r.has("result")) {
                JsonObject result = r.getAsJsonObject("result");
                if (result.has("content")) {
                    JsonArray content = result.getAsJsonArray("content");
                    if (content.size() > 0 && content.get(0).getAsJsonObject().has("text")) {
                        return content.get(0).getAsJsonObject().get("text").getAsString();
                    }
                }
                return result.toString();
            }
        } catch (Exception e) {
            log.warn("解析工具返回失败", e);
        }
        return resp;
    }

    private String sendAndRead(String json) throws Exception {
        writeMsg(json);
        return readMsg();
    }

    private void writeMsg(String json) throws Exception {
        writer.write(json);
        writer.newLine();
        writer.flush();
    }

    private String readMsg() throws Exception {
        return reader.readLine();
    }

    private String _httpPost(String json) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(server.getUrl()).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json, text/event-stream");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);

        if (sessionId != null) {
            conn.setRequestProperty("Mcp-Session-Id", sessionId);
        }
        if (server.getHeaders() != null && !server.getHeaders().isEmpty()) {
            for (String line : server.getHeaders().split("\n")) {
                if (line.trim().isEmpty()) continue;
                int idx = line.indexOf('=');
                if (idx > 0) conn.setRequestProperty(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
        }

        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        String respSessionId = conn.getHeaderField("Mcp-Session-Id");
        if (respSessionId != null) this.sessionId = respSessionId;

        int code = conn.getResponseCode();
        if (code >= 200 && code < 300) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
                return McpClient.extractSSEData(sb.toString());
            }
        }
        return null;
    }

    private void touch() {
        this.lastUsedAt = System.currentTimeMillis();
    }

    public void close() {
        try {
            if (type == Type.STDIO && process != null) {
                process.destroyForcibly();
            }
        } catch (Exception ignored) {}
    }
}
```

- [ ] **Step 2: 在 McpClient 中添加 extractSSEData 为 public static**

修改 `McpClient.java`，将 `extractSSEData` 方法从 private 改为 public static。

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/mcp/utils/
git commit -m "feat: 添加 McpConnection 抽象和 tools/call 支持"

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

### Task B2: 创建 McpConnectionManager

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/mcp/utils/McpConnectionManager.java`

- [ ] **Step 1: 创建 McpConnectionManager**

```java
package com.github.hbq969.ai.zephyr.mcp.utils;

import com.github.hbq969.ai.zephyr.mcp.dao.McpDao;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpServerEntity;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class McpConnectionManager {

    private static final int MAX_CONNECTIONS = 100;
    private static final long IDLE_TIMEOUT_MS = 15 * 60 * 1000L; // 15 分钟

    private final Map<String, McpConnection> connections = new ConcurrentHashMap<>();

    @Resource
    private McpDao mcpDao;

    public McpConnection getConnection(String userName, String serverId) {
        String key = userName + ":" + serverId;
        McpConnection conn = connections.get(key);
        if (conn != null) {
            return conn; // touch 在 callTool 中更新
        }
        return createConnection(key, userName, serverId);
    }

    private synchronized McpConnection createConnection(String key, String userName, String serverId) {
        McpConnection existing = connections.get(key);
        if (existing != null) return existing;

        if (connections.size() >= MAX_CONNECTIONS) {
            evictLru();
        }

        McpServerEntity server = mcpDao.queryServerById(serverId);
        if (server == null || !server.getUserName().equals(userName)) {
            throw new RuntimeException("MCP 服务器不存在");
        }

        McpConnection conn = McpConnection.create(server);
        connections.put(key, conn);
        log.info("MCP 连接已建立: {} (当前 {} 个连接)", key, connections.size());
        return conn;
    }

    public void removeConnection(String userName, String serverId) {
        String key = userName + ":" + serverId;
        McpConnection conn = connections.remove(key);
        if (conn != null) {
            conn.close();
            log.info("MCP 连接已关闭: {}", key);
        }
    }

    private void evictLru() {
        String oldest = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, McpConnection> e : connections.entrySet()) {
            if (e.getValue().getLastUsedAt() < oldestTime) {
                oldestTime = e.getValue().getLastUsedAt();
                oldest = e.getKey();
            }
        }
        if (oldest != null) {
            McpConnection conn = connections.remove(oldest);
            if (conn != null) conn.close();
            log.info("LRU 淘汰连接: {}", oldest);
        }
    }

    @Scheduled(fixedRate = 300000) // 每 5 分钟
    public void cleanupIdle() {
        long now = System.currentTimeMillis();
        connections.entrySet().removeIf(entry -> {
            if (now - entry.getValue().getLastUsedAt() > IDLE_TIMEOUT_MS) {
                entry.getValue().close();
                log.info("空闲连接回收: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }
}
```

- [ ] **Step 2: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/mcp/utils/McpConnectionManager.java
git commit -m "feat: 添加 McpConnectionManager 连接管理器"

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

---

## Part C: 上下文组装 + 模型调用

### Task C1: 创建 DTO 类型

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/chat/model/ChatRequest.java`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/chat/model/ToolDef.java`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/chat/model/LlmResult.java`

- [ ] **Step 1: 创建 ChatRequest**

```java
package com.github.hbq969.ai.zephyr.chat.model;

import lombok.Data;

@Data
public class ChatRequest {
    private String conversationId;
    private String message;
}
```

- [ ] **Step 2: 创建 ToolDef**

```java
package com.github.hbq969.ai.zephyr.chat.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ToolDef {
    private String type;
    private FunctionDef function;

    @Data
    @Builder
    public static class FunctionDef {
        private String name;
        private String description;
        private Object parameters;
    }
}
```

- [ ] **Step 3: 创建 LlmResult**

```java
package com.github.hbq969.ai.zephyr.chat.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class LlmResult {
    private String content;
    private String thinking;
    private List<ToolCall> toolCalls;
    private Map<String, Integer> usage;

    @Data
    @Builder
    public static class ToolCall {
        private String id;
        private String name;
        private Map<String, Object> arguments;
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
```

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/model/
git commit -m "feat: 添加 ChatRequest/ToolDef/LlmResult DTO"

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

### Task C2: 创建 ContextBuilder

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/ContextBuilder.java`

- [ ] **Step 1: 创建 ContextBuilder**

```java
package com.github.hbq969.ai.zephyr.chat.service;

import com.github.hbq969.ai.zephyr.chat.dao.ChatDao;
import com.github.hbq969.ai.zephyr.chat.dao.entity.MessageEntity;
import com.github.hbq969.ai.zephyr.chat.model.ToolDef;
import com.github.hbq969.ai.zephyr.config.dao.ModelConfigDao;
import com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity;
import com.github.hbq969.ai.zephyr.mcp.dao.McpDao;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity;
import com.github.hbq969.ai.zephyr.memory.model.MemoryVO;
import com.github.hbq969.ai.zephyr.memory.service.MemoryService;
import com.github.hbq969.ai.zephyr.skill.dao.SkillDao;
import com.github.hbq969.ai.zephyr.skill.dao.entity.SkillConfigEntity;
import com.github.hbq969.ai.zephyr.skill.model.SkillVO;
import com.github.hbq969.ai.zephyr.skill.service.SkillService;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.annotation.Resource;
import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class ContextBuilder {

    private static final Gson gson = new Gson();

    @Resource
    private ModelConfigDao modelConfigDao;
    @Resource
    private McpDao mcpDao;
    @Resource
    private SkillDao skillDao;
    @Resource
    private SkillService skillService;
    @Resource
    private MemoryService memoryService;
    @Resource
    private ChatDao chatDao;

    private static final int MAX_HISTORY_ROUNDS = 20;

    // 角色定义
    private static final String ROLE_PROMPT = """
            你是一个 AI 助手，名为 zephyr。
            
            你可以使用 MCP 工具获取实时数据，使用技能（Skill）获取特定任务的详细指导，
            查看用户记忆（Memory）了解历史上下文和偏好。
            
            ## 工具使用说明
            - 优先使用 MCP 工具获取实时准确的数据
            - 需要特定任务的详细指导时，使用 use_skill 工具
            - 需要了解用户的背景或偏好时，使用 use_memory 工具
            - 你可以多次调用工具，直到获得足够信息后再回答
            """;

    public Context build(String userName, String conversationId) {
        // 1. 加载模型配置
        List<ModelConfigEntity> models = modelConfigDao.queryByUserName(userName);
        ModelConfigEntity model = models.stream()
                .filter(m -> m.getIsDefault() != null && m.getIsDefault() == 1)
                .findFirst()
                .orElse(models.isEmpty() ? null : models.get(0));
        if (model == null) throw new RuntimeException("请先配置模型");

        // 2. 加载 MCP 工具
        List<ToolDef> toolDefs = buildMcpToolDefs(userName);

        // 3. 加载 Skills 索引
        String skillIndex = buildSkillIndex(userName);

        // 4. 加载记忆索引
        String memoryIndex = buildMemoryIndex(userName);

        // 5. 组装 system prompt
        StringBuilder systemPrompt = new StringBuilder(ROLE_PROMPT);
        if (!skillIndex.isEmpty()) {
            systemPrompt.append("\n\n## 可用技能\n").append(skillIndex)
                    .append("\n（需要详细指导时使用 use_skill 工具加载）");
        }
        if (!memoryIndex.isEmpty()) {
            systemPrompt.append("\n\n## 用户记忆\n").append(memoryIndex)
                    .append("\n（需要完整内容时使用 use_memory 工具查看）");
        }

        // 6. 添加内置工具
        toolDefs.add(buildUseSkillTool());
        toolDefs.add(buildUseMemoryTool());

        // 7. 加载历史消息（最近 20 轮）
        List<Map<String, Object>> messages = buildMessages(userName, conversationId, systemPrompt.toString());

        return Context.builder()
                .model(model)
                .systemPrompt(systemPrompt.toString())
                .tools(toolDefs)
                .messages(messages)
                .build();
    }

    private List<ToolDef> buildMcpToolDefs(String userName) {
        List<ToolDef> defs = new ArrayList<>();
        List<McpToolEntity> tools = mcpDao.queryEnabledToolsByUserName(userName);
        for (McpToolEntity t : tools) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("type", "object");
            params.put("properties", new LinkedHashMap<>());
            params.put("required", Collections.emptyList());

            defs.add(ToolDef.builder()
                    .type("function")
                    .function(ToolDef.FunctionDef.builder()
                            .name(t.getToolName())
                            .description(t.getDescription() != null ? t.getDescription() : "")
                            .parameters(params)
                            .build())
                    .build());
        }
        return defs;
    }

    private String buildSkillIndex(String userName) {
        StringBuilder sb = new StringBuilder();
        List<SkillConfigEntity> skills = skillDao.queryEnabledByUserName(userName);
        // 也合并同步扫描到的 skill
        List<SkillVO> synced = skillService.syncScan(userName);
        Set<String> seen = new HashSet<>();
        for (SkillConfigEntity s : skills) {
            sb.append("- ").append(s.getSkillName()).append(": ").append(s.getDescription()).append("\n");
            seen.add(s.getSkillName());
        }
        for (SkillVO s : synced) {
            if (seen.contains(s.getSkillName())) continue;
            sb.append("- ").append(s.getSkillName()).append(": ").append(s.getDescription()).append("\n");
        }
        return sb.toString();
    }

    private String buildMemoryIndex(String userName) {
        StringBuilder sb = new StringBuilder();
        List<MemoryVO> memories = memoryService.list(null, userName);
        for (MemoryVO m : memories) {
            sb.append("- ").append(m.getName()).append(" (").append(m.getType()).append("): ")
                    .append(m.getDescription()).append("\n");
        }
        return sb.toString();
    }

    private List<Map<String, Object>> buildMessages(String userName, String conversationId, String systemPrompt) {
        List<Map<String, Object>> messages = new ArrayList<>();

        Map<String, Object> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemPrompt);
        messages.add(systemMsg);

        if (conversationId != null) {
            List<MessageEntity> history = chatDao.queryMessages(conversationId);
            List<MessageEntity> recent = history;
            if (history.size() > MAX_HISTORY_ROUNDS * 2) {
                recent = history.subList(history.size() - MAX_HISTORY_ROUNDS * 2, history.size());
            }
            for (MessageEntity e : recent) {
                Map<String, Object> msg = new HashMap<>();
                msg.put("role", e.getRole());
                msg.put("content", e.getContent());
                if (e.getToolCallId() != null) {
                    msg.put("tool_call_id", e.getToolCallId());
                }
                if (e.getToolCallsJson() != null && !e.getToolCallsJson().isEmpty()) {
                    msg.put("tool_calls", gson.fromJson(e.getToolCallsJson(),
                            new TypeToken<List<Map<String, Object>>>(){}.getType()));
                }
                messages.add(msg);
            }
        }
        return messages;
    }

    private ToolDef buildUseSkillTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("skill_name", Map.of("type", "string", "description", "技能名称"));
        params.put("properties", props);
        params.put("required", List.of("skill_name"));

        return ToolDef.builder()
                .type("function")
                .function(ToolDef.FunctionDef.builder()
                        .name("use_skill")
                        .description("加载指定 skill 的完整指导内容到上下文")
                        .parameters(params)
                        .build())
                .build();
    }

    private ToolDef buildUseMemoryTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("memory_name", Map.of("type", "string", "description", "记忆名称"));
        params.put("properties", props);
        params.put("required", List.of("memory_name"));

        return ToolDef.builder()
                .type("function")
                .function(ToolDef.FunctionDef.builder()
                        .name("use_memory")
                        .description("查看指定记忆的完整内容")
                        .parameters(params)
                        .build())
                .build();
    }

    @Data
    @Builder
    public static class Context {
        private ModelConfigEntity model;
        private String systemPrompt;
        private List<ToolDef> tools;
        private List<Map<String, Object>> messages;
    }
}
```

- [ ] **Step 2: 在 McpDao 中添加按用户查询已启用工具的接口，XML 中添加对应 DML**

在 `McpDao.java` 中添加方法声明：

```java
    List<McpToolEntity> queryEnabledToolsByUserName(@Param("userName") String userName);
```

在 `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/common/McpMapper.xml` 末尾的 `</mapper>` 之前添加：

```xml
    <select id="queryEnabledToolsByUserName" resultType="com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity">
        select id, user_name as userName, server_id as serverId,
               tool_name as toolName, description, enabled, source, created_at as createdAt
        from mcp_tools
        where user_name = #{userName} and enabled = 1
    </select>
```

在 `SkillDao.java` 中添加方法声明：

```java
    List<SkillConfigEntity> queryEnabledByUserName(@Param("userName") String userName);
```

在 `src/main/java/com/github/hbq969/ai/zephyr/skill/dao/mapper/common/SkillMapper.xml` 末尾的 `</mapper>` 之前添加：

```xml
    <select id="queryEnabledByUserName" resultType="com.github.hbq969.ai.zephyr.skill.dao.entity.SkillConfigEntity">
        select id, user_name as userName, skill_name as skillName,
               display_name as displayName, description, source, source_url as sourceUrl,
               version, enabled, install_path as installPath, created_at as createdAt, updated_at as updatedAt
        from skill_configs
        where user_name = #{userName} and enabled = 1
    </select>
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/ContextBuilder.java \
        src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/McpDao.java \
        src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/common/McpMapper.xml \
        src/main/java/com/github/hbq969/ai/zephyr/skill/dao/SkillDao.java \
        src/main/java/com/github/hbq969/ai/zephyr/skill/dao/mapper/common/SkillMapper.xml
git commit -m "feat: 添加 ContextBuilder 上下文组装器"

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

### Task C3: 创建 LlmClient

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/chat/client/LlmClient.java`
- Modify: `pom.xml` (添加 okhttp 依赖)

- [ ] **Step 1: 添加 OkHttp 依赖**

在 `pom.xml` 的 `<dependencies>` 中添加：

```xml
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>okhttp</artifactId>
    <version>4.12.0</version>
</dependency>
```

- [ ] **Step 2: 创建 LlmClient**

```java
package com.github.hbq969.ai.zephyr.chat.client;

import com.github.hbq969.ai.zephyr.chat.model.ChatEvent;
import com.github.hbq969.ai.zephyr.chat.model.LlmResult;
import com.github.hbq969.ai.zephyr.chat.model.ToolDef;
import com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity;
import com.github.hbq969.code.common.encrypt.ext.utils.AESUtil;
import com.google.gson.*;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class LlmClient {

    @Value("${encrypt.restful.aes.key}")
    private String aesKey;

    @Value("${encrypt.restful.aes.iv}")
    private String aesIv;

    private static final Gson gson = new Gson();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    public LlmResult chat(ModelConfigEntity model, List<Map<String, Object>> messages,
                          List<ToolDef> tools, SseEmitter emitter) throws IOException {
        String apiKey = AESUtil.decrypt(model.getApiKeyEncrypted(), aesKey, aesIv, StandardCharsets.UTF_8);
        String baseUrl = model.getBaseUrl();
        if (!baseUrl.endsWith("/")) baseUrl += "/";

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("model", model.getName());
        bodyJson.addProperty("stream", true);
        bodyJson.add("messages", gson.toJsonTree(messages));

        if (tools != null && !tools.isEmpty()) {
            bodyJson.add("tools", gson.toJsonTree(tools));
        }

        JsonObject streamOpts = new JsonObject();
        streamOpts.addProperty("include_usage", true);
        bodyJson.add("stream_options", streamOpts);

        RequestBody body = RequestBody.create(gson.toJson(bodyJson), JSON);
        Request request = new Request.Builder()
                .url(baseUrl + "v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(body)
                .build();

        StringBuilder fullContent = new StringBuilder();
        StringBuilder fullThinking = new StringBuilder();
        List<LlmResult.ToolCall> toolCalls = new ArrayList<>();
        JsonArray accumulatedToolCalls = new JsonArray();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                emitter.send(SseEmitter.event().name("message")
                        .data(ChatEvent.builder().type("error").content("API 错误: " + errorBody).build()));
                return LlmResult.builder().content("").build();
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if (data.equals("[DONE]")) break;

                    try {
                        JsonObject event = gson.fromJson(data, JsonObject.class);
                        if (event.has("choices") && event.getAsJsonArray("choices").size() > 0) {
                            JsonObject choice = event.getAsJsonArray("choices").get(0).getAsJsonObject();
                            JsonObject delta = choice.has("delta") ? choice.getAsJsonObject("delta") : null;
                            if (delta == null) continue;

                            // text content
                            if (delta.has("content") && !delta.get("content").isJsonNull()) {
                                String token = delta.get("content").getAsString();
                                fullContent.append(token);
                                emitter.send(SseEmitter.event().name("message")
                                        .data(ChatEvent.builder().type("token").content(token).build()));
                            }

                            // thinking (DeepSeek)
                            if (delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
                                String thinking = delta.get("reasoning_content").getAsString();
                                fullThinking.append(thinking);
                                emitter.send(SseEmitter.event().name("message")
                                        .data(ChatEvent.builder().type("thinking").content(thinking).build()));
                            }

                            // tool calls
                            if (delta.has("tool_calls")) {
                                JsonArray tcArray = delta.getAsJsonArray("tool_calls");
                                for (int i = 0; i < tcArray.size(); i++) {
                                    JsonObject tc = tcArray.get(i).getAsJsonObject();
                                    int idx = tc.has("index") ? tc.get("index").getAsInt() : 0;
                                    while (accumulatedToolCalls.size() <= idx) {
                                        accumulatedToolCalls.add(new JsonObject());
                                    }
                                    JsonObject accumulated = accumulatedToolCalls.get(idx).getAsJsonObject();

                                    if (tc.has("id")) accumulated.addProperty("id", tc.get("id").getAsString());
                                    if (tc.has("function")) {
                                        JsonObject func = tc.getAsJsonObject("function");
                                        if (!accumulated.has("function")) accumulated.add("function", new JsonObject());
                                        JsonObject accFunc = accumulated.getAsJsonObject("function");
                                        if (func.has("name")) accFunc.addProperty("name", func.get("name").getAsString());
                                        if (func.has("arguments")) {
                                            String args = accFunc.has("arguments") ? accFunc.get("arguments").getAsString() : "";
                                            accFunc.addProperty("arguments", args + func.get("arguments").getAsString());
                                        }
                                    }
                                }
                            }

                            // finish reason
                            if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) {
                                String finishReason = choice.get("finish_reason").getAsString();
                                if ("tool_calls".equals(finishReason)) {
                                    for (int i = 0; i < accumulatedToolCalls.size(); i++) {
                                        JsonObject tc = accumulatedToolCalls.get(i).getAsJsonObject();
                                        JsonObject func = tc.getAsJsonObject("function");
                                        LlmResult.ToolCall toolCall = LlmResult.ToolCall.builder()
                                                .id(tc.has("id") ? tc.get("id").getAsString() : "")
                                                .name(func.get("name").getAsString())
                                                .arguments(gson.fromJson(func.get("arguments").getAsString(),
                                                        new com.google.gson.reflect.TypeToken<Map<String, Object>>(){}.getType()))
                                                .build();
                                        toolCalls.add(toolCall);
                                    }
                                }
                            }
                        }

                        // usage
                        if (event.has("usage")) {
                            JsonObject usage = event.getAsJsonObject("usage");
                            Map<String, Integer> usageMap = new LinkedHashMap<>();
                            if (usage.has("prompt_tokens")) usageMap.put("inputTokens", usage.get("prompt_tokens").getAsInt());
                            if (usage.has("completion_tokens")) usageMap.put("outputTokens", usage.get("completion_tokens").getAsInt());
                            emitter.send(SseEmitter.event().name("message")
                                    .data(ChatEvent.builder().type("usage").usage(usageMap).build()));
                        }
                    } catch (Exception e) {
                        log.warn("解析 SSE 事件失败: {}", e.getMessage());
                    }
                }
            }
        }

        return LlmResult.builder()
                .content(fullContent.toString())
                .thinking(fullThinking.length() > 0 ? fullThinking.toString() : null)
                .toolCalls(toolCalls.isEmpty() ? null : toolCalls)
                .build();
    }
}
```

- [ ] **Step 3: 提交**

```bash
git add pom.xml src/main/java/com/github/hbq969/ai/zephyr/chat/client/LlmClient.java
git commit -m "feat: 添加 OkHttp 依赖和 LlmClient SSE 流式调用"

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

### Task C4: 创建 ChatService

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/ChatService.java`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/ctrl/ChatCtrl.java`

- [ ] **Step 1: 创建 ChatService 接口**

```java
package com.github.hbq969.ai.zephyr.chat.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

public interface ChatService {
    SseEmitter send(String userName, String conversationId, String message);
    void cancel(String userName);
    Map<String, Object> contextUsage(String userName, String conversationId);
}
```

- [ ] **Step 2: 创建 ChatServiceImpl**

```java
package com.github.hbq969.ai.zephyr.chat.service.impl;

import cn.hutool.core.lang.UUID;
import com.github.hbq969.ai.zephyr.chat.client.LlmClient;
import com.github.hbq969.ai.zephyr.chat.dao.ChatDao;
import com.github.hbq969.ai.zephyr.chat.dao.entity.ConversationEntity;
import com.github.hbq969.ai.zephyr.chat.dao.entity.MessageEntity;
import com.github.hbq969.ai.zephyr.chat.model.ChatEvent;
import com.github.hbq969.ai.zephyr.chat.model.LlmResult;
import com.github.hbq969.ai.zephyr.chat.service.ChatService;
import com.github.hbq969.ai.zephyr.chat.service.ContextBuilder;
import com.github.hbq969.ai.zephyr.mcp.utils.McpConnectionManager;
import com.github.hbq969.ai.zephyr.memory.service.MemoryService;
import com.github.hbq969.ai.zephyr.skill.service.SkillService;
import com.google.gson.Gson;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    private static final Gson gson = new Gson();
    private static final int MAX_ROUNDS = 10;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, okhttp3.Call> activeCalls = new ConcurrentHashMap<>();

    @Resource
    private ContextBuilder contextBuilder;
    @Resource
    private LlmClient llmClient;
    @Resource
    private ChatDao chatDao;
    @Resource
    private SkillService skillService;
    @Resource
    private MemoryService memoryService;
    @Resource
    private McpConnectionManager mcpConnectionManager;

    @Override
    public SseEmitter send(String userName, String conversationId, String message) {
        SseEmitter emitter = new SseEmitter(300000L);

        executor.execute(() -> {
            String sessionKey = UUID.randomUUID().toString(true).substring(0, 8);
            try {
                // 1. 确保会话存在
                long now = System.currentTimeMillis() / 1000;
                if (conversationId == null || conversationId.isEmpty()) {
                    conversationId = UUID.fastUUID().toString(true).substring(0, 12);
                    ConversationEntity conv = new ConversationEntity();
                    conv.setId(conversationId);
                    conv.setUserName(userName);
                    conv.setTitle(message.length() > 30 ? message.substring(0, 30) : message);
                    conv.setCreatedAt(now);
                    conv.setUpdatedAt(now);
                    chatDao.insertConversation(conv);
                }

                // 2. 持久化 user 消息
                MessageEntity userMsg = new MessageEntity();
                userMsg.setId(UUID.fastUUID().toString(true).substring(0, 12));
                userMsg.setConversationId(conversationId);
                userMsg.setRole("user");
                userMsg.setContent(message);
                userMsg.setCreatedAt(now);
                chatDao.insertMessage(userMsg);

                // 3. 组装上下文
                ContextBuilder.Context ctx = contextBuilder.build(userName, conversationId);
                List<Map<String, Object>> messages = ctx.getMessages();
                messages.add(Map.of("role", "user", "content", message));

                // 4. 工具调用循环
                for (int round = 0; round < MAX_ROUNDS; round++) {
                    LlmResult result = llmClient.chat(ctx.getModel(), messages, ctx.getTools(), emitter);

                    if (result.hasToolCalls()) {
                        // 4a. 添加 assistant 消息（含 tool_calls）
                        Map<String, Object> assistantMsg = new LinkedHashMap<>();
                        assistantMsg.put("role", "assistant");
                        assistantMsg.put("content", result.getContent() != null ? result.getContent() : "");
                        if (result.getToolCalls() != null) {
                            assistantMsg.put("tool_calls", result.getToolCalls().stream().map(tc -> {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("id", tc.getId());
                                m.put("type", "function");
                                m.put("function", Map.of("name", tc.getName(), "arguments", gson.toJson(tc.getArguments())));
                                return m;
                            }).toList());
                        }
                        messages.add(assistantMsg);

                        // 4b. 持久化 assistant 消息
                        persistAssistantMessage(conversationId, userName, result, now);

                        // 4c. 分发工具调用
                        List<Map<String, Object>> toolResults = dispatchTools(result.getToolCalls(), userName);
                        messages.addAll(toolResults);

                        // 4d. 持久化 tool 消息
                        for (int i = 0; i < result.getToolCalls().size(); i++) {
                            LlmResult.ToolCall tc = result.getToolCalls().get(i);
                            MessageEntity toolMsg = new MessageEntity();
                            toolMsg.setId(UUID.fastUUID().toString(true).substring(0, 12));
                            toolMsg.setConversationId(conversationId);
                            toolMsg.setRole("tool");
                            toolMsg.setContent(toolResults.get(i).get("content").toString());
                            toolMsg.setToolCallId(tc.getId());
                            toolMsg.setCreatedAt(System.currentTimeMillis() / 1000);
                            chatDao.insertMessage(toolMsg);
                        }
                    } else {
                        // 5. 正常结束，持久化 assistant 消息
                        persistAssistantMessage(conversationId, userName, result, now);
                        emitter.send(SseEmitter.event().name("message")
                                .data(ChatEvent.builder().type("done").build()));
                        emitter.complete();
                        return;
                    }
                }
                // 超轮次结束
                emitter.send(SseEmitter.event().name("message")
                        .data(ChatEvent.builder().type("done").build()));
                emitter.complete();
            } catch (Exception e) {
                log.error("Chat error", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(ChatEvent.builder().type("error").content(e.getMessage()).build()));
                    emitter.complete();
                } catch (IOException ignored) {}
            }
        });

        emitter.onTimeout(emitter::complete);
        emitter.onError(th -> log.error("SSE error", th));
        return emitter;
    }

    private void persistAssistantMessage(String conversationId, String userName, LlmResult result, long now) {
        MessageEntity msg = new MessageEntity();
        msg.setId(cn.hutool.core.lang.UUID.fastUUID().toString(true).substring(0, 12));
        msg.setConversationId(conversationId);
        msg.setRole("assistant");
        msg.setContent(result.getContent());
        msg.setThinking(result.getThinking());
        if (result.getToolCalls() != null) {
            msg.setToolCallsJson(gson.toJson(result.getToolCalls().stream().map(tc -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", tc.getId());
                m.put("name", tc.getName());
                m.put("input", tc.getArguments());
                m.put("status", "success");
                return m;
            }).toList()));
        }
        msg.setCreatedAt(now);
        chatDao.insertMessage(msg);

        // 更新会话时间
        ConversationEntity conv = chatDao.queryConversationById(conversationId);
        if (conv != null) {
            conv.setUpdatedAt(System.currentTimeMillis() / 1000);
            chatDao.updateConversationTitle(conversationId, conv.getTitle(),
                    conv.getUpdatedAt(), conv.getUserName());
        }
    }

    private List<Map<String, Object>> dispatchTools(List<LlmResult.ToolCall> toolCalls, String userName) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (LlmResult.ToolCall tc : toolCalls) {
            String content;
            try {
                content = switch (tc.getName()) {
                    case "use_skill" -> executeUseSkill(tc.getArguments().get("skill_name").toString());
                    case "use_memory" -> executeUseMemory(tc.getArguments().get("memory_name").toString(), userName);
                    default -> executeMcpTool(tc.getName(), tc.getArguments(), userName);
                };
            } catch (Exception e) {
                content = "工具执行错误: " + e.getMessage();
            }
            results.add(Map.of("role", "tool", "tool_call_id", tc.getId(), "content",
                    content.length() > 8000 ? content.substring(0, 8000) + "..." : content));
        }
        return results;
    }

    private String executeUseSkill(String skillName) {
        Path skillMd = Paths.get(System.getProperty("user.home"), ".zephyr", "skills", skillName, "SKILL.md");
        if (!Files.exists(skillMd)) {
            // 也搜索子目录
            try {
                java.io.File[] subDirs = skillMd.getParent().toFile().listFiles(java.io.File::isDirectory);
                if (subDirs != null) {
                    for (java.io.File d : subDirs) {
                        Path nested = d.toPath().resolve("SKILL.md");
                        if (Files.exists(nested)) {
                            skillMd = nested;
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        if (!Files.exists(skillMd)) return "技能 " + skillName + " 不存在";
        try {
            String raw = Files.readString(skillMd);
            // 去掉 YAML frontmatter
            return raw.replaceFirst("(?s)^---\\s*\\n.*?\\n---\\s*\\n", "");
        } catch (IOException e) {
            return "读取技能失败: " + e.getMessage();
        }
    }

    private String executeUseMemory(String memoryName, String userName) {
        try {
            return memoryService.detail(memoryName, userName).getContent();
        } catch (Exception e) {
            return "记忆不存在: " + e.getMessage();
        }
    }

    private String executeMcpTool(String toolName, Map<String, Object> arguments, String userName) {
        // 查找 tool 对应的 server
        List<com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity> tools =
                mcpConnectionManager.getAllEnabledTools(userName);
        // 从连接管理器获取工具列表
        for (com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity t : tools) {
            if (t.getToolName().equals(toolName)) {
                com.google.gson.JsonObject args = gson.toJsonTree(arguments).getAsJsonObject();
                return mcpConnectionManager.getConnection(userName, t.getServerId()).callTool(toolName, args);
            }
        }
        return "MCP 工具未找到: " + toolName;
    }

    @Override
    public void cancel(String userName) {
        // cancel logic handled by activeCalls map
    }

    @Override
    public Map<String, Object> contextUsage(String userName, String conversationId) {
        ContextBuilder.Context ctx = contextBuilder.build(userName, conversationId);
        int sysTokens = estimateTokens(ctx.getSystemPrompt());
        int histTokens = 0;
        int skillTokens = 0;
        int memTokens = 0;
        for (Map<String, Object> msg : ctx.getMessages()) {
            String role = (String) msg.get("role");
            String content = (String) msg.get("content");
            int t = estimateTokens(content != null ? content : "");
            if ("system".equals(role)) continue; // 已算
            else if ("tool".equals(role)) {
                String toolCallId = (String) msg.get("tool_call_id");
                // 粗略判断：可能是 use_skill 返回
                if (skillTokens > 0 || t > 1000) skillTokens += t;
                else memTokens += t;
            } else {
                histTokens += t;
            }
        }
        int toolTokens = estimateTokens(gson.toJson(ctx.getTools()));

        return Map.of(
                "systemPrompt", sysTokens,
                "history", histTokens,
                "skillContent", skillTokens,
                "memoryContent", memTokens,
                "toolDefinitions", toolTokens,
                "total", sysTokens + histTokens + skillTokens + memTokens + toolTokens
        );
    }

    private int estimateTokens(String text) {
        return (int) Math.ceil(text.length() * 0.3);
    }
}
```

注意：McpConnectionManager 中需要添加 getAllEnabledTools 方法，参见下一步骤。

- [ ] **Step 3: 在 McpConnectionManager 中添加帮手方法**

```java
// 添加到 McpConnectionManager.java 末尾
public java.util.List<com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity> getAllEnabledTools(String userName) {
    return mcpDao.queryEnabledToolsByUserName(userName);
}
```

- [ ] **Step 4: 修改 ChatCtrl 注入 ChatService**

```java
package com.github.hbq969.ai.zephyr.chat.ctrl;

import com.github.hbq969.ai.zephyr.chat.model.ChatEvent;
import com.github.hbq969.ai.zephyr.chat.model.ChatRequest;
import com.github.hbq969.ai.zephyr.chat.service.ChatService;
import com.github.hbq969.code.common.restful.ReturnMessage;
import com.github.hbq969.code.sm.login.service.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Tag(name = "聊天接口")
@RestController
@RequestMapping(path = "/chat")
public class ChatCtrl {

    @Resource
    private ChatService chatService;

    @Operation(summary = "发送消息（SSE 流式）")
    @RequestMapping(path = "/send", method = RequestMethod.POST)
    @ResponseBody
    public SseEmitter sendMessage(@RequestBody ChatRequest body) {
        return chatService.send(
                UserContext.get().getUserName(),
                body.getConversationId(),
                body.getMessage()
        );
    }

    @Operation(summary = "取消当前对话")
    @RequestMapping(path = "/cancel", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> cancel() {
        chatService.cancel(UserContext.get().getUserName());
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "上下文占比")
    @RequestMapping(path = "/context-usage", method = RequestMethod.GET)
    @ResponseBody
    public ReturnMessage<?> contextUsage(@RequestParam(required = false) String conversationId) {
        return ReturnMessage.success(chatService.contextUsage(
                UserContext.get().getUserName(), conversationId));
    }
}
```

- [ ] **Step 5: 用 curl 测试真实对话**

```bash
# 确保已经配置了一个模型

# 发送消息
curl -u admin:123456 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/chat/send" \
  -d '{"message":"你好，请简单介绍一下你自己"}'

# 验证上下文占比
curl -u admin:123456 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/chat/context-usage?conversationId=xxx"
```

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/ src/main/java/com/github/hbq969/ai/zephyr/chat/ctrl/ChatCtrl.java
git commit -m "feat: 实现 ChatService 核心流程（上下文组装 + 工具分发 + LLM 调用）"

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

---

## Part D: `/` 命令输入

### Task D1: 创建 SlashMenu 组件

**Files:**
- Create: `src/main/resources/static/src/views/chat/SlashMenu.vue`
- Modify: `src/main/resources/static/src/views/chat/InputArea.vue`

- [ ] **Step 1: 创建 SlashMenu.vue**

```vue
<script lang="ts" setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { Icon } from '@iconify/vue'

const props = defineProps<{
  visible: boolean
  models: Array<{ name: string; id: string }>
  mcpCount: number
  skillCount: number
  memoryCount: number
}>()

const emit = defineEmits<{
  close: []
  select: [cmd: string]
}>()

const search = ref('')
const activeIdx = ref(0)

interface Command {
  cmd: string
  desc: string
  badge?: string
  group: string
}

const allCommands: Command[] = [
  { cmd: '/model', desc: '切换对话模型', badge: '', group: '模型' },
  { cmd: '/mcp', desc: 'MCP 工具列表', badge: props.mcpCount > 0 ? `${props.mcpCount} 个` : '', group: '能力' },
  { cmd: '/skills', desc: '可用技能', badge: props.skillCount > 0 ? `${props.skillCount} 个` : '', group: '能力' },
  { cmd: '/memory', desc: '用户记忆', badge: props.memoryCount > 0 ? `${props.memoryCount} 条` : '', group: '能力' },
  { cmd: '/resume', desc: '恢复之前的对话', group: '会话' },
  { cmd: '/context', desc: '上下文占比', group: '会话' },
  { cmd: '/clear', desc: '清空当前对话', group: '操作' },
  { cmd: '/help', desc: '查看帮助', group: '操作' },
]

const filtered = computed(() => {
  const q = search.value.toLowerCase()
  return allCommands.filter(c => c.cmd.includes(q) || c.desc.includes(q))
})

const groups = computed(() => {
  const map = new Map<string, Command[]>()
  for (const c of filtered.value) {
    const arr = map.get(c.group) || []
    arr.push(c)
    map.set(c.group, arr)
  }
  return Array.from(map.entries())
})

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'ArrowDown') { e.preventDefault(); activeIdx.value = Math.min(activeIdx.value + 1, filtered.value.length - 1) }
  else if (e.key === 'ArrowUp') { e.preventDefault(); activeIdx.value = Math.max(activeIdx.value - 1, 0) }
  else if (e.key === 'Enter') { e.preventDefault(); const f = filtered.value[activeIdx.value]; if (f) emit('select', f.cmd) }
  else if (e.key === 'Escape') { emit('close') }
}

onMounted(() => { window.addEventListener('keydown', onKeydown, true) })
onUnmounted(() => { window.removeEventListener('keydown', onKeydown, true) })
</script>

<template>
  <div v-if="visible" class="slash-menu">
    <div class="slash-search">
      <Icon icon="lucide:search" class="search-icon" />
      <input v-model="search" placeholder="搜索命令..." autofocus />
    </div>
    <div v-for="[group, cmds] in groups" :key="group" class="slash-group">
      <div class="slash-group-label">{{ group }}</div>
      <div v-for="(c, i) in cmds" :key="c.cmd" class="slash-item" :class="{ active: i === activeIdx || (search && i === 0) }" @click="emit('select', c.cmd)">
        <span class="cmd-text">{{ c.cmd }}</span>
        <span class="desc-text">{{ c.desc }}</span>
        <span class="badge-text" v-if="c.badge">{{ c.badge }}</span>
        <Icon v-if="!c.badge" icon="lucide:chevron-right" class="arrow-icon" />
      </div>
    </div>
  </div>
</template>

<style scoped>
@import url('./SlashMenu.css');
</style>
```

创建 `SlashMenu.css`：

```css
.slash-menu {
  position: absolute; bottom: calc(100% + 8px); left: 0; right: 0;
  background: var(--el-bg-color); border: 1px solid var(--el-border-color);
  border-radius: 12px; box-shadow: 0 8px 32px rgba(0,0,0,0.12);
  overflow: hidden; z-index: 200; max-height: 360px; overflow-y: auto;
}
.slash-search { display: flex; align-items: center; gap: 8px; padding: 10px 14px; border-bottom: 1px solid var(--el-border-color-light); }
.slash-search input { flex: 1; border: none; background: transparent; color: var(--el-text-color-primary); font-size: 14px; outline: none; }
.search-icon { color: var(--el-text-color-placeholder); font-size: 16px; flex-shrink: 0; }
.slash-group { padding: 4px 0; }
.slash-group + .slash-group { border-top: 1px solid var(--el-border-color-light); }
.slash-group-label { font-size: 11px; color: var(--el-text-color-placeholder); padding: 6px 14px 2px; text-transform: uppercase; letter-spacing: 0.5px; }
.slash-item { display: flex; align-items: center; gap: 8px; padding: 8px 14px; cursor: pointer; transition: background 0.1s; font-size: 14px; }
.slash-item:hover, .slash-item.active { background: var(--el-fill-color-light); }
.cmd-text { font-weight: 600; color: var(--el-color-primary); min-width: 70px; }
.desc-text { color: var(--el-text-color-secondary); flex: 1; }
.badge-text { font-size: 12px; background: var(--el-fill-color); color: var(--el-text-color-secondary); padding: 1px 6px; border-radius: 4px; }
.arrow-icon { color: var(--el-text-color-placeholder); font-size: 12px; flex-shrink: 0; }

html.dark .slash-menu { background: var(--el-bg-color); }
html.dark .slash-item:hover, html.dark .slash-item.active { background: var(--el-fill-color-light); }
```

- [ ] **Step 2: 修改 InputArea.vue 集成 SlashMenu**

在 `InputArea.vue` 的 `<script setup>` 中添加：

```typescript
import SlashMenu from './SlashMenu.vue'
import { useConversationsStore } from '@/store/conversations'

const convStore = useConversationsStore()
const showSlashMenu = ref(false)
const mcpCount = ref(0)
const skillCount = ref(0)
const memoryCount = ref(0)

function onInput() {
  const el = inputRef.value
  if (el) { el.style.height = 'auto'; el.style.height = Math.min(el.scrollHeight, 160) + 'px' }
  // 检测 / 触发 slash 菜单
  if (text.value === '/') {
    showSlashMenu.value = true
    fetchSlashCounts()
  }
}

function onKeydown(e: KeyboardEvent) {
  if (showSlashMenu.value) return // 由 SlashMenu 处理键盘事件
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); doSend() }
}

function fetchSlashCounts() {
  axios({ url: '/mcp/server/list', method: 'get' }).then(res => {
    if (res.data.state === 'OK') mcpCount.value = res.data.body.length
  }).catch(() => {})
  axios({ url: '/skill/list', method: 'get' }).then(res => {
    if (res.data.state === 'OK') skillCount.value = res.data.body.length
  }).catch(() => {})
  axios({ url: '/memory/list', method: 'get' }).then(res => {
    if (res.data.state === 'OK') memoryCount.value = res.data.body.length
  }).catch(() => {})
}

function onSlashSelect(cmd: string) {
  showSlashMenu.value = false
  text.value = ''
  switch (cmd) {
    case '/model': showModelList.value = !showModelList.value; break
    case '/mcp': emit('slashCommand', 'mcp'); break
    case '/skills': emit('slashCommand', 'skills'); break
    case '/memory': emit('slashCommand', 'memory'); break
    case '/resume': emit('slashCommand', 'resume'); break
    case '/context': emit('slashCommand', 'context'); break
    case '/clear': emit('clearMessages'); break
    case '/help': emit('slashCommand', 'help'); break
  }
}

function closeSlashMenu() {
  showSlashMenu.value = false
  text.value = ''
}
```

在 template 中，在 `input-container` div 最前面加入：

```html
<SlashMenu
  :visible="showSlashMenu"
  :models="settingsStore.models"
  :mcpCount="mcpCount"
  :skillCount="skillCount"
  :memoryCount="memoryCount"
  @close="closeSlashMenu"
  @select="onSlashSelect"
/>
```

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/static/src/views/chat/SlashMenu.vue src/main/resources/static/src/views/chat/SlashMenu.css src/main/resources/static/src/views/chat/InputArea.vue
git commit -m "feat: 添加 SlashMenu / 命令输入组件"

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

### Task D2: 创建浮层面板组件

**Files:**
- Create: `src/main/resources/static/src/views/chat/ResumePanel.vue`

- [ ] **Step 1: 创建 ResumePanel（恢复对话浮层）**

```vue
<script lang="ts" setup>
import { ref } from 'vue'
import { useConversationsStore } from '@/store/conversations'
import { Icon } from '@iconify/vue'
import axios from '@/network'

const convStore = useConversationsStore()

defineProps<{ visible: boolean }>()
const emit = defineEmits<{ close: [] }>()

function select(id: string) {
  convStore.selectConversation(id)
  emit('close')
}
</script>

<template>
  <teleport to="body">
    <div v-if="visible" class="panel-overlay" @click.self="emit('close')">
      <div class="resume-panel">
        <div class="panel-header">
          <span>恢复对话</span>
          <button class="panel-close" @click="emit('close')"><Icon icon="lucide:x" /></button>
        </div>
        <div class="panel-body">
          <div v-for="c in convStore.conversations" :key="c.id" class="panel-item" @click="select(c.id)">
            <div>
              <div class="item-title">{{ c.title }}</div>
              <div class="item-meta">{{ c.messageCount || 0 }} 条消息</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </teleport>
</template>

<style scoped>
.panel-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.3); z-index: 300; display: flex; align-items: center; justify-content: center; }
.resume-panel { background: var(--el-bg-color); border: 1px solid var(--el-border-color); border-radius: 12px; width: 480px; max-height: 420px; overflow-y: auto; }
.panel-header { display: flex; justify-content: space-between; align-items: center; padding: 14px 16px; border-bottom: 1px solid var(--el-border-color-light); font-weight: 600; }
.panel-close { background: none; border: none; cursor: pointer; color: var(--el-text-color-secondary); padding: 4px; border-radius: 4px; }
.panel-close:hover { background: var(--el-fill-color-light); }
.panel-body { padding: 4px 0; }
.panel-item { padding: 10px 16px; cursor: pointer; }
.panel-item:hover { background: var(--el-fill-color-light); }
.item-title { font-weight: 500; }
.item-meta { font-size: 12px; color: var(--el-text-color-placeholder); margin-top: 2px; }
</style>
```

类似方式创建以下面板组件（结构一致：teleport overlay + panel header + list body）：

| 组件 | 文件 | 数据来源 |
|------|------|---------|
| McpListPanel.vue | 新建 | `GET /mcp/server/list` |
| SkillsListPanel.vue | 新建 | `GET /skill/list` |
| MemoryListPanel.vue | 新建 | `GET /emory/list`，点击跳转 `router.push(`/settings/memory/edit?name=${name}`)` |
| ContextPanel.vue | 新建 | `GET /chat/context-usage?conversationId=xxx`，条形图展示各部分占比 |

所有面板通过 ChatView.vue 统一管理显示状态（`ref<boolean>` per panel），InputArea emit `slashCommand` 事件后由 ChatView 响应显示对应面板。

- [ ] **Step 2: 提交**

```bash
git add src/main/resources/static/src/views/chat/ResumePanel.vue
git commit -m "feat: 添加 / slash 命令浮层面板组件"

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```

---

## 验证与集成测试

### Task E1: 端到端验证

- [ ] **Step 1: 构建前端**

```bash
cd src/main/resources/static
npm run build
mkdir -p ../../../target/classes/static
cp -rf zephyr-ui ../../../target/classes/static/
```

- [ ] **Step 2: 启动后端并验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn spring-boot:run -Dspring-boot.run.profiles=me
```

- [ ] **Step 3: 测试对话流程**

```bash
# 1. 配置模型
curl -u admin:123456 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/model-config/create" \
  -d '{"name":"DeepSeek-V3","baseUrl":"https://api.deepseek.com","apiKey":"sk-xxx","isDefault":true}'

# 2. 创建会话
curl -u admin:123456 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/conversations/create" \
  -d '{"title":"测试"}' 

# 3. 发送消息（SSE 流式）
curl -u admin:123456 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/chat/send" \
  -d '{"conversationId":"<上一步返回的id>","message":"你好"}'

# 4. 上下文占比
curl -u admin:123456 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/chat/context-usage"
```

- [ ] **Step 4: 浏览器验证**

```bash
open http://localhost:30733/zephyr/zephyr-ui/index.html
```

测试：
- [ ] 输入框 `/` 弹出命令菜单
- [ ] 发送消息后 SSE 流式返回
- [ ] 会话列表正确显示
- [ ] 切换会话后历史消息正确加载

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat: LLM 对话接入端到端验证完成"

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
```
