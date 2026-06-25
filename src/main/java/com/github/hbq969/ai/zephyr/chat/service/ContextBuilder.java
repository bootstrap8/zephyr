package com.github.hbq969.ai.zephyr.chat.service;

import com.github.hbq969.ai.zephyr.chat.dao.ChatDao;
import com.github.hbq969.ai.zephyr.chat.dao.entity.ConversationEntity;
import com.github.hbq969.ai.zephyr.chat.dao.entity.MessageEntity;
import com.github.hbq969.ai.zephyr.workspace.dao.WorkspaceDao;
import com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity;
import com.github.hbq969.ai.zephyr.chat.model.ToolDef;
import com.github.hbq969.ai.zephyr.config.dao.ModelConfigDao;
import com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity;
import com.github.hbq969.ai.zephyr.config.dao.entity.UserModelPreferenceEntity;
import com.github.hbq969.ai.zephyr.mcp.dao.McpDao;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity;
import com.github.hbq969.ai.zephyr.memory.model.MemoryVO;
import com.github.hbq969.ai.zephyr.memory.service.MemoryService;
import com.github.hbq969.ai.zephyr.skill.dao.SkillDao;
import com.github.hbq969.ai.zephyr.skill.dao.entity.SkillConfigEntity;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.annotation.Resource;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;

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
    private MemoryService memoryService;
    @Resource
    private WorkspaceDao workspaceDao;
    @Resource
    private ChatDao chatDao;
    @Resource
    private com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties cfg;
    @Resource
    private com.github.hbq969.ai.zephyr.knowledge.dao.KnowledgeDao knowledgeDao;
    @Resource
    private com.github.hbq969.ai.zephyr.knowledge.service.KnowledgeService knowledgeService;
    @Resource
    private com.github.hbq969.ai.zephyr.config.dao.UserModelPreferenceDao userModelPreferenceDao;
    @Resource
    private com.github.hbq969.ai.zephyr.security.PromptLoader promptLoader;

    private String fileSystemSecurityPrompt(String mode) {
        if (MODE_BYPASS.equalsIgnoreCase(mode)) return promptLoader.load("modes/bypass.md");
        if (MODE_ACCEPT_EDITS.equalsIgnoreCase(mode)) return promptLoader.load("modes/accept-edits.md");
        return promptLoader.load("modes/default.md");
    }

    public Context build(String userName, String conversationId, String mode) {
        // 1. 加载模型配置（合并共享 + 私有）
        List<ModelConfigEntity> ownModels = modelConfigDao.queryByUserName(userName);
        List<ModelConfigEntity> sharedModels = modelConfigDao.queryShared();

        // 私有优先，共享补充（同名时私有覆盖共享）
        Map<String, ModelConfigEntity> modelMap = new LinkedHashMap<>();
        for (ModelConfigEntity m : ownModels) {
            modelMap.put(m.getName(), m);
        }
        for (ModelConfigEntity m : sharedModels) {
            modelMap.putIfAbsent(m.getName(), m);
        }
        List<ModelConfigEntity> allModels = new ArrayList<>(modelMap.values());

        // 1.1 优先读用户偏好表
        ModelConfigEntity model = null;
        String modelType = MODEL_TYPE_LLM;
        UserModelPreferenceEntity pref =
                userModelPreferenceDao.queryByUserAndType(userName, modelType);
        if (pref != null) {
            String prefId = pref.getModelId();
            model = ownModels.stream().filter(m -> prefId.equals(m.getId())).findFirst()
                    .orElseGet(() -> allModels.stream().filter(m -> prefId.equals(m.getId())).findFirst().orElse(null));
        }
        // 1.2 回退：用户私有默认 > 共享默认 > 第一个 llm
        if (model == null) {
            model = ownModels.stream()
                    .filter(m -> m.getIsDefault() != null && m.getIsDefault() == 1)
                    .findFirst()
                    .orElseGet(() -> allModels.stream()
                            .filter(m -> m.getIsDefault() != null && m.getIsDefault() == 1)
                            .findFirst().orElse(null));
        }
        if (model == null) {
            model = allModels.stream()
                    .filter(m -> MODEL_TYPE_LLM.equals(m.getModelType()) || m.getModelType() == null)
                    .findFirst()
                    .orElse(null);
        }
        if (model == null) throw new RuntimeException("请先配置模型");

        // 2. 加载 MCP 工具 → OpenAI tool definitions
        List<ToolDef> toolDefs = buildMcpToolDefs(userName);

        // 3. 加载 Skills 索引
        String skillIndex = buildSkillIndex(userName);

        // 4. 加载记忆索引
        String memoryIndex = buildMemoryIndex(userName);

        // 5. 组装安全规则（合并所有 security prompt 文件）
        StringBuilder securityRules = new StringBuilder();
        securityRules.append(promptLoader.load("security/tool-risk-rules.md")).append("\n\n");
        securityRules.append(promptLoader.load("security/hard-block.md")).append("\n\n");
        securityRules.append(promptLoader.load("security/soft-block.md")).append("\n\n");
        securityRules.append(promptLoader.load("security/allow-exceptions.md")).append("\n\n");
        securityRules.append(promptLoader.load("security/user-intent.md"));

        // 6. 渲染角色 prompt
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("fileSystemSecurity", fileSystemSecurityPrompt(mode));
        vars.put("skillIndex", skillIndex);
        vars.put("memoryIndex", memoryIndex);
        vars.put("knowledgeBaseIndex", "");
        vars.put("workspaceInfo", "");
        vars.put("securityRules", securityRules.toString());
        String rendered = promptLoader.render("role.md", vars);
        StringBuilder systemPrompt = new StringBuilder(rendered);

        if (!skillIndex.isEmpty()) {
            systemPrompt.append("\n\n## 可用技能\n").append(skillIndex)
                    .append("\n（需要详细指导时使用 use_skill 工具加载）");
        }
        if (!memoryIndex.isEmpty()) {
            systemPrompt.append("\n\n## 用户记忆\n").append(memoryIndex)
                    .append("\n（需要完整内容时使用 use_memory 工具查看）");
        }

        // 工作空间
        if (conversationId != null && !conversationId.isEmpty()) {
            ConversationEntity conv = chatDao.queryConversationById(conversationId);
            if (conv != null && conv.getWorkspaceId() != null && !conv.getWorkspaceId().isEmpty()) {
                WorkspaceEntity ws = workspaceDao.queryById(conv.getWorkspaceId());
                if (ws != null) {
                    systemPrompt.append("\n\n## 工作空间\n")
                        .append("当前工作目录: ").append(ws.getPath()).append("\n");
                    if (!MODE_BYPASS.equalsIgnoreCase(mode)) {
                        systemPrompt.append("此路径的父目录、兄弟目录均不属于工作空间，仅此目录及其子目录内的文件可以访问。\n");
                    }
                }
            }
        }

        // 加载对话勾选的知识库
        if (conversationId != null && !conversationId.isEmpty()) {
            List<String> enabledKbIds = knowledgeDao.queryKbIdsByConversation(conversationId);
            if (!enabledKbIds.isEmpty()) {
                List<com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeBaseEntity> kbs = knowledgeDao.queryKbByIds(enabledKbIds);
                systemPrompt.append("\n\n## 已启用知识库\n");
                for (com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeBaseEntity kb : kbs) {
                    systemPrompt.append("- ").append(kb.getName()).append(": ")
                        .append(kb.getDescription() != null ? kb.getDescription() : "").append("\n");
                }
                systemPrompt.append("使用 search_knowledge 工具检索知识库内容");
            }
        }

        // 6. 添加内置工具
        toolDefs.add(buildUseSkillTool());
        toolDefs.add(buildUseMemoryTool());
        toolDefs.add(buildSearchKnowledgeTool());
        toolDefs.add(buildExecuteShellTool());
        toolDefs.add(buildListProcessesTool());
        toolDefs.add(buildKillProcessTool());

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

        List<McpToolEntity> tools = new ArrayList<>(mcpDao.queryEnabledToolsByUserName(userName));
        tools.addAll(mcpDao.queryEnabledToolsBySharedServers());
        for (McpToolEntity t : tools) {
            Map<String, Object> params;
            if (t.getParametersJson() != null && !t.getParametersJson().isEmpty()) {
                params = gson.fromJson(t.getParametersJson(),
                        new TypeToken<Map<String, Object>>(){}.getType());
            } else {
                params = new LinkedHashMap<>();
                params.put("type", JSON_TYPE_OBJECT);
                params.put("properties", new LinkedHashMap<>());
                params.put("required", Collections.emptyList());
            }

            defs.add(ToolDef.builder()
                    .type(TOOL_CALL_TYPE_FUNCTION)
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

        // 用户私有 + 共享合并，用户私有覆盖同名共享
        Map<String, SkillConfigEntity> dedup = new LinkedHashMap<>();
        List<SkillConfigEntity> sharedSkills = skillDao.queryShared();
        for (SkillConfigEntity s : sharedSkills) {
            if (s.getEnabled() != null && s.getEnabled() == 1) {
                dedup.put(s.getSkillName(), s);
            }
        }
        List<SkillConfigEntity> userSkills = skillDao.queryEnabledByUserName(userName);
        for (SkillConfigEntity s : userSkills) {
            dedup.put(s.getSkillName(), s);
        }

        Set<String> seen = new HashSet<>();
        for (SkillConfigEntity s : dedup.values()) {
            sb.append("- ").append(s.getSkillName()).append(": ").append(s.getDescription()).append("\n");
            seen.add(s.getSkillName());
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
        systemMsg.put("role", ROLE_SYSTEM);
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
                        converted.put("type", TOOL_CALL_TYPE_FUNCTION);
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
                if (!ROLE_TOOL.equals(next.get("role"))) break;
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
            if (!ROLE_TOOL.equals(msg.get("role"))) continue;
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
        params.put("type", JSON_TYPE_OBJECT);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(PARAM_SKILL_NAME, Map.of("type", "string", "description", "技能名称"));
        params.put("properties", props);
        params.put("required", List.of(PARAM_SKILL_NAME));

        return ToolDef.builder()
                .type(TOOL_CALL_TYPE_FUNCTION)
                .function(ToolDef.FunctionDef.builder()
                        .name(TOOL_USE_SKILL)
                        .description("加载指定 skill 的完整指导内容到上下文")
                        .parameters(params)
                        .build())
                .build();
    }

    private ToolDef buildUseMemoryTool() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", JSON_TYPE_OBJECT);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(PARAM_MEMORY_NAME, Map.of("type", "string", "description", "记忆名称"));
        params.put("properties", props);
        params.put("required", List.of(PARAM_MEMORY_NAME));

        return ToolDef.builder()
                .type(TOOL_CALL_TYPE_FUNCTION)
                .function(ToolDef.FunctionDef.builder()
                        .name(TOOL_USE_MEMORY)
                        .description("查看指定记忆的完整内容")
                        .parameters(params)
                        .build())
                .build();
    }

    private ToolDef buildSearchKnowledgeTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(PARAM_QUERY, Map.of("type", "string", "description", "检索关键词或问题"));
        props.put(PARAM_TOP_K, Map.of("type", "integer", "description", "返回结果数量，默认 " + cfg.getKnowledge().getTopK()));

        return ToolDef.builder()
                .type(TOOL_CALL_TYPE_FUNCTION)
                .function(ToolDef.FunctionDef.builder()
                        .name(TOOL_SEARCH_KNOWLEDGE)
                        .description("从已勾选的知识库中检索相关文档片段")
                        .parameters(Map.of("type", JSON_TYPE_OBJECT, "properties", props, "required", List.of(PARAM_QUERY)))
                        .build())
                .build();
    }

    private ToolDef buildExecuteShellTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(PARAM_COMMAND, Map.of("type", "string", "description", "要执行的完整命令字符串"));
        props.put(PARAM_BACKGROUND, Map.of("type", "boolean", "description", "是否后台运行，默认 false"));

        return ToolDef.builder()
                .type(TOOL_CALL_TYPE_FUNCTION)
                .function(ToolDef.FunctionDef.builder()
                        .name(TOOL_EXECUTE_SHELL)
                        .description("在工作空间目录执行 shell 命令。前台命令（默认）阻塞等待结果返回；后台命令（background=true）立即返回，进程跨会话存活。")
                        .parameters(Map.of("type", JSON_TYPE_OBJECT, "properties", props, "required", List.of(PARAM_COMMAND)))
                        .build())
                .build();
    }

    private ToolDef buildListProcessesTool() {
        return ToolDef.builder()
                .type(TOOL_CALL_TYPE_FUNCTION)
                .function(ToolDef.FunctionDef.builder()
                        .name(TOOL_LIST_PROCESSES)
                        .description("列出当前用户的所有后台进程")
                        .parameters(Map.of("type", JSON_TYPE_OBJECT, "properties", new LinkedHashMap<>()))
                        .build())
                .build();
    }

    private ToolDef buildKillProcessTool() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(PARAM_PID, Map.of("type", "integer", "description", "进程 PID"));

        return ToolDef.builder()
                .type(TOOL_CALL_TYPE_FUNCTION)
                .function(ToolDef.FunctionDef.builder()
                        .name(TOOL_KILL_PROCESS)
                        .description("终止指定后台进程")
                        .parameters(Map.of("type", JSON_TYPE_OBJECT, "properties", props, "required", List.of(PARAM_PID)))
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
