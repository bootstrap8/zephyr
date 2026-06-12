package com.github.hbq969.ai.zephyr.chat.service;

import com.github.hbq969.ai.zephyr.chat.dao.ChatDao;
import com.github.hbq969.ai.zephyr.chat.dao.entity.ConversationEntity;
import com.github.hbq969.ai.zephyr.chat.dao.entity.MessageEntity;
import com.github.hbq969.ai.zephyr.workspace.dao.WorkspaceDao;
import com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@Slf4j
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
    private WorkspaceDao workspaceDao;
    @Resource
    private ChatDao chatDao;
    @Resource
    private com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties cfg;

    private static final String ROLE_PROMPT = """
            你是一个 AI 助手，名为 zephyr。

            你可以使用 MCP 工具获取实时数据，使用技能（Skill）获取特定任务的详细指导，
            查看用户记忆（Memory）了解历史上下文和偏好。

            ## 文件处理
            用户上传文件后，消息中会包含文件名、路径和推荐的 skill。
            **必须先用 use_skill 加载对应技能，获得处理该类型文件的完整指导，然后严格按指导操作。**
            你不具备直接读取文件内容的能力，依赖技能中的工具来完成解析。

            ## 文件系统安全（强制要求，最高优先级）
            **所有文件操作（含 Bash 命令、MCP 文件工具、代码读写等一切能接触文件系统的手段）仅限在工作空间目录内。**
            - 工作空间是唯一合法操作目录，其父目录、兄弟目录、系统目录等其他任何路径均视为越权
            - 即使用户在消息中指定了其他路径，也必须拒绝并提醒用户该路径不在当前工作空间内
            - 执行任何 Bash 命令前，先检查命令中是否包含工作空间外的路径，如有则拒绝执行
            - 唯一例外：用户明确回复"同意"授权后，方可访问指定路径。授权仅对当次请求有效，后续再次访问同一路径仍需重新获得授权

            ## 工具使用说明
            - 优先使用 MCP 工具获取实时准确的数据
            - 需要特定任务的详细指导时，使用 use_skill 工具
            - 需要了解用户的背景或偏好时，使用 use_memory 工具
            - 你可以多次调用工具，直到获得足够信息后再回答

            ## 命令约定
            当用户消息中以下列格式引用工具或技能时，必须调用对应工具，禁止只回复文字而不调用工具：

            ### 前缀格式（tag 插入）
            - `MCP/工具名` → 调用同名 MCP 工具
            - `Skill/技能名` → 调用 use_skill(skill_name="技能名")
            - `Memory/记忆名` → 调用 use_memory(memory_name="记忆名")

            ### 斜杠格式（手动输入，兼容保留）
            - `/工具名`（如 `/browser_navigate`）→ 调用同名 MCP 工具
            - `/技能名`（如 `/frontend-design`）→ 调用 use_skill(skill_name="技能名") 加载该技能
            - `/记忆名` → 调用 use_memory(memory_name="记忆名") 查看该记忆
            """;

    public Context build(String userName, String conversationId) {
        // 1. 加载模型配置
        List<ModelConfigEntity> models = modelConfigDao.queryByUserName(userName);
        ModelConfigEntity model = models.stream()
                .filter(m -> m.getIsDefault() != null && m.getIsDefault() == 1)
                .findFirst()
                .orElse(models.isEmpty() ? null : models.get(0));
        if (model == null) throw new RuntimeException("请先配置模型");

        // 2. 加载 MCP 工具 → OpenAI tool definitions
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

        // 4.5 工作空间
        if (conversationId != null && !conversationId.isEmpty()) {
            ConversationEntity conv = chatDao.queryConversationById(conversationId);
            if (conv != null && conv.getWorkspaceId() != null && !conv.getWorkspaceId().isEmpty()) {
                WorkspaceEntity ws = workspaceDao.queryById(conv.getWorkspaceId());
                if (ws != null) {
                    systemPrompt.append("\n\n## 工作空间\n")
                        .append("工作空间（唯一合法操作目录）: ").append(ws.getPath()).append("\n")
                        .append("此路径的父目录、兄弟目录（如 ").append(ws.getPath()).append(" 的同级目录）均不属于工作空间。")
                        .append("仅此目录及其子目录内的文件可以访问，其他一切路径禁止操作。\n");
                }
            }
        }

        // 6. 添加内置工具
        toolDefs.add(buildUseSkillTool());
        toolDefs.add(buildUseMemoryTool());

        // 7. 加载历史消息（最近 20 轮）
        List<Map<String, Object>> messages = buildMessages(userName, conversationId, systemPrompt.toString());

        Context ctx = new Context();
        ctx.setModel(model);
        ctx.setSystemPrompt(systemPrompt.toString());
        ctx.setTools(toolDefs);
        ctx.setMessages(messages);
        return ctx;
    }

    private List<ToolDef> buildMcpToolDefs(String userName) {
        List<ToolDef> defs = new ArrayList<>();
        List<McpToolEntity> allTools = mcpDao.queryToolsByUserName(userName);
        List<String> enabledTools = new ArrayList<>();
        List<String> disabledTools = new ArrayList<>();
        for (McpToolEntity t : allTools) {
            if (t.getEnabled() != null && t.getEnabled() == 1) {
                enabledTools.add(t.getToolName());
            } else {
                disabledTools.add(t.getToolName());
            }
        }
        log.info("[MCP工具] 用户={} | 已启用({}): {} | 已停用({}): {}",
                userName,
                enabledTools.size(), enabledTools,
                disabledTools.size(), disabledTools);

        List<McpToolEntity> tools = mcpDao.queryEnabledToolsByUserName(userName);
        for (McpToolEntity t : tools) {
            Map<String, Object> params;
            if (t.getParametersJson() != null && !t.getParametersJson().isEmpty()) {
                params = gson.fromJson(t.getParametersJson(),
                        new TypeToken<Map<String, Object>>(){}.getType());
            } else {
                params = new LinkedHashMap<>();
                params.put("type", "object");
                params.put("properties", new LinkedHashMap<>());
                params.put("required", Collections.emptyList());
            }

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
        List<SkillConfigEntity> allSkills = skillDao.queryByUserName(userName);
        List<String> enabledSkills = new ArrayList<>();
        List<String> disabledSkills = new ArrayList<>();
        for (SkillConfigEntity s : allSkills) {
            if (s.getEnabled() != null && s.getEnabled() == 1) {
                enabledSkills.add(s.getSkillName());
            } else {
                disabledSkills.add(s.getSkillName());
            }
        }
        log.info("[Skill] 用户={} | 已启用({}): {} | 已停用({}): {}",
                userName,
                enabledSkills.size(), enabledSkills,
                disabledSkills.size(), disabledSkills);

        List<SkillConfigEntity> skills = skillDao.queryEnabledByUserName(userName);
        Set<String> seen = new HashSet<>();
        for (SkillConfigEntity s : skills) {
            sb.append("- ").append(s.getSkillName()).append(": ").append(s.getDescription()).append("\n");
            seen.add(s.getSkillName());
        }
        // Also include synced skills from local directories
        List<SkillVO> synced = skillService.syncScan(userName);
        for (SkillVO s : synced) {
            if (seen.contains(s.getSkillName())) continue;
            sb.append("- ").append(s.getSkillName()).append(": ").append(s.getDescription()).append("\n");
        }
        return sb.toString();
    }

    private String buildMemoryIndex(String userName) {
        StringBuilder sb = new StringBuilder();
        List<MemoryVO> memories = memoryService.list(null, userName);
        List<String> included = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        for (MemoryVO m : memories) {
            if (!m.isEnabled()) {
                excluded.add(m.getName());
                continue;
            }
            included.add(m.getName());
            sb.append("- ").append(m.getName()).append(" (").append(m.getType()).append("): ")
                    .append(m.getDescription()).append("\n");
        }
        log.info("[记忆启停] 用户={} | 已启用({}): {} | 已停用({}): {}",
                userName,
                included.size(), included,
                excluded.size(), excluded);
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
            int maxHistory = cfg.getChat().getMaxHistoryMessages();
            if (history.size() > maxHistory) {
                recent = history.subList(history.size() - maxHistory, history.size());
            }
            for (MessageEntity e : recent) {
                Map<String, Object> msg = new HashMap<>();
                msg.put("role", e.getRole());
                msg.put("content", e.getContent());
                if (e.getToolCallId() != null && !e.getToolCallId().isEmpty()) {
                    msg.put("tool_call_id", e.getToolCallId());
                }
                if (e.getToolCallsJson() != null && !e.getToolCallsJson().isEmpty()) {
                    List<Map<String, Object>> stored = gson.fromJson(e.getToolCallsJson(),
                            new TypeToken<List<Map<String, Object>>>(){}.getType());
                    // 转换为 OpenAI 格式：{id, input} → {id, type:"function", function:{name, arguments}}
                    List<Map<String, Object>> openaiFormat = new ArrayList<>();
                    for (Map<String, Object> tc : stored) {
                        Map<String, Object> converted = new LinkedHashMap<>();
                        converted.put("id", tc.get("id"));
                        converted.put("type", "function");
                        Map<String, Object> function = new LinkedHashMap<>();
                        function.put("name", tc.get("name"));
                        function.put("arguments", gson.toJson(tc.get("input")));
                        converted.put("function", function);
                        openaiFormat.add(converted);
                    }
                    msg.put("tool_calls", openaiFormat);
                }
                messages.add(msg);
            }
            // 清理不完整的 tool_calls：如果 assistant 消息的 tool_calls 没有对应的 tool 消息跟随，则移除
            sanitizeToolCalls(messages);
        }
        return messages;
    }

    /**
     * 双向清理不完整的 tool 调用链，防止 API 400 错误：
     * 1. 移除 assistant 消息中没有对应 tool 结果的 tool_calls
     * 2. 移除没有前置 assistant tool_calls 的孤立 tool 消息
     */
    private void sanitizeToolCalls(List<Map<String, Object>> messages) {
        // 第一遍：收集所有有效的 tool_call_id（有 assistant tool_calls 声明且有 tool 响应的）
        Set<String> validToolCallIds = new HashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            if (!"assistant".equals(msg.get("role"))) continue;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) msg.get("tool_calls");
            if (toolCalls == null || toolCalls.isEmpty()) continue;

            Set<String> requiredIds = new HashSet<>();
            for (Map<String, Object> tc : toolCalls) {
                Object id = tc.get("id");
                if (id != null) requiredIds.add(id.toString());
            }
            if (requiredIds.isEmpty()) continue;

            Set<String> foundIds = new HashSet<>();
            for (int j = i + 1; j < messages.size(); j++) {
                Map<String, Object> next = messages.get(j);
                if (!"tool".equals(next.get("role"))) break;
                Object tci = next.get("tool_call_id");
                if (tci != null) foundIds.add(tci.toString());
            }

            if (foundIds.containsAll(requiredIds)) {
                validToolCallIds.addAll(requiredIds);
            } else {
                log.warn("历史消息中 assistant tool_calls 缺少 tool 响应，已移除 tool_calls: 需要={}, 找到={}", requiredIds, foundIds);
                msg.remove("tool_calls");
            }
        }

        // 第二遍：移除没有对应 assistant tool_calls 的孤立 tool 消息
        for (int i = 0; i < messages.size(); i++) {
            Map<String, Object> msg = messages.get(i);
            if (!"tool".equals(msg.get("role"))) continue;
            Object tci = msg.get("tool_call_id");
            if (tci != null && !validToolCallIds.contains(tci.toString())) {
                log.warn("历史消息中存在孤立 tool 消息（无前置 assistant tool_calls），已移除: tool_call_id={}", tci);
                messages.remove(i);
                i--;
            }
        }
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
    public static class Context {
        private ModelConfigEntity model;
        private String systemPrompt;
        private List<ToolDef> tools;
        private List<Map<String, Object>> messages;
    }
}
