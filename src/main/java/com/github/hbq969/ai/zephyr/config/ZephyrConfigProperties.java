package com.github.hbq969.ai.zephyr.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * zephyr 统一配置，对应 application.yml 中 {@code zephyr} 前缀下所有属性。
 * <p>
 * 每个嵌套类对应 YAML 中的一级节点，字段名直接映射为 YAML key（kebab-case）。
 * 所有字段均提供 Java 默认值，YAML 中的值会覆盖默认值，环境变量也可覆盖（如
 * {@code ZEPHYR_CHAT_UPLOAD_MAX-FILE-SIZE}）。
 */
@Component
@ConfigurationProperties(prefix = "zephyr")
@Data
public class ZephyrConfigProperties {

    /** 对话相关配置 */
    private Chat chat = new Chat();

    /** LLM API 调用配置 */
    private Llm llm = new Llm();

    /** MCP 工具/连接配置 */
    private Mcp mcp = new Mcp();

    /** 模型配置探测相关 */
    private ModelConfig modelConfig = new ModelConfig();

    /** Skill 安装/加载路径 */
    private Skills skills = new Skills();

    /** Memory 存储路径 */
    private Memory memory = new Memory();

    /** 知识库相关配置 */
    private Knowledge knowledge = new Knowledge();

    /** Skill 管理配置（上传/同步） */
    private Skill skill = new Skill();

    /** 加解密配置 */
    private Encrypt encrypt = new Encrypt();

    // ================================================================
    //  对话配置
    // ================================================================

    @Data
    public static class Chat {

        /** 单次对话最多携带的历史消息条数，超出部分截断，默认 200 */
        private int maxHistoryMessages = 200;

        /** 文件上传 */
        private Upload upload = new Upload();

        /** SSE 流式连接 */
        private Sse sse = new Sse();

        /** 工具输出清洗 */
        private ToolOutput toolOutput = new ToolOutput();

        /** 上下文估算 */
        private Context context = new Context();

        @Data
        public static class Upload {
            /** 上传文件大小上限，默认 10MB */
            private long maxFileSize = 10_485_760L;
            /** 上传文件存放目录名（相对于工作空间根目录） */
            private String directoryName = ".zephyr-uploads";
        }

        @Data
        public static class Sse {
            /** SSE 连接超时（毫秒），默认 5 分钟 */
            private long timeoutMillis = 300_000L;
        }

        @Data
        public static class ToolOutput {
            /** tool 结果存入 DB / 传给 LLM 的最大字符数，超出截断 */
            private int maxLength = 8000;
            /** 二进制检测时采样前 N 个字符 */
            private int binarySampleSize = 4096;
            /** 控制字符占比超过此阈值视为二进制数据（0.0 ~ 1.0） */
            private double binaryThreshold = 0.3;
            /** 从二进制内容中提取可读片段的最大字符数 */
            private int binaryExtractionLimit = 6000;
        }

        @Data
        public static class Context {
            /** token 估算系数：字符数 × ratio ≈ token 数 */
            private double tokenEstimationRatio = 0.3;
            /** tool 返回内容超过此 token 数判定为 skill 内容，否则算 memory */
            private int skillTokenThreshold = 1000;
        }
    }

    // ================================================================
    //  LLM API 调用
    // ================================================================

    @Data
    public static class Llm {
        /** OkHttp 客户端配置 */
        private Client client = new Client();

        @Data
        public static class Client {
            /** 建立 TCP 连接超时（秒） */
            private int connectTimeoutSeconds = 30;
            /** 等待响应数据超时（秒） */
            private int readTimeoutSeconds = 120;
        }
    }

    // ================================================================
    //  MCP 工具 & 连接池
    // ================================================================

    @Data
    public static class Mcp {
        /** 工具调用 */
        private Tool tool = new Tool();

        /** 连接池 */
        private Connection connection = new Connection();

        @Data
        public static class Tool {
            /** MCP 工具单次调用超时（秒），超时后强制终止 STDIO 进程 */
            private int timeoutSeconds = 60;
        }

        @Data
        public static class Connection {
            /** 最大同时保持的 MCP 连接数，超出按 LRU 淘汰 */
            private int maxConnections = 100;
            /** 空闲连接超时（毫秒），超过此时间未使用将被回收，默认 15 分钟 */
            private long idleTimeoutMillis = 900_000L;
            /** 空闲连接扫描间隔（毫秒），默认 5 分钟 */
            private long cleanupIntervalMillis = 300_000L;
        }
    }

    // ================================================================
    //  模型配置
    // ================================================================

    @Data
    public static class ModelConfig {
        /** 模型 API 探测 */
        private Api api = new Api();

        @Data
        public static class Api {
            /** 探测模型列表时的连接超时（秒） */
            private int connectTimeoutSeconds = 5;
            /** 探测模型列表时的读取超时（秒） */
            private int readTimeoutSeconds = 5;
        }
    }

    // ================================================================
    //  路径配置
    // ================================================================

    @Data
    public static class Skills {
        /** Skill 安装根目录，默认 ~/.zephyr/skills */
        private String home = System.getProperty("user.home") + "/.zephyr/skills";
    }

    @Data
    public static class Memory {
        /** Memory 存储根目录，默认 ~/.zephyr/memory */
        private String home = System.getProperty("user.home") + "/.zephyr/memory";
    }

    @Data
    public static class Knowledge {
        /** Chroma 向量数据库 */
        private Chroma chroma = new Chroma();
        /** LightRAG 图谱增强配置 */
        private LightRag lightrag = new LightRag();
        /** 文档存储根目录，默认 ~/.zephyr/knowledge */
        private String dataDir = System.getProperty("user.home") + "/.zephyr/knowledge";

        @Data
        public static class Chroma {
            /** 部署模式: embedded 或 server */
            private String mode = "embedded";
            /** chroma 可执行文件路径（embedded 模式需要），默认 "chroma" 走系统 PATH */
            private String binPath = "chroma";
            /** embedded 模式数据目录 */
            private String dataDir = System.getProperty("user.home") + "/.zephyr/chroma";
            /** embedded 模式 HTTP 端口 */
            private int port = 18951;
            /** server 模式 Chroma 地址 */
            private String baseUrl;
        }

        @Data
        public static class LightRag {
            /** 是否启用 LightRAG sidecar，默认 false（未部署时不影响系统运行） */
            private boolean enabled = false;
            /** LightRAG sidecar 地址 */
            private String baseUrl = "http://localhost:9621";
            /** OkHttp 连接超时（秒），默认 10 */
            private int connectTimeoutSeconds = 10;
            /** OkHttp 读取超时（秒），默认 300（索引需调 LLM 抽取实体，耗时较长） */
            private int readTimeoutSeconds = 300;
        }
    }

    @Data
    public static class Skill {
        /** 上传限制 */
        private Upload upload = new Upload();

        /** 平台同步扫描 */
        private Sync sync = new Sync();

        @Data
        public static class Upload {
            /** Skill 上传文件大小上限，默认 100MB */
            private long maxSizeBytes = 104_857_600L;
        }

        @Data
        public static class Sync {
            /** Claude Code 本地 skills 目录 */
            private String claudeSkillsPath = System.getProperty("user.home") + "/.claude/skills";
            /** Codex 本地 skills 目录 */
            private String codexSkillsPath = System.getProperty("user.home") + "/.codex/skills";
            /** OpenCode 本地 skills 目录 */
            private String opencodeSkillsPath = System.getProperty("user.home") + "/.opencode/skills";
        }
    }

    // ================================================================
    //  加解密
    // ================================================================

    @Data
    public static class Encrypt {
        /** RESTful API 加解密 */
        private Restful restful = new Restful();

        @Data
        public static class Restful {
            /** AES 加密参数 */
            private Aes aes = new Aes();

            @Data
            public static class Aes {
                /** AES 密钥，由 h-common 框架注入 */
                private String key;
                /** AES 偏移向量，由 h-common 框架注入 */
                private String iv;
            }
        }
    }
}
