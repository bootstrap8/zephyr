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
    private ChatDao chatDao;

    private static final int MAX_HISTORY_ROUNDS = 20;

    private static final String ROLE_PROMPT = """
            你是一个 AI 助手，名为 zephyr。

            你可以使用 MCP 工具获取实时数据，使用技能（Skill）获取特定任务的详细指导，
            查看用户记忆（Memory）了解历史上下文和偏好。

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
            if (history.size() > MAX_HISTORY_ROUNDS * 2) {
                recent = history.subList(history.size() - MAX_HISTORY_ROUNDS * 2, history.size());
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
    public static class Context {
        private ModelConfigEntity model;
        private String systemPrompt;
        private List<ToolDef> tools;
        private List<Map<String, Object>> messages;
    }
}
