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
import com.github.hbq969.ai.zephyr.workspace.dao.WorkspaceDao;
import com.github.hbq969.ai.zephyr.knowledge.dao.KnowledgeDao;
import com.github.hbq969.ai.zephyr.knowledge.service.KnowledgeService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
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
    private final ExecutorService executor = Executors.newCachedThreadPool();

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
    @Resource
    private WorkspaceDao workspaceDao;

    @Resource
    private com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties cfg;

    @Resource
    private KnowledgeDao knowledgeDao;

    @Resource
    private KnowledgeService knowledgeService;

    @Override
    public SseEmitter send(String userName, String conversationId, String workspaceId,
                           String originalMessage, String mode, List<String> filePaths) {
        SseEmitter emitter = new SseEmitter(cfg.getChat().getSse().getTimeoutMillis());
        String cancelKey = userName;

        emitter.onTimeout(() -> {
            llmClient.cancelCall(cancelKey);
            emitter.complete();
        });
        emitter.onError(th -> {
            log.warn("SSE client disconnected: {}", th.getMessage());
            llmClient.cancelCall(cancelKey);
        });

        executor.execute(() -> {
            try {
                String cid = conversationId;
                long now = System.currentTimeMillis() / 1000;
                long msgSeq = now;  // 单调递增计数器，保证所有消息 timestamp 唯一

                // 0. 预处理斜杠命令
                String message = originalMessage;
                if (message.startsWith("/")) {
                    String handled = handleSlashCommand(message, userName, cid, emitter, now);
                    if (handled == null) return; // 已直接处理完成
                    message = handled; // 改写后的消息
                }

                // 1. 确保会话存在（首次对话自动创建）
                if (cid == null || cid.isEmpty()) {
                    cid = UUID.fastUUID().toString(true).substring(0, 12);
                    ConversationEntity conv = new ConversationEntity();
                    conv.setId(cid);
                    conv.setUserName(userName);
                    conv.setTitle(message.length() > 30 ? message.substring(0, 30) : message);
                    conv.setWorkspaceId(workspaceId);
                    conv.setCreatedAt(now);
                    conv.setUpdatedAt(now);
                    chatDao.insertConversation(conv);
                    // 通知前端新会话ID
                    emitter.send(SseEmitter.event().name("message")
                            .data(ChatEvent.builder().type("meta").content(cid).build()));
                }

                // 2. 组装上下文（不含当前用户消息）
                ContextBuilder.Context ctx = contextBuilder.build(userName, cid, mode != null ? mode : "default");
                List<Map<String, Object>> messages = ctx.getMessages();

                // 如果有上传文件，拼入用户消息
                String userContent = message;
                if (filePaths != null && !filePaths.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("[用户上传了以下文件，请根据扩展名使用对应的 skill 处理]\n");
                    for (String p : filePaths) {
                        String ext = "";
                        String name = p.substring(p.lastIndexOf('/') + 1);
                        int dotIdx = name.lastIndexOf('.');
                        if (dotIdx > 0) {
                            ext = name.substring(dotIdx).toLowerCase();
                            // 去掉时间戳前缀（如 1718123456_）
                            int usIdx = name.indexOf('_');
                            if (usIdx > 0 && usIdx < dotIdx) {
                                name = name.substring(usIdx + 1);
                            }
                        }
                        String skillHint = switch (ext) {
                            case ".pdf" -> " → 调用 use_skill(\"pdf\")";
                            case ".xlsx", ".xls" -> " → 调用 use_skill(\"xlsx\")";
                            case ".docx", ".doc" -> " → 调用 use_skill(\"docx\")";
                            case ".pptx", ".ppt" -> " → 调用 use_skill(\"pptx\")";
                            case ".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp" -> " → 调用 use_skill(\"image-analysis\")";
                            default -> "";
                        };
                        sb.append("- ").append(name).append(skillHint).append("\n");
                        sb.append("  路径: ").append(p).append("\n");
                    }
                    sb.append("\n用户消息: ").append(message);
                    userContent = sb.toString();
                }
                messages.add(Map.of("role", "user", "content", userContent));

                // 3. 持久化 user 消息（必须在上下文构建之后，避免重复加载）
                MessageEntity userMsg = new MessageEntity();
                userMsg.setId(UUID.fastUUID().toString(true).substring(0, 12));
                userMsg.setConversationId(cid);
                userMsg.setRole("user");
                userMsg.setContent(message);
                userMsg.setCreatedAt(msgSeq++);
                chatDao.insertMessage(userMsg);

                // 4. 工具调用循环（无轮次限制，模型自行决定何时停止）
                LlmResult result = null;
                int totalInputTokens = 0, totalOutputTokens = 0, rounds = 0;
                while (true) {
                    result = llmClient.chat(ctx.getModel(), messages, ctx.getTools(), emitter, cancelKey);
                    rounds++;
                    if (result.getUsage() != null) {
                        totalInputTokens += result.getUsage().getOrDefault("inputTokens", 0);
                        totalOutputTokens += result.getUsage().getOrDefault("outputTokens", 0);
                    }

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

                        // 4b. 持久化 assistant 消息（单调递增保证顺序）
                        persistAssistantMessage(cid, result, msgSeq++);

                        // 4c. 分发工具调用
                        List<String> enabledKbIds = cid != null ? knowledgeDao.queryKbIdsByConversation(cid) : List.of();
                        List<Map<String, Object>> toolResults = dispatchTools(result.getToolCalls(), userName, enabledKbIds, emitter);

                        // 推送工具执行结果
                        for (int i = 0; i < result.getToolCalls().size(); i++) {
                            LlmResult.ToolCall tc = result.getToolCalls().get(i);
                            String output = toolResults.get(i).get("content").toString();
                            try {
                                emitter.send(SseEmitter.event().name("message")
                                        .data(ChatEvent.builder()
                                                .type("tool_result")
                                                .toolName(tc.getName())
                                                .toolOutput(output)
                                                .build()));
                            } catch (IOException e) {
                                log.warn("推送 tool_result 事件失败: {}", e.getMessage());
                            }
                        }

                        messages.addAll(toolResults);

                        // 4d. 持久化 tool 消息
                        for (int i = 0; i < result.getToolCalls().size(); i++) {
                            LlmResult.ToolCall tc = result.getToolCalls().get(i);
                            MessageEntity toolMsg = new MessageEntity();
                            toolMsg.setId(UUID.fastUUID().toString(true).substring(0, 12));
                            toolMsg.setConversationId(cid);
                            toolMsg.setRole("tool");
                            toolMsg.setContent(toolResults.get(i).get("content").toString());
                            toolMsg.setToolCallId(tc.getId());
                            toolMsg.setCreatedAt(msgSeq++);
                            chatDao.insertMessage(toolMsg);
                        }
                    } else {
                        // 5. 正常结束（内容为空时跳过持久化，避免空消息气泡）
                        if (isNotBlank(result.getContent()) || result.hasToolCalls()) {
                            persistAssistantMessage(cid, result, msgSeq++);
                        }
                        if (rounds > 1 || totalInputTokens + totalOutputTokens > 0) {
                            log.info("对话完成 — 共 {} 轮, 输入: {} tokens, 输出: {} tokens, 合计: {} tokens",
                                    rounds, totalInputTokens, totalOutputTokens, totalInputTokens + totalOutputTokens);
                        }
                        emitter.send(SseEmitter.event().name("message")
                                .data(ChatEvent.builder().type("done").build()));
                        emitter.complete();
                        return;
                    }
                }
            } catch (Exception e) {
                // 客户端断连是预期行为，不记 ERROR
                boolean disconnected = e instanceof java.io.IOException
                        && e.getMessage() != null && e.getMessage().contains("CANCEL");
                if (disconnected) {
                    log.debug("SSE 客户端已断开，终止对话");
                } else {
                    log.error("Chat error", e);
                    try {
                        emitter.send(SseEmitter.event().name("message")
                                .data(ChatEvent.builder().type("error").content(e.getMessage()).build()));
                        emitter.complete();
                    } catch (Exception ignored) {}
                }
            }
        });

        return emitter;
    }

    private void persistAssistantMessage(String cid, LlmResult result, long now) {
        MessageEntity msg = new MessageEntity();
        msg.setId(cn.hutool.core.lang.UUID.fastUUID().toString(true).substring(0, 12));
        msg.setConversationId(cid);
        msg.setRole("assistant");
        msg.setContent(result.getContent() != null ? result.getContent().trim() : "");
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
        ConversationEntity conv = chatDao.queryConversationById(cid);
        if (conv != null) {
            chatDao.updateConversationTitle(cid, conv.getTitle(),
                    System.currentTimeMillis() / 1000, conv.getUserName());
        }
    }

    /**
     * 处理斜杠命令。返回 null 表示已直接处理完成（emitter 已关闭），
     * 返回非 null 字符串表示改写后的消息（继续走 LLM 流程）。
     */
    private String handleSlashCommand(String message, String userName, String cid,
                                       SseEmitter emitter, long now) throws IOException {
        String cmd = message.trim();
        int spaceIdx = cmd.indexOf(' ');
        String cmdName = spaceIdx > 0 ? cmd.substring(1, spaceIdx) : cmd.substring(1);
        String cmdArgs = spaceIdx > 0 ? cmd.substring(spaceIdx + 1).trim() : "";

        // 内置命令：直接处理，不走 LLM
        switch (cmdName) {
            case "clear" -> {
                chatDao.deleteMessagesByConvId(cid);
                chatDao.deleteConversation(cid, userName);
                emitter.send(SseEmitter.event().name("message")
                        .data(ChatEvent.builder().type("clear").build()));
                emitter.send(SseEmitter.event().name("message")
                        .data(ChatEvent.builder().type("done").build()));
                emitter.complete();
                return null;
            }
            case "help" -> {
                String help = """
                        ## zephyr 可用命令

                        ### MCP 工具
                        输入 `/工具名` 直接调用 MCP 工具，如 `/browser_navigate`

                        ### 技能
                        输入 `/技能名` 加载并使用技能，如 `/frontend-design`

                        ### 会话
                        - `/context` — 查看上下文占比

                        ### 操作
                        - `/clear` — 清空当前对话
                        - `/help` — 查看此帮助
                        """;
                emitter.send(SseEmitter.event().name("message")
                        .data(ChatEvent.builder().type("token").content(help).build()));
                emitter.send(SseEmitter.event().name("message")
                        .data(ChatEvent.builder().type("done").build()));
                emitter.complete();
                return null;
            }
            case "context" -> {
                Map<String, Object> usage = contextUsage(userName, cid, null);
                StringBuilder sb = new StringBuilder("## 上下文占比\n\n");
                sb.append("| 类型 | Token 数 |\n");
                sb.append("|------|----------|\n");
                sb.append("| 系统提示词 | ").append(usage.get("systemPrompt")).append(" |\n");
                sb.append("| 历史消息 | ").append(usage.get("history")).append(" |\n");
                sb.append("| 技能内容 | ").append(usage.get("skillContent")).append(" |\n");
                sb.append("| 记忆内容 | ").append(usage.get("memoryContent")).append(" |\n");
                sb.append("| 工具定义 | ").append(usage.get("toolDefinitions")).append(" |\n");
                sb.append("| **总计** | **").append(usage.get("total")).append("** |\n");
                emitter.send(SseEmitter.event().name("message")
                        .data(ChatEvent.builder().type("token").content(sb.toString()).build()));
                emitter.send(SseEmitter.event().name("message")
                        .data(ChatEvent.builder().type("done").build()));
                emitter.complete();
                return null;
            }
        }

        // MCP 工具 / 技能 / 记忆命令：改写为更明确的消息
        if (!cmdArgs.isEmpty()) {
            return "请使用 " + cmdName + " 工具，参数: " + cmdArgs;
        }
        return "请调用 " + cmdName;
    }

    private List<Map<String, Object>> dispatchTools(List<LlmResult.ToolCall> toolCalls, String userName, List<String> enabledKbIds, SseEmitter emitter) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (LlmResult.ToolCall tc : toolCalls) {
            String content;
            try {
                content = switch (tc.getName()) {
                    case "use_skill" -> executeUseSkill(tc.getArguments().get("skill_name").toString(), userName);
                    case "use_memory" -> executeUseMemory(tc.getArguments().get("memory_name").toString(), userName);
                    case "search_knowledge" -> executeSearchKnowledge(tc.getArguments(), enabledKbIds);
                    default -> executeMcpTool(tc.getName(), tc.getArguments(), userName);
                };
            } catch (Exception e) {
                content = "工具执行错误: " + e.getMessage();
            }
            content = sanitizeToolOutput(content);
            try {
                detectArtifacts(tc, emitter, userName);
            } catch (Exception ignored) {}
            results.add(Map.of("role", "tool", "tool_call_id", tc.getId(), "content",
                    content.length() > cfg.getChat().getToolOutput().getMaxLength() ? content.substring(0, cfg.getChat().getToolOutput().getMaxLength()) + "..." : content));
        }
        return results;
    }

    /**
     * 检测工具调用产生的文件产物，通过 SSE 推送 artifact 事件。
     * 仅处理在工作空间目录内的文件写入。
     */
    private void detectArtifacts(LlmResult.ToolCall tc, SseEmitter emitter, String userName) {
        String toolName = tc.getName();
        Map<String, Object> args = tc.getArguments();

        Set<String> writeTools = Set.of("Write", "write", "write_file", "write_to_file", "create_file", "edit_file");
        if (!writeTools.contains(toolName)) return;

        String filePathStr = null;
        if (args != null) {
            for (String key : args.keySet()) {
                String kl = key.toLowerCase();
                if (kl.contains("file") || kl.contains("path") || kl.equals("target")
                        || kl.equals("destination") || kl.equals("output")) {
                    Object v = args.get(key);
                    if (v instanceof String s && !s.isBlank()) {
                        filePathStr = s;
                        break;
                    }
                }
            }
        }
        if (filePathStr == null || filePathStr.isBlank()) return;

        log.info("[artifact] 检测到产物: tool={}, path={}", toolName, filePathStr);

        // 检查路径是否在工作空间内
        java.nio.file.Path target = java.nio.file.Paths.get(filePathStr);
        if (!java.nio.file.Files.isRegularFile(target)) return;

        try {
            java.nio.file.Path abs = target.toRealPath();
            List<com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity> wss =
                    workspaceDao.queryByUserName(userName);
            for (com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity ws : wss) {
                java.nio.file.Path wsDir = java.nio.file.Paths.get(ws.getPath()).toRealPath();
                if (abs.startsWith(wsDir)) {
                    java.nio.file.Path rel = wsDir.relativize(abs);
                    String relPath = rel.toString().replace('\\', '/');
                    String fileName = abs.getFileName().toString();
                    String mimeType = java.nio.file.Files.probeContentType(abs);
                    if (mimeType == null) {
                        mimeType = fileName.toLowerCase().endsWith(".html") ? "text/html"
                                : fileName.toLowerCase().endsWith(".pdf") ? "application/pdf"
                                : "application/octet-stream";
                    }
                    long size = java.nio.file.Files.size(abs);

                    emitter.send(SseEmitter.event().name("message")
                            .data(ChatEvent.builder()
                                    .type("artifact")
                                    .artifactName(fileName)
                                    .artifactPath(relPath)
                                    .artifactType(mimeType)
                                    .artifactSize(size)
                                    .build()));
                    break;
                }
            }
        } catch (Exception ignored) {}
    }

    /** 清除 NULL byte 并检测/截断二进制内容 */
    private String sanitizeToolOutput(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        // 清除 NULL byte
        String s = raw.replace("\0", "");

        // 采样判断是否为二进制数据
        int sampleLen = Math.min(s.length(), cfg.getChat().getToolOutput().getBinarySampleSize());
        int binaryCount = 0;
        for (int i = 0; i < sampleLen; i++) {
            char c = s.charAt(i);
            // 控制字符（排除常见的空白字符）视为二进制
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                binaryCount++;
            }
        }
        double ratio = (double) binaryCount / sampleLen;

        // 二进制占比超过阈值 → 提取可读片段
        if (ratio > cfg.getChat().getToolOutput().getBinaryThreshold()) {
            StringBuilder result = new StringBuilder();
            StringBuilder buf = new StringBuilder();
            int totalPrintable = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                boolean printable = (c >= 0x20 && c <= 0x7E) || c == '\t' || c == '\n' || c == '\r'
                        || (c >= 0x80 && c <= 0xFF)  // Latin-1 补充
                        || Character.isLetterOrDigit(c) || Character.getType(c) == Character.OTHER_PUNCTUATION;
                if (printable) {
                    buf.append(c);
                    totalPrintable++;
                } else {
                    if (buf.length() >= 20) {
                        if (!result.isEmpty()) result.append("\n...\n");
                        result.append(buf);
                    }
                    buf.setLength(0);
                }
                if (result.length() > cfg.getChat().getToolOutput().getBinaryExtractionLimit()) break;
            }
            if (buf.length() >= 20) {
                if (!result.isEmpty()) result.append("\n...\n");
                result.append(buf);
            }

            if (result.isEmpty()) {
                return "[二进制内容，共 " + s.length() + " 字节，无可读文本片段]";
            }
            return result + "\n\n[二进制内容已省略，共提取 " + totalPrintable + " 个可读字符]";
        }

        return s;
    }

    private String executeUseSkill(String skillName, String userName) {
        String relativePath = skillName.replace(':', '/');

        // 1. 用户私有 skill 优先
        Path userSkillMd = Paths.get(cfg.getSkills().getHome(), userName, relativePath, "SKILL.md");
        String result = readSkillFile(userSkillMd);
        if (result != null) return result;

        // 2. fallback 到共享 skill
        Path sharedSkillMd = Paths.get(cfg.getSkills().getHome(), "share", relativePath, "SKILL.md");
        result = readSkillFile(sharedSkillMd);
        if (result != null) return result;

        return "技能 " + skillName + " 不存在";
    }

    private String readSkillFile(Path skillMd) {
        if (Files.exists(skillMd)) {
            try {
                String raw = Files.readString(skillMd);
                return raw.replaceFirst("(?s)^---\\s*\\n.*?\\n---\\s*\\n", "");
            } catch (IOException e) {
                return "读取技能失败: " + e.getMessage();
            }
        }
        Path parent = skillMd.getParent();
        if (Files.isDirectory(parent)) {
            try {
                java.io.File[] subDirs = parent.toFile().listFiles(java.io.File::isDirectory);
                if (subDirs != null) {
                    for (java.io.File d : subDirs) {
                        Path nested = d.toPath().resolve("SKILL.md");
                        if (Files.exists(nested)) {
                            String raw = Files.readString(nested);
                            return raw.replaceFirst("(?s)^---\\s*\\n.*?\\n---\\s*\\n", "");
                        }
                    }
                }
            } catch (IOException ignored) {}
        }
        return null;
    }

    private String executeUseMemory(String memoryName, String userName) {
        try {
            return memoryService.detail(memoryName, userName).getContent();
        } catch (Exception e) {
            return "记忆不存在: " + e.getMessage();
        }
    }

    private String executeMcpTool(String toolName, Map<String, Object> arguments, String userName) {
        List<com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity> tools =
                mcpConnectionManager.getAllEnabledTools(userName);
        for (com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity t : tools) {
            if (t.getToolName().equals(toolName)) {
                JsonObject args = gson.toJsonTree(arguments).getAsJsonObject();
                return mcpConnectionManager.getConnection(userName, t.getServerId()).callTool(toolName, args);
            }
        }
        return "MCP 工具未找到: " + toolName;
    }

    private String executeSearchKnowledge(Map<String, Object> args, List<String> enabledKbIds) {
        if (enabledKbIds == null || enabledKbIds.isEmpty()) {
            return "{\"message\": \"当前对话未启用任何知识库\"}";
        }
        try {
            String query = args.get("query").toString();
            int topK = 5;
            if (args.containsKey("top_k")) {
                topK = ((Number) args.get("top_k")).intValue();
            }
            List<KnowledgeService.SearchResult> results = knowledgeService.search(query, enabledKbIds, topK);
            if (results.isEmpty()) {
                return "{\"results\": [], \"message\": \"未找到相关文档片段\"}";
            }
            StringBuilder sb = new StringBuilder("{\"results\": [");
            for (int i = 0; i < results.size(); i++) {
                KnowledgeService.SearchResult r = results.get(i);
                if (i > 0) sb.append(",");
                sb.append("{\"content\": \"").append(escapeJson(r.getContent())).append("\",")
                  .append("\"source\": \"").append(escapeJson(r.getSourceFile())).append("\",")
                  .append("\"score\": ").append(String.format("%.4f", r.getScore())).append("}");
            }
            sb.append("]}");
            return sb.toString();
        } catch (Exception e) {
            return "{\"error\": \"知识库检索暂时不可用，请稍后重试: " + escapeJson(e.getMessage()) + "\"}";
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t");
    }

    @Override
    public void cancel(String userName) {
        llmClient.cancelCall(userName);
    }

    @Override
    public Map<String, Object> upload(MultipartFile file, String workspaceId, String userName) {
        if (file.getSize() > cfg.getChat().getUpload().getMaxFileSize()) {
            throw new IllegalArgumentException("文件大小不能超过 " + (cfg.getChat().getUpload().getMaxFileSize() / 1024 / 1024) + "MB");
        }
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId 不能为空");
        }
        com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity ws =
                workspaceDao.queryById(workspaceId);
        if (ws == null) {
            throw new IllegalArgumentException("工作空间不存在");
        }
        if (!ws.getUserName().equals(userName)) {
            throw new IllegalArgumentException("无权访问该工作空间");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            originalName = "untitled";
        }
        String safeName = originalName.replaceAll("[/\\\\:<>\"|?*]", "_");
        long ts = System.currentTimeMillis() / 1000;
        String filename = ts + "_" + safeName;
        Path uploadsDir = Paths.get(ws.getPath(), cfg.getChat().getUpload().getDirectoryName());
        try {
            Files.createDirectories(uploadsDir);
            Path dest = uploadsDir.resolve(filename);
            file.transferTo(dest.toFile());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", cfg.getChat().getUpload().getDirectoryName() + "/" + filename);
            result.put("name", originalName);
            result.put("size", file.getSize());
            return result;
        } catch (IOException e) {
            throw new RuntimeException("文件保存失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Map<String, Object> contextUsage(String userName, String cid, String mode) {
        ContextBuilder.Context ctx = contextBuilder.build(userName, cid, mode != null ? mode : "default");
        int sysTokens = estimateTokens(ctx.getSystemPrompt());
        int histTokens = 0;
        int skillTokens = 0;
        int memTokens = 0;
        for (Map<String, Object> msg : ctx.getMessages()) {
            String role = (String) msg.get("role");
            String content = (String) msg.get("content");
            int t = estimateTokens(content != null ? content : "");
            if ("system".equals(role)) continue;
            else if ("tool".equals(role)) {
                if (t > cfg.getChat().getContext().getSkillTokenThreshold()) skillTokens += t;
                else memTokens += t;
            } else {
                histTokens += t;
            }
        }
        int toolTokens = estimateTokens(gson.toJson(ctx.getTools()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("systemPrompt", sysTokens);
        result.put("history", histTokens);
        result.put("skillContent", skillTokens);
        result.put("memoryContent", memTokens);
        result.put("toolDefinitions", toolTokens);
        result.put("total", sysTokens + histTokens + skillTokens + memTokens + toolTokens);
        return result;
    }

    private int estimateTokens(String text) {
        return (int) Math.ceil(text.length() * cfg.getChat().getContext().getTokenEstimationRatio());
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
