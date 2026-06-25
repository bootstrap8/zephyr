package com.github.hbq969.ai.zephyr.mcp.utils;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpServerEntity;
import com.github.hbq969.ai.zephyr.mcp.dao.entity.McpToolEntity;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

@Slf4j
public class McpClient {

    private static final Gson gson = new Gson();

    public static List<McpToolEntity> discoverTools(McpServerEntity server) {
        if ("http".equals(server.getTransport())) {
            return discoverHttp(server);
        }
        return discoverStdio(server);
    }

    private static List<McpToolEntity> discoverStdio(McpServerEntity server) {
        List<McpToolEntity> tools = new ArrayList<>();
        Process process = null;
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(server.getCommand());
            String[] argLines = server.getArgs() != null ? server.getArgs().split("\n") : new String[0];
            for (String a : argLines) {
                String trimmed = a.trim();
                if (!trimmed.isEmpty()) cmd.add(trimmed);
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (server.getEnvVars() != null && !server.getEnvVars().isEmpty()) {
                for (String line : server.getEnvVars().split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;
                    int idx = trimmed.indexOf('=');
                    if (idx > 0) {
                        pb.environment().put(trimmed.substring(0, idx), trimmed.substring(idx + 1));
                    }
                }
            }
            process = pb.start();
            final Process proc = process;
            log.info("stdio MCP 进程已启动: server={}, pid={}, cmd={}",
                    server.getName(), proc.pid(), String.join(" ", cmd));

            StringBuilder stderrBuf = new StringBuilder();
            Thread stderrThread = new Thread(() -> {
                try {
                    log.debug("stdio MCP stderr 消费线程启动: server={}, pid={}", server.getName(), proc.pid());
                    try (BufferedReader errReader = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                        String line;
                        int lineCount = 0;
                        while ((line = errReader.readLine()) != null) {
                            stderrBuf.append(line).append("\n");
                            lineCount++;
                        }
                        log.debug("stdio MCP stderr 线程退出: server={}, pid={}, lines={}, alive={}",
                                server.getName(), proc.pid(), lineCount, proc.isAlive());
                    }
                } catch (Exception ignored) {}
            }, "mcp-stderr-" + server.getName());
            stderrThread.setDaemon(true);
            stderrThread.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

            int timeoutSec = MCP_DISCOVER_TIMEOUT_SECONDS;

            // 1. send initialize
            JsonObject initReq = new JsonObject();
            initReq.addProperty(JSONRPC_FIELD, JSONRPC_VERSION);
            initReq.addProperty("id", 1);
            initReq.addProperty("method", MCP_METHOD_INITIALIZE);
            JsonObject initParams = new JsonObject();
            initParams.addProperty("protocolVersion", MCP_PROTOCOL_VERSION);
            JsonObject capabilities = new JsonObject();
            initParams.add("capabilities", capabilities);
            JsonObject clientInfo = new JsonObject();
            clientInfo.addProperty("name", MCP_CLIENT_NAME);
            clientInfo.addProperty("version", MCP_CLIENT_VERSION);
            initParams.add("clientInfo", clientInfo);
            initReq.add("params", initParams);

            String resp = sendAndReadJsonWithTimeout(reader, writer, gson.toJson(initReq), timeoutSec);
            String stderrInfo = stderrBuf.toString().trim();
            if (resp == null) {
                log.warn("stdio MCP initialize 超时({}s): server={}, pid={}, alive={}, stderrLen={}, stderr={}",
                        timeoutSec, server.getName(), proc.pid(), proc.isAlive(), stderrInfo.length(),
                        stderrInfo.isEmpty() ? "(空)" : stderrInfo.substring(0, Math.min(MCP_STDERR_TRUNCATE, stderrInfo.length())));
                return tools;
            }
            if (!resp.contains("\"id\":1")) {
                if (!stderrInfo.isEmpty()) {
                    log.warn("stdio MCP initialize 响应异常: server={}, resp={}, stderr={}", server.getName(),
                            resp.substring(0, Math.min(MCP_RESP_TRUNCATE, resp.length())),
                            stderrInfo.substring(0, Math.min(300, stderrInfo.length())));
                } else {
                    log.warn("stdio MCP initialize 响应异常: server={}, resp={}", server.getName(),
                            resp.substring(0, Math.min(200, resp.length())));
                }
                return tools;
            }

            // 2. send initialized notification
            JsonObject initialized = new JsonObject();
            initialized.addProperty(JSONRPC_FIELD, JSONRPC_VERSION);
            initialized.addProperty("method", "notifications/initialized");
            writeMsg(writer, gson.toJson(initialized));

            // 3. send tools/list
            JsonObject listReq = new JsonObject();
            listReq.addProperty(JSONRPC_FIELD, JSONRPC_VERSION);
            listReq.addProperty("id", 2);
            listReq.addProperty("method", MCP_METHOD_TOOLS_LIST);
            listReq.add("params", new JsonObject());

            resp = sendAndReadJsonWithTimeout(reader, writer, gson.toJson(listReq), timeoutSec);
            if (resp == null) {
                log.warn("stdio MCP tools/list 超时({}s): server={}", timeoutSec, server.getName());
                return tools;
            }
            tools = parseTools(resp);
        } catch (Exception e) {
            log.warn("stdio MCP 连接失败: {} - {}", server.getName(), e.getMessage());
        } finally {
            if (process != null) process.destroyForcibly();
        }
        return tools;
    }

    private static List<McpToolEntity> discoverHttp(McpServerEntity server) {
        List<McpToolEntity> tools = new ArrayList<>();
        try {
            // initialize
            JsonObject initReq = new JsonObject();
            initReq.addProperty(JSONRPC_FIELD, JSONRPC_VERSION);
            initReq.addProperty("id", 1);
            initReq.addProperty("method", MCP_METHOD_INITIALIZE);
            JsonObject initParams = new JsonObject();
            initParams.addProperty("protocolVersion", MCP_PROTOCOL_VERSION);
            JsonObject capabilities = new JsonObject();
            initParams.add("capabilities", capabilities);
            JsonObject clientInfo = new JsonObject();
            clientInfo.addProperty("name", MCP_CLIENT_NAME);
            clientInfo.addProperty("version", MCP_CLIENT_VERSION);
            initParams.add("clientInfo", clientInfo);
            initReq.add("params", initParams);

            String reqJson = gson.toJson(initReq);
            log.info("MCP HTTP init request: {}", reqJson);
            String[] initResult = httpPost(server.getUrl(), server.getHeaders(), reqJson, null);
            String initResp = initResult[0];
            String sessionId = initResult[1];
            log.info("MCP HTTP init response: {}, sessionId: {}", initResp, sessionId);
            if (initResp == null || !initResp.contains("\"id\":1")) return tools;

            // tools/list
            JsonObject listReq = new JsonObject();
            listReq.addProperty(JSONRPC_FIELD, JSONRPC_VERSION);
            listReq.addProperty("id", 2);
            listReq.addProperty("method", MCP_METHOD_TOOLS_LIST);
            listReq.add("params", new JsonObject());

            String[] listResult = httpPost(server.getUrl(), server.getHeaders(), gson.toJson(listReq), sessionId);
            String listResp = listResult[0];
            log.info("MCP HTTP tools/list response: {}", listResp);
            if (listResp != null) {
                tools = parseTools(listResp);
            }
        } catch (Exception e) {
            log.warn("HTTP MCP 连接失败: {} - {}", server.getName(), e.getMessage());
        }
        return tools;
    }

    private static String sendAndReadJsonWithTimeout(BufferedReader reader, BufferedWriter writer, String json, int timeoutSeconds) throws Exception {
        writeMsg(writer, json);
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                return readMsgJson(reader);
            } catch (Exception e) {
                return null;
            }
        });
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return null;
        }
    }

    private static void writeMsg(BufferedWriter writer, String json) throws Exception {
        writer.write(json);
        writer.newLine();
        writer.flush();
    }

    private static String readMsgJson(BufferedReader reader) throws Exception {
        StringBuilder skipped = new StringBuilder();
        for (int i = 0; i < MCP_READ_LOOP_MAX; i++) {
            String line = reader.readLine();
            if (line == null) return null;
            String trimmed = line.trim();
            if (trimmed.startsWith("{")) return trimmed;
            if (!trimmed.isEmpty() && skipped.length() < MCP_STDERR_TRUNCATE) {
                if (!skipped.isEmpty()) skipped.append(" | ");
                skipped.append(trimmed);
            }
        }
        if (!skipped.isEmpty()) {
            log.warn("stdio MCP stdout 非 JSON 行: {}", skipped.toString());
        }
        return null;
    }

    private static String[] httpPost(String url, String headersStr, String json, String sessionId) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();

        if (conn instanceof HttpsURLConnection) {
            HttpsURLConnection sslConn = (HttpsURLConnection) conn;
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }};
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            sslConn.setSSLSocketFactory(sc.getSocketFactory());
            sslConn.setHostnameVerifier((hostname, session) -> true);
        }

        conn.setRequestMethod("POST");
        conn.setRequestProperty(CONTENT_TYPE_HEADER, APPLICATION_JSON);
        conn.setRequestProperty("Accept", ACCEPT_SSE_HEADER);
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(MCP_SSE_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(MCP_SSE_READ_TIMEOUT_MS);

        if (sessionId != null && !sessionId.isEmpty()) {
            conn.setRequestProperty(MCP_SESSION_ID_HEADER, sessionId);
        }

        if (headersStr != null && !headersStr.isEmpty()) {
            for (String line : headersStr.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                int idx = trimmed.indexOf('=');
                if (idx > 0) {
                    conn.setRequestProperty(trimmed.substring(0, idx), trimmed.substring(idx + 1));
                }
            }
        }

        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        String respSessionId = conn.getHeaderField("Mcp-Session-Id");

        if (code >= 200 && code < 300) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
                return new String[]{sb.toString(), respSessionId};
            }
        } else {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                log.warn("MCP HTTP {} response code: {}, body: {}", url, code, sb.toString());
            } catch (Exception ignored) {
                log.warn("MCP HTTP {} response code: {}", url, code);
            }
        }
        return new String[]{null, null};
    }

    public static String extractSSEData(String raw) {
        if (raw == null) return null;
        // SSE format: "data:{json}"
        for (String line : raw.split("\n")) {
            if (line.startsWith(SSE_DATA_PREFIX)) {
                return line.substring(5).trim();
            }
        }
        // plain JSON
        return raw;
    }

    private static List<McpToolEntity> parseTools(String raw) {
        List<McpToolEntity> tools = new ArrayList<>();
        try {
            String json = extractSSEData(raw);
            if (json == null) return tools;
            JsonObject resp = gson.fromJson(json, JsonObject.class);
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
}
