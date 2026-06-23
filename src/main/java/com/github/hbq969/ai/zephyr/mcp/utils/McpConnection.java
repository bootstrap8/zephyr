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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private final int toolTimeoutSeconds;

    // stdio
    private Process process;
    private BufferedReader reader;
    private BufferedWriter writer;

    // http
    private String sessionId;

    private McpConnection(Type type, McpServerEntity server, int toolTimeoutSeconds) {
        this.type = type;
        this.server = server;
        this.toolTimeoutSeconds = toolTimeoutSeconds;
    }

    public static McpConnection create(McpServerEntity server, int toolTimeoutSeconds) {
        McpConnection conn = new McpConnection(
                "http".equals(server.getTransport()) ? Type.HTTP : Type.STDIO,
                server,
                toolTimeoutSeconds
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
        Path pidFile = pidFilePath();
        try {
            Files.createDirectories(pidFile.getParent());
            Files.writeString(pidFile, String.valueOf(process.pid()));
            log.info("MCP PID 已记录: server={}, pid={}", server.getName(), process.pid());
        } catch (Exception e) {
            log.warn("写入 MCP PID 文件失败: {}", pidFile, e);
        }
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

    public List<McpToolEntity> listTools() {
        List<McpToolEntity> tools = new java.util.ArrayList<>();
        try {
            String resp;
            if (type == Type.STDIO) {
                JsonObject req = new JsonObject();
                req.addProperty("jsonrpc", "2.0");
                req.addProperty("id", requestId.incrementAndGet());
                req.addProperty("method", "tools/list");
                req.add("params", new JsonObject());
                resp = sendAndRead(gson.toJson(req));
            } else {
                JsonObject req = new JsonObject();
                req.addProperty("jsonrpc", "2.0");
                req.addProperty("id", requestId.incrementAndGet());
                req.addProperty("method", "tools/list");
                req.add("params", new JsonObject());
                resp = _httpPost(gson.toJson(req));
            }
            if (resp != null) {
                tools = parseTools(resp);
            }
        } catch (Exception e) {
            log.warn("MCP listTools 失败: server={}, error={}", server.getName(), e.getMessage());
            throw new RuntimeException("获取工具列表失败: " + e.getMessage(), e);
        }
        return tools;
    }

    private List<McpToolEntity> parseTools(String raw) {
        List<McpToolEntity> tools = new java.util.ArrayList<>();
        try {
            if (raw == null) return tools;
            JsonObject resp = gson.fromJson(raw, JsonObject.class);
            if (resp.has("result")) {
                JsonObject result = resp.getAsJsonObject("result");
                if (result.has("tools")) {
                    JsonArray arr = result.getAsJsonArray("tools");
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject t = arr.get(i).getAsJsonObject();
                        McpToolEntity entity = new McpToolEntity();
                        entity.setToolName(t.has("name") ? t.get("name").getAsString() : "");
                        entity.setDescription(t.has("description") ? t.get("description").getAsString() : "");
                        if (t.has("inputSchema")) {
                            entity.setParametersJson(gson.toJson(t.get("inputSchema")));
                        }
                        entity.setSource("discovered");
                        entity.setEnabled(1);
                        tools.add(entity);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 tools/list 响应失败: {}", e.getMessage());
        }
        return tools;
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
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                for (int i = 0; i < 200; i++) {
                    String line = reader.readLine();
                    if (line == null) return null;
                    String trimmed = line.trim();
                    if (trimmed.startsWith("{")) return trimmed;
                }
                return null;
            } catch (Exception e) {
                return null;
            }
        });
        try {
            return future.get(toolTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("STDIO MCP 工具执行超时({}s)，强制关闭进程: server={}, pid={}",
                    toolTimeoutSeconds, server.getName(), process != null ? process.pid() : -1);
            future.cancel(true);
            if (process != null) {
                killProcessTree();
                try { Files.deleteIfExists(pidFilePath()); } catch (Exception ignored) {}
            }
            return null;
        }
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

    private Path pidFilePath() {
        return Paths.get(System.getProperty("user.home"), ".zephyr/mcp-pids",
                server.getUserName() + "-" + server.getId() + ".pid");
    }

    private void touch() {
        this.lastUsedAt = System.currentTimeMillis();
    }

    private void killProcessTree() {
        long pid = process.pid();
        ProcessHandle.of(pid).ifPresent(ph -> {
            List<ProcessHandle> children = ph.descendants().toList();
            log.info("MCP 进程树: server={}, parentPid={}, childrenPids={}",
                    server.getName(), pid, children.stream().map(c -> String.valueOf(c.pid())).toList());
            children.forEach(ProcessHandle::destroyForcibly);
            ph.destroyForcibly();
        });
    }

    public void close() {
        try {
            if (type == Type.STDIO && process != null) {
                long pid = process.pid();
                killProcessTree();
                try { Files.deleteIfExists(pidFilePath()); } catch (Exception ignored) {}
                log.info("MCP 进程已终止: server={}, pid={}", server.getName(), pid);
            }
        } catch (Exception ignored) {}
    }
}
