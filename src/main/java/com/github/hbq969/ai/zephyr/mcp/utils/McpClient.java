package com.github.hbq969.ai.zephyr.mcp.utils;

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
            Process process = pb.start();

            // 消费 stderr，避免缓冲区满导致进程阻塞
            StringBuilder stderrBuf = new StringBuilder();
            Thread stderrThread = new Thread(() -> {
                try (BufferedReader errReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = errReader.readLine()) != null) {
                        stderrBuf.append(line).append("\n");
                    }
                } catch (Exception ignored) {}
            });
            stderrThread.setDaemon(true);
            stderrThread.start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

            // 1. send initialize
            JsonObject initReq = new JsonObject();
            initReq.addProperty("jsonrpc", "2.0");
            initReq.addProperty("id", 1);
            initReq.addProperty("method", "initialize");
            JsonObject initParams = new JsonObject();
            initParams.addProperty("protocolVersion", "2024-11-05");
            JsonObject capabilities = new JsonObject();
            initParams.add("capabilities", capabilities);
            JsonObject clientInfo = new JsonObject();
            clientInfo.addProperty("name", "zephyr");
            clientInfo.addProperty("version", "1.0.0");
            initParams.add("clientInfo", clientInfo);
            initReq.add("params", initParams);

            String resp = sendAndReadJson(reader, writer, gson.toJson(initReq));
            if (resp == null || !resp.contains("\"id\":1")) {
                process.destroyForcibly();
                String errInfo = stderrBuf.toString().trim();
                if (!errInfo.isEmpty()) {
                    log.warn("stdio MCP initialize 失败，stderr: {}", errInfo.substring(0, Math.min(500, errInfo.length())));
                }
                return tools;
            }

            // 2. send initialized notification
            JsonObject initialized = new JsonObject();
            initialized.addProperty("jsonrpc", "2.0");
            initialized.addProperty("method", "notifications/initialized");
            writeMsg(writer, gson.toJson(initialized));

            // 3. send tools/list
            JsonObject listReq = new JsonObject();
            listReq.addProperty("jsonrpc", "2.0");
            listReq.addProperty("id", 2);
            listReq.addProperty("method", "tools/list");
            listReq.add("params", new JsonObject());

            resp = sendAndReadJson(reader, writer, gson.toJson(listReq));
            if (resp != null) {
                tools = parseTools(resp);
            }

            process.destroyForcibly();
        } catch (Exception e) {
            log.warn("stdio MCP 连接失败: {} - {}", server.getName(), e.getMessage());
        }
        return tools;
    }

    private static List<McpToolEntity> discoverHttp(McpServerEntity server) {
        List<McpToolEntity> tools = new ArrayList<>();
        try {
            // initialize
            JsonObject initReq = new JsonObject();
            initReq.addProperty("jsonrpc", "2.0");
            initReq.addProperty("id", 1);
            initReq.addProperty("method", "initialize");
            JsonObject initParams = new JsonObject();
            initParams.addProperty("protocolVersion", "2024-11-05");
            JsonObject capabilities = new JsonObject();
            initParams.add("capabilities", capabilities);
            JsonObject clientInfo = new JsonObject();
            clientInfo.addProperty("name", "zephyr");
            clientInfo.addProperty("version", "1.0.0");
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
            listReq.addProperty("jsonrpc", "2.0");
            listReq.addProperty("id", 2);
            listReq.addProperty("method", "tools/list");
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

    private static String sendAndReadJson(BufferedReader reader, BufferedWriter writer, String json) throws Exception {
        writeMsg(writer, json);
        return readMsgJson(reader);
    }

    private static void writeMsg(BufferedWriter writer, String json) throws Exception {
        writer.write(json);
        writer.newLine();
        writer.flush();
    }

    /** 读取一行 JSON-RPC 响应，自动跳过非 JSON 行（如 MCP 服务器误打到 stdout 的日志） */
    private static String readMsgJson(BufferedReader reader) throws Exception {
        for (int i = 0; i < 200; i++) {
            String line = reader.readLine();
            if (line == null) return null;
            String trimmed = line.trim();
            if (trimmed.startsWith("{")) return trimmed;
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
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json, text/event-stream");
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);

        if (sessionId != null && !sessionId.isEmpty()) {
            conn.setRequestProperty("Mcp-Session-Id", sessionId);
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
            if (line.startsWith("data:")) {
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
