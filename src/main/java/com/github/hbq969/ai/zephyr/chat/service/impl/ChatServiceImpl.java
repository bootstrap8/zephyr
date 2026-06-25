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
import com.github.hbq969.ai.zephyr.chat.service.ConversationSessionManager;
import com.github.hbq969.ai.zephyr.chat.service.BackgroundProcessManager;
import com.github.hbq969.ai.zephyr.security.AuditLogger;
import com.github.hbq969.ai.zephyr.security.SecurityEvaluator;
import com.github.hbq969.ai.zephyr.security.SecurityEvaluator.WorkspaceBoundary;
import com.github.hbq969.ai.zephyr.mcp.utils.McpConnectionManager;
import com.github.hbq969.ai.zephyr.memory.service.MemoryService;
import com.github.hbq969.ai.zephyr.skill.service.SkillService;
import com.github.hbq969.ai.zephyr.workspace.dao.WorkspaceDao;
import com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity;
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
    @Resource
    private ConversationSessionManager sessionManager;
    @Resource
    private BackgroundProcessManager backgroundProcessManager;
    @Resource
    private SecurityEvaluator securityEvaluator;
    @Resource
    private AuditLogger auditLogger;

    // 绕过重试计数器（会话级）
    private final Map<String, Integer> bypassAttempts = new ConcurrentHashMap<>();
    // 待确认请求池
    private final Map<String, ConfirmResult> confirmResults = new ConcurrentHashMap<>();

    private record ConfirmResult(boolean allowed) {}

    @Override
    public SseEmitter send(String userName, String conversationId, String workspaceId,
                           String originalMessage, String mode, List<String> filePaths) {

        // === 同步阶段：解析 conversationId、注册 SessionHandle、创建 SseEmitter ===
        String cid = (conversationId != null && !conversationId.isEmpty())
                ? conversationId
                : UUID.fastUUID().toString(true).substring(0, 12);

        ConversationSessionManager.SessionHandle handle = sessionManager.register(cid, userName);

        SseEmitter emitter = new SseEmitter(cfg.getChat().getSse().getTimeoutMillis());

        emitter.onTimeout(() -> {
            log.info("[会话] SSE 超时 cid={}", cid);
            handle.cancel();
        });
        emitter.onError(th -> {
            log.warn("[会话] SSE 客户端断开 cid={}: {}", cid, th.getMessage());
            handle.cancel();
        });

        // === 异步阶段 ===
        sessionManager.getExecutor().execute(() -> {
            boolean completed = false;
            try {
                long now = System.currentTimeMillis() / 1000;
                long msgSeq = now;

                handle.checkCancel();

                // 0. 预处理斜杠命令
                String message = originalMessage;
                if (message.startsWith("/")) {
                    String handled = handleSlashCommand(message, userName, cid, emitter, now, handle);
                    if (handled == null) {
                        completed = true;
                        return;
                    }
                    message = handled;
                }

                handle.checkCancel();

                // 1. 确保会话存在（cid 已预生成）
                if (conversationId == null || conversationId.isEmpty()) {
                    ConversationEntity conv = new ConversationEntity();
                    conv.setId(cid);
                    conv.setUserName(userName);
                    conv.setTitle(message.length() > 30 ? message.substring(0, 30) : message);
                    conv.setWorkspaceId(workspaceId);
                    conv.setCreatedAt(now);
                    conv.setUpdatedAt(now);
                    chatDao.insertConversation(conv);
                    log.info("[会话] 新建对话 cid={}, user={}, title={}", cid, userName, conv.getTitle());
                    emitter.send(SseEmitter.event().name("message")
                            .data(ChatEvent.builder().type("meta").content(cid).build()));
                }

                handle.checkCancel();

                // 2. 组装上下文
                ContextBuilder.Context ctx = contextBuilder.build(userName, cid, mode != null ? mode : "default");
                List<Map<String, Object>> messages = ctx.getMessages();

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
                            case ".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp" ->
                                    " → 调用 use_skill(\"image-analysis\")";
                            default -> "";
                        };
                        sb.append("- ").append(name).append(skillHint).append("\n");
                        sb.append("  路径: ").append(p).append("\n");
                    }
                    sb.append("\n用户消息: ").append(message);
                    userContent = sb.toString();
                }
                messages.add(Map.of("role", "user", "content", userContent));

                // 3. 持久化 user 消息
                MessageEntity userMsg = new MessageEntity();
                userMsg.setId(UUID.fastUUID().toString(true).substring(0, 12));
                userMsg.setConversationId(cid);
                userMsg.setRole("user");
                userMsg.setContent(message);
                userMsg.setCreatedAt(msgSeq++);
                chatDao.insertMessage(userMsg);

                handle.checkCancel();

                // 4. 工具调用循环
                LlmResult result = null;
                int totalInputTokens = 0, totalOutputTokens = 0, rounds = 0;
                while (true) {
                    handle.checkCancel();
                    result = llmClient.chat(ctx.getModel(), messages, ctx.getTools(), emitter, cid, handle);
                    rounds++;
                    if (result.getUsage() != null) {
                        totalInputTokens += result.getUsage().getOrDefault("inputTokens", 0);
                        totalOutputTokens += result.getUsage().getOrDefault("outputTokens", 0);
                    }

                    handle.touch();

                    if (result.hasToolCalls()) {
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
                        persistAssistantMessage(cid, result, msgSeq++);

                        List<String> enabledKbIds = cid != null ? knowledgeDao.queryKbIdsByConversation(cid) : List.of();
                        List<Map<String, Object>> toolResults = dispatchTools(result.getToolCalls(), userName, enabledKbIds, cid, mode, emitter, handle);
                        handle.touch();

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

                        handle.checkCancel();
                    } else {
                        if (isNotBlank(result.getContent()) || result.hasToolCalls()) {
                            persistAssistantMessage(cid, result, msgSeq++);
                        }
                        if (rounds > 1 || totalInputTokens + totalOutputTokens > 0) {
                            log.info("对话完成 — 共 {} 轮, 输入: {} tokens, 输出: {} tokens, 合计: {} tokens",
                                    rounds, totalInputTokens, totalOutputTokens, totalInputTokens + totalOutputTokens);
                        }
                        emitter.send(SseEmitter.event().name("message")
                                .data(ChatEvent.builder().type("done").build()));
                        completed = true;
                        return;
                    }
                }
            } catch (ConversationSessionManager.CancelSessionException e) {
                log.info("[SSE] 连接已取消 cid={}", cid);
                llmClient.cancelCall(cid);
            } catch (Exception e) {
                boolean disconnected = e instanceof IOException
                        && e.getMessage() != null && e.getMessage().contains("CANCEL");
                if (disconnected) {
                    log.info("[会话] SSE 客户端断开，中断 SSE 流 cid={}", cid);
                } else {
                    log.error("[会话] 异常 cid={}", cid, e);
                    try {
                        emitter.send(SseEmitter.event().name("message")
                                .data(ChatEvent.builder().type("error").content(e.getMessage()).build()));
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
                handle.killTrackedProcesses();
                log.info("[会话] SSE 响应完成 cid={}, completed={}", cid, completed);
                sessionManager.remove(cid);
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
                                      SseEmitter emitter, long now,
                                      ConversationSessionManager.SessionHandle handle) throws IOException {
        String cmd = message.trim();
        int spaceIdx = cmd.indexOf(' ');
        String cmdName = spaceIdx > 0 ? cmd.substring(1, spaceIdx) : cmd.substring(1);
        String cmdArgs = spaceIdx > 0 ? cmd.substring(spaceIdx + 1).trim() : "";

        // 内置命令：直接处理，不走 LLM
        switch (cmdName) {
            case "clear" -> {
                chatDao.deleteMessagesByConvId(cid);
                chatDao.deleteConversation(cid, userName);
                log.info("[会话] /clear 清空对话 cid={}, user={}", cid, userName);
                emitter.send(SseEmitter.event().name("message")
                        .data(ChatEvent.builder().type("clear").build()));
                emitter.send(SseEmitter.event().name("message")
                        .data(ChatEvent.builder().type("done").build()));
                throw new ConversationSessionManager.CancelSessionException(cid);
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

    /**
     * 根据 conversationId 解析对应 workspace 的文件系统边界。
     * 返回 NONE 表示无有效边界（所有文件操作需确认）。
     */
    private WorkspaceBoundary resolveWorkspaceBoundary(String conversationId) {
        if (conversationId == null) return WorkspaceBoundary.NONE;
        try {
            ConversationEntity conv = chatDao.queryConversationById(conversationId);
            if (conv == null || conv.getWorkspaceId() == null) return WorkspaceBoundary.NONE;
            WorkspaceEntity ws = workspaceDao.queryById(conv.getWorkspaceId());
            if (ws == null || ws.getPath() == null) return WorkspaceBoundary.NONE;
            Path wsPath = Path.of(ws.getPath());
            try {
                return new WorkspaceBoundary(wsPath.toRealPath());
            } catch (IOException e) {
                log.warn("[安全] workspace 路径不存在或无法解析，使用规范化形式: {} ({})",
                        wsPath, e.getMessage());
                return new WorkspaceBoundary(wsPath.normalize().toAbsolutePath());
            }
        } catch (Exception e) {
            log.warn("[安全] 解析 workspace 路径失败 conv={}: {}", conversationId, e.getMessage());
            return WorkspaceBoundary.NONE;
        }
    }

    private List<Map<String, Object>> dispatchTools(List<LlmResult.ToolCall> toolCalls,
            String userName, List<String> enabledKbIds, String conversationId, String mode,
            SseEmitter emitter, ConversationSessionManager.SessionHandle handle) {
        List<Map<String, Object>> results = new ArrayList<>();

        // === 一次性解析 workspace 边界（循环外，避免 N 次 DB 查询） ===
        WorkspaceBoundary boundary = resolveWorkspaceBoundary(conversationId);

        for (LlmResult.ToolCall tc : toolCalls) {

            // === 安全评估 ===
            SecurityEvaluator.Result secResult = securityEvaluator.evaluate(
                    tc.getName(), tc.getArguments(), userName, mode, boundary);

            if (secResult.decision() == SecurityEvaluator.Decision.BLOCK) {
                // HARD BLOCK → 拒绝
                log.warn("[安全] HARD_BLOCK cid={}, tool={}, rule={}",
                        conversationId, tc.getName(), secResult.rule());
                results.add(Map.of("role", "tool", "tool_call_id", tc.getId(), "content",
                        "操作被拒绝（安全规则: " + secResult.rule() + "）— " + secResult.reason()));
                continue;
            }

            if (secResult.decision() == SecurityEvaluator.Decision.CONFIRM) {
                // SOFT BLOCK → 推送确认事件，等待用户
                String confirmId = userName + ":" + cn.hutool.core.util.IdUtil.fastSimpleUUID().substring(0, 12);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(ChatEvent.builder()
                                    .type("confirm_action")
                                    .toolName(tc.getName())
                                    .content(gson.toJson(Map.of(
                                            "confirmId", confirmId,
                                            "toolName", tc.getName(),
                                            "toolInput", tc.getArguments(),
                                            "rule", secResult.rule(),
                                            "ruleDetail", secResult.reason()
                                    )))
                                    .build()));
                } catch (IOException e) {
                    log.warn("推送 confirm_action 事件失败: {}", e.getMessage());
                    results.add(Map.of("role", "tool", "tool_call_id", tc.getId(), "content",
                            "操作需确认但推送失败: " + e.getMessage()));
                    continue;
                }

                // 阻塞等待用户确认
                ConfirmResult confirm = waitForUserConfirm(confirmId,
                        cfg.getSecurity().getConfirmTimeoutSeconds());

                if (confirm == null || !confirm.allowed()) {
                    log.info("[安全] 用户拒绝 SOFT_BLOCK cid={}, tool={}",
                            conversationId, tc.getName());
                    auditLogger.log("USER_DENIED", tc.getName(), "DENIED",
                            secResult.rule(), userName);
                    results.add(Map.of("role", "tool", "tool_call_id", tc.getId(), "content",
                            "操作已被用户拒绝: " + secResult.reason()));
                    continue;
                }

                log.info("[安全] 用户确认 SOFT_BLOCK cid={}, tool={}",
                        conversationId, tc.getName());
            }

            // === 执行工具（原有逻辑） ===
            String content;
            try {
                content = switch (tc.getName()) {
                    case "use_skill" -> executeUseSkill(tc.getArguments().get("skill_name").toString(), userName);
                    case "use_memory" -> executeUseMemory(tc.getArguments().get("memory_name").toString(), userName);
                    case "search_knowledge" -> executeSearchKnowledge(tc.getArguments(), enabledKbIds);
                    case "execute_shell" -> executeShell(tc.getArguments(), userName, conversationId);
                    case "list_processes" -> listProcesses(userName);
                    case "kill_process" -> killProcess(tc.getArguments(), userName);
                    default -> executeMcpTool(tc.getName(), tc.getArguments(), userName);
                };
            } catch (Exception e) {
                content = "工具执行错误: " + e.getMessage();
            }
            content = sanitizeToolOutput(content);
            results.add(Map.of("role", "tool", "tool_call_id", tc.getId(), "content",
                    content.length() > cfg.getChat().getToolOutput().getMaxLength()
                            ? content.substring(0, cfg.getChat().getToolOutput().getMaxLength()) + "..."
                            : content));
        }

        // 检查绕过重试
        String cid = conversationId;
        if (!results.isEmpty()) {
            boolean hasBlock = results.stream().anyMatch(r ->
                    r.get("content").toString().contains("操作被拒绝（安全规则"));
            if (hasBlock) {
                int attempts = bypassAttempts.merge(cid, 1, Integer::sum);
                if (attempts >= cfg.getSecurity().getMaxBypassAttempts()) {
                    log.warn("[安全] 连续 {} 次 HARD_BLOCK 绕过尝试，强制终止 cid={}", attempts, cid);
                    bypassAttempts.remove(cid);
                    throw new ConversationSessionManager.CancelSessionException(cid);
                }
            } else {
                bypassAttempts.remove(cid);
            }
        }

        return results;
    }

    /**
     * 阻塞等待用户确认。前端通过 /confirm 端点写入结果。
     */
    private ConfirmResult waitForUserConfirm(String confirmId, int timeoutSeconds) {
        try {
            synchronized (confirmResults) {
                long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    ConfirmResult r = confirmResults.remove(confirmId);
                    if (r != null) return r;
                    confirmResults.wait(Math.min(1000, deadline - System.currentTimeMillis()));
                }
            }
            log.warn("[安全] 确认超时 confirmId={}", confirmId);
            auditLogger.log("TIMEOUT", "", "DENIED", "确认超时", "");
            return null; // 超时 = 拒绝
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** 供 ChatCtrl 调用，写入用户确认结果 */
    @Override
    public void confirm(String confirmId, boolean allowed) {
        synchronized (confirmResults) {
            confirmResults.put(confirmId, new ConfirmResult(allowed));
            confirmResults.notifyAll();
        }
    }


    /**
     * 清除 NULL byte 并检测/截断二进制内容
     */
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
            } catch (IOException ignored) {
            }
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
                // 共享工具使用工具归属用户的连接
                String connUser = t.getUserName().equals(userName) ? userName : t.getUserName();
                JsonObject args = gson.toJsonTree(arguments).getAsJsonObject();
                return mcpConnectionManager.getConnection(connUser, t.getServerId()).callTool(toolName, args);
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
            int topK = cfg.getKnowledge().getTopK();
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
        List<ConversationSessionManager.SessionHandle> handles = sessionManager.getByUser(userName);
        log.info("[SSE] 批量取消连接 user={}, 数量={}", userName, handles.size());
        for (ConversationSessionManager.SessionHandle h : handles) {
            h.cancel();
            llmClient.cancelCall(h.getConversationId());
        }
    }

    @Override
    public void cancelByConversationId(String conversationId) {
        ConversationSessionManager.SessionHandle h = sessionManager.get(conversationId);
        if (h != null) {
            log.info("[SSE] 按 cid 取消连接 cid={}", conversationId);
            h.cancel();
            llmClient.cancelCall(conversationId);
        } else {
            log.info("[SSE] 按 cid 取消连接（无活跃连接） cid={}", conversationId);
        }
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

    private Set<String> shellWhitelist = Set.of();

    @jakarta.annotation.PostConstruct
    void initShellWhitelist() {
        String raw = cfg.getShell().getAllowedCommands();
        if (raw == null || raw.isBlank()) {
            shellWhitelist = Set.of();
        } else {
            shellWhitelist = Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String executeShell(Map<String, Object> args, String userName, String conversationId) {
        String mode = cfg.getShell().getMode();
        if ("disabled".equals(mode)) {
            return "Shell 命令执行已禁用";
        }

        String command = args.get("command").toString().trim();
        if (command.isEmpty()) {
            return "命令不能为空";
        }

        boolean background = args.containsKey("background") && Boolean.TRUE.equals(args.get("background"));

        // 命令白名单校验（程序化硬约束）
        if (!"allowAll".equals(mode)) {
            String cmdName = command.split("\\s+", 2)[0];
            int lastSlash = cmdName.lastIndexOf('/');
            if (lastSlash >= 0) {
                cmdName = cmdName.substring(lastSlash + 1);
            }
            if (!shellWhitelist.contains(cmdName)) {
                String msg = "命令 '" + cmdName + "' 不在白名单中，拒绝执行";
                log.info(msg);
                return msg;
            }
        }

        // 获取工作空间路径
        String workspacePath = System.getProperty("user.home");
        ConversationEntity conv = chatDao.queryConversationById(conversationId);
        if (conv != null && conv.getWorkspaceId() != null) {
            com.github.hbq969.ai.zephyr.workspace.dao.entity.WorkspaceEntity ws =
                    workspaceDao.queryById(conv.getWorkspaceId());
            if (ws != null) {
                workspacePath = ws.getPath();
            }
        }

        ConversationSessionManager.SessionHandle handle = sessionManager.get(conversationId);
        if (handle == null) {
            return "会话不存在，无法执行命令";
        }

        if (background) {
            // 后台模式：检查配额 → 创建日志目录 → shell 层重定向到日志文件 → 启动 → 注册
            backgroundProcessManager.enforceQuota(userName);
            java.nio.file.Path logDir = java.nio.file.Paths.get(workspacePath, ".zephyr-logs");
            try {
                java.nio.file.Files.createDirectories(logDir);
            } catch (java.io.IOException e) {
                return "创建日志目录失败: " + e.getMessage();
            }
            try {
                // 双引号包裹路径防空格，且允许 $$ 展开（单引号会阻止 shell 变量展开）
                Process p = new ProcessBuilder("sh", "-c",
                        "exec >\"" + workspacePath + "/.zephyr-logs/$$.log\" 2>&1; " + command)
                        .directory(new java.io.File(workspacePath))
                        .redirectErrorStream(false)
                        .start();
                long pid = p.pid();
                backgroundProcessManager.register(userName, conversationId, p, command, workspacePath);
                return "PID: " + pid + ", 日志: " + workspacePath + "/.zephyr-logs/" + pid + ".log";
            } catch (Exception e) {
                return "后台命令启动失败: " + e.getMessage();
            }
        } else {
            // 前台模式：ProcessSlot → 启动 → 等待 → 返回结果
            ConversationSessionManager.ProcessSlot slot = handle.reserveProcessSlot(command);
            Process p = null;
            try {
                p = new ProcessBuilder("sh", "-c", command)
                        .directory(new java.io.File(workspacePath))
                        .redirectErrorStream(true)
                        .start();
                slot.bind(p.pid());

                int timeout = cfg.getShell().getCommandTimeoutSeconds();
                boolean timeoutReached = !p.waitFor(timeout, java.util.concurrent.TimeUnit.SECONDS);
                if (timeoutReached) {
                    p.destroyForcibly();
                    return "命令超时（" + timeout + "s），已终止";
                }

                // 限制读取大小，防止 OOM
                int maxBytes = cfg.getShell().getMaxOutputBytes();
                byte[] buf = p.getInputStream().readNBytes(maxBytes + 1);
                boolean truncated = buf.length > maxBytes;
                String output = new String(buf, 0, Math.min(buf.length, maxBytes), java.nio.charset.StandardCharsets.UTF_8);
                if (truncated) {
                    output += "\n\n[输出已截断，超过 " + maxBytes + " 字节]";
                }
                return "退出码: " + p.exitValue() + "\n" + output;
            } catch (Exception e) {
                slot.markFailed();
                if (p != null) {
                    p.destroyForcibly();
                }
                return "命令执行异常: " + e.getMessage();
            }
        }
    }

    private String listProcesses(String userName) {
        List<BackgroundProcessManager.TrackedProcess> list = backgroundProcessManager.list(userName);
        if (list.isEmpty()) {
            return "当前没有后台进程";
        }
        StringBuilder sb = new StringBuilder("后台进程列表:\n");
        for (BackgroundProcessManager.TrackedProcess tp : list) {
            sb.append("PID: ").append(tp.getPid())
                    .append(", 命令: ").append(tp.getCommand())
                    .append(", 启动时间: ").append(tp.getStartedAt())
                    .append("\n");
        }
        return sb.toString();
    }

    private String killProcess(Map<String, Object> args, String userName) {
        long pid = ((Number) args.get("pid")).longValue();
        boolean killed = backgroundProcessManager.kill(userName, pid);
        return killed ? "进程 " + pid + " 已终止" : "进程 " + pid + " 未找到或已结束";
    }
}
