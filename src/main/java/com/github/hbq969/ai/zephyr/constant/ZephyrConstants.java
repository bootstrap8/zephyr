package com.github.hbq969.ai.zephyr.constant;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 项目级常量，消除散落在各文件中的魔法值。
 * 按域名分组，每个常量标注用途和来源文件。
 */
public final class ZephyrConstants {

    private ZephyrConstants() {}

    // === 用户默认值 ===
    public static final String DEFAULT_USERNAME = "admin";
    /** 系统 workspace 所属内部用户名，不依赖请求上下文 */
    public static final String SYSTEM_USERNAME = "system";
    /** 系统 workspace 标记键 */
    public static final String KEY_IS_SYSTEM = "isSystem";
    public static final String DEFAULT_AVATAR = "A";

    // === 通用响应 ===
    public static final String RESPONSE_SUCCESS = "ok";

    // === 短ID ===
    public static final int SHORT_ID_LENGTH = 12;

    // === 模式 ===
    public static final String MODE_DEFAULT = "default";
    public static final String MODE_ACCEPT_EDITS = "acceptEdits";
    public static final String MODE_BYPASS = "bypass";

    // === 消息角色 ===
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_USER = "user";
    public static final String ROLE_TOOL = "tool";

    // === 工具调用 ===
    public static final String TOOL_CALL_TYPE_FUNCTION = "function";

    // === 模型类型 ===
    public static final String MODEL_TYPE_LLM = "llm";
    public static final String MODEL_TYPE_EMBEDDING = "embedding";

    // === 作用域 ===
    public static final String SCOPE_USER = "user";
    public static final String SCOPE_SHARED = "shared";

    // === 认证 ===
    public static final String BEARER_PREFIX = "Bearer ";

    // === HTTP 头 ===
    public static final String AUTHORIZATION_HEADER = "Authorization";
    public static final String CONTENT_TYPE_HEADER = "Content-Type";

    // === HTTP 状态码 ===
    public static final int HTTP_STATUS_UNAUTHORIZED = 401;
    public static final int HTTP_STATUS_NOT_FOUND = 404;

    // === Media 类型 ===
    public static final String APPLICATION_JSON = "application/json";
    public static final String MEDIA_TYPE_JSON_UTF8 = "application/json; charset=utf-8";

    // === SSE ===
    public static final String SSE_DATA_PREFIX = "data: ";
    public static final String SSE_DONE_MARKER = "[DONE]";

    /** SSE event 名称：流式 token */
    public static final String SSE_EVENT_MESSAGE = "message";
    /** SSE event 名称：错误 */
    public static final String SSE_EVENT_ERROR = "error";
    /** SSE event 名称：增量 token */
    public static final String SSE_EVENT_TOKEN = "token";
    /** SSE event 名称：思考过程 */
    public static final String SSE_EVENT_THINKING = "thinking";
    /** SSE event 名称：工具调用 */
    public static final String SSE_EVENT_TOOL_CALL = "tool_call";
    /** SSE event 名称：用量统计 */
    public static final String SSE_EVENT_USAGE = "usage";
    /** SSE event 名称：操作确认 */
    public static final String SSE_EVENT_CONFIRM_ACTION = "confirm_action";

    // === 工具名称 ===
    public static final String TOOL_USE_SKILL = "use_skill";
    public static final String TOOL_USE_MEMORY = "use_memory";
    public static final String TOOL_SEARCH_KNOWLEDGE = "search_knowledge";
    public static final String TOOL_EXECUTE_SHELL = "execute_shell";
    public static final String TOOL_LIST_PROCESSES = "list_processes";
    public static final String TOOL_KILL_PROCESS = "kill_process";
    public static final String TOOL_WRITE_FILE = "write_file";

    // === tool_call 参数名 ===
    public static final String PARAM_SKILL_NAME = "skill_name";
    public static final String PARAM_MEMORY_NAME = "memory_name";
    public static final String PARAM_QUERY = "query";
    public static final String PARAM_TOP_K = "top_k";
    public static final String PARAM_COMMAND = "command";
    public static final String PARAM_BACKGROUND = "background";
    public static final String PARAM_PID = "pid";

    // === API 路径 ===
    public static final String CHAT_COMPLETIONS_PATH = "v1/chat/completions";
    public static final String EMBEDDINGS_API_PATH = "/v1/embeddings";
    public static final String MODELS_API_PATH = "/v1/models";

    // === Usage 键 ===
    public static final String USAGE_INPUT_TOKENS = "inputTokens";
    public static final String USAGE_OUTPUT_TOKENS = "outputTokens";
    public static final String USAGE_KEY = "usage";

    // === Stream 请求参数 ===
    public static final String STREAM_INCLUDE_USAGE = "include_usage";

    // === Finish Reason ===
    public static final String FINISH_REASON_TOOL_CALLS = "tool_calls";

    // === Shell ===
    public static final String SHELL_MODE_DISABLED = "disabled";
    public static final String SHELL_MODE_ALLOW_ALL = "allowAll";
    public static final String SHELL_COMMAND = "sh";
    public static final String SHELL_COMMAND_ARG = "-c";

    /** Shell 白名单分隔符（逗号） */
    public static final String SHELL_WHITELIST_DELIMITER = ",";

    // === 安全规则类型 ===
    public static final String RULE_TYPE_SHELL_ALLOWED = "SHELL_ALLOWED";
    public static final String RULE_TYPE_DEFAULT_ALLOW = "DEFAULT_ALLOW";
    public static final String RULE_TYPE_HARD_BLOCK = "HARD_BLOCK";
    public static final String RULE_TYPE_SOFT_BLOCK = "SOFT_BLOCK";

    // === 文件扩展名 ===
    public static final String EXT_PDF = ".pdf";
    public static final String EXT_XLSX = ".xlsx";
    public static final String EXT_XLS = ".xls";
    public static final String EXT_DOCX = ".docx";
    public static final String EXT_DOC = ".doc";
    public static final String EXT_PPTX = ".pptx";
    public static final String EXT_PPT = ".ppt";
    public static final String EXT_PNG = ".png";
    public static final String EXT_JPG = ".jpg";
    public static final String EXT_JPEG = ".jpeg";
    public static final String EXT_GIF = ".gif";
    public static final String EXT_SVG = ".svg";
    public static final String EXT_WEBP = ".webp";
    public static final String EXT_MD = ".md";
    public static final String EXT_LOG = ".log";
    public static final String EXT_SQL = ".sql";
    public static final String EXT_ZIP = ".zip";
    public static final String EXT_TAR = ".tar";
    public static final String EXT_TAR_GZ = ".tar.gz";
    public static final String EXT_TGZ = ".tgz";
    public static final String EXT_PID = ".pid";

    /** 压缩包扩展名集合 */
    public static final Set<String> ARCHIVE_EXTENSIONS = Set.of(EXT_ZIP, EXT_TAR_GZ, EXT_TGZ, EXT_TAR);

    // === 文件名 ===
    public static final String SKILL_FILE_NAME = "SKILL.md";
    public static final String MEMORY_INDEX_FILE = "MEMORY.md";

    // === 目录名 ===
    public static final String SHARE_DIR_NAME = "share";
    public static final String ZEPHYR_DIR = ".zephyr";
    public static final String ZEPHYR_LOGS_DIR = ".zephyr-logs";
    public static final String MCP_PIDS_DIR = ".zephyr/mcp-pids";

    // === 文件上传 skill 映射 ===
    /** 文件扩展名 → use_skill 参数名映射 */
    public static final String SKILL_PARAM_PDF = "pdf";
    public static final String SKILL_PARAM_XLSX = "xlsx";
    public static final String SKILL_PARAM_DOCX = "docx";
    public static final String SKILL_PARAM_PPTX = "pptx";
    public static final String SKILL_PARAM_IMAGE = "image";

    // === JSON Schema ===
    public static final String JSON_TYPE_OBJECT = "object";

    // === JSON-RPC (MCP) ===
    public static final String JSONRPC_FIELD = "jsonrpc";
    public static final String JSONRPC_VERSION = "2.0";
    public static final String MCP_PROTOCOL_VERSION = "2024-11-05";
    public static final String MCP_CLIENT_NAME = "zephyr";
    public static final String MCP_CLIENT_VERSION = "1.0.0";
    public static final String MCP_METHOD_INITIALIZE = "initialize";
    public static final String MCP_METHOD_TOOLS_LIST = "tools/list";
    public static final String MCP_SESSION_ID_HEADER = "Mcp-Session-Id";

    // === MCP 数字约定 ===
    public static final int MCP_REQUEST_ID_INIT = 100;
    public static final int MCP_READ_LOOP_MAX = 200;
    public static final int MCP_DISCOVER_TIMEOUT_SECONDS = 30;
    public static final int MCP_STDERR_TRUNCATE = 500;
    public static final int MCP_RESP_TRUNCATE = 200;

    // === MCP 传输/状态 ===
    public static final String TRANSPORT_STDIO = "stdio";
    public static final String STATUS_DISCONNECTED = "disconnected";
    public static final String STATUS_CONNECTED = "connected";
    public static final String STATUS_ERROR = "error";

    // === MCP 连接 HTTP ===
    public static final int MCP_HTTP_CONNECT_TIMEOUT_MS = 10000;
    public static final int MCP_HTTP_READ_TIMEOUT_MS = 30000;
    public static final int MCP_SSE_CONNECT_TIMEOUT_MS = 8000;
    public static final int MCP_SSE_READ_TIMEOUT_MS = 15000;

    // === SSE Accept 头 ===
    public static final String ACCEPT_SSE_HEADER = "application/json, text/event-stream";

    // === RRF ===
    public static final int RRF_DEFAULT_K = 60;

    // === 文本处理 ===
    public static final int DEFAULT_CHUNK_SIZE = 800;
    public static final int DEFAULT_CHUNK_OVERLAP = 150;
    public static final int MIN_CHUNK_CODE_POINTS = 20;

    // === 应用配置文件（安全规则 HARD BLOCK） ===
    public static final List<String> APP_CONFIG_FILES = List.of(
            "application.yml", "application-me.yml", "application-prod.yml", "application-test.yml");

    // === 安全路径前缀 ===
    public static final List<String> SECURITY_PATH_PREFIXES = List.of(
            "prompts/security/", "prompts/modes/", "prompts/tools/");

    // === API Key 掩码 ===
    public static final int MASK_THRESHOLD_LENGTH = 8;
    public static final int MASK_PREFIX_LENGTH = 3;
    public static final int MASK_SUFFIX_LENGTH = 4;
    public static final String MASK_STRING = "****";

    // === 来源 ===
    public static final String SOURCE_MANUAL = "manual";

    // === 标题 ===
    public static final int TITLE_MAX_LENGTH = 30;
    public static final String DEFAULT_CONVERSATION_TITLE = "新对话";

    // === 会话 ===
    public static final int SHUTDOWN_AWAIT_SECONDS = 5;

    // === 二进制检测 ===
    public static final int MIN_PRINTABLE = 0x20;
    public static final int MAX_PRINTABLE = 0x7E;
    public static final int MIN_LATIN1 = 0x80;
    public static final int MAX_LATIN1 = 0xFF;
    public static final int MIN_PRINTABLE_RUN_LENGTH = 20;

    // === 轮询等待 ===
    public static final int WAIT_POLL_INTERVAL_MS = 1000;

    // === 文件名清理 ===
    public static final Pattern FILENAME_SANITIZE_REGEX = Pattern.compile("[/\\\\:<>\"|?*]");
    public static final String DEFAULT_FILENAME = "untitled";

    // === Frontmatter ===
    public static final Pattern FRONTMATTER_REGEX = Pattern.compile("(?s)^---\\s*\\n.*?\\n---\\s*\\n");

    // === 正则表达式（文本处理） ===
    public static final Pattern CONTROL_CHAR_PATTERN = Pattern.compile("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f-\\x9f]");
    public static final Pattern MULTI_SPACE_PATTERN = Pattern.compile("[ \\t]+");
    public static final Pattern MULTI_NEWLINE_PATTERN = Pattern.compile("\\n{3,}");
    public static final Pattern MEANINGLESS_LINE_PATTERN = Pattern.compile("^[\\p{P}\\p{S}\\d\\s\\p{Z}]*$");

    // === Chroma ===
    public static final int CHROMA_CONNECT_TIMEOUT_SECONDS = 10;
    public static final int CHROMA_READ_TIMEOUT_SECONDS = 60;
    public static final String CHROMA_RUN_COMMAND = "run";
    public static final String CHROMA_PATH_ARG = "--path";
    public static final String CHROMA_PORT_ARG = "--port";
    public static final int CHROMA_STARTUP_DELAY_MS = 2000;
    public static final int CHROMA_BATCH_SIZE = 100;

    // === 知识库 ===
    public static final int KNOWLEDGE_TOP_K_DEFAULT = 5;
    public static final int KNOWLEDGE_BM25_K1_DEFAULT = 1;

    // === Git ===
    public static final String GIT_COMMAND = "git";
    public static final String GIT_CLONE_DEPTH = "1";
    public static final String DEFAULT_GIT_BRANCH = "main";

    // === Shell 退出码前缀 ===
    public static final String EXIT_CODE_PREFIX = "退出码: ";
    public static final String SHELL_TIMEOUT_PREFIX_CN = "命令超时（";
    public static final String SHELL_TIMEOUT_SUFFIX_CN = "s），已终止";

    // === 确认操作 ===
    public static final String ACTION_ALLOW = "allow";
    public static final String ACTION_DENY = "deny";
    public static final String KEY_CONFIRM_ID = "confirmId";
    public static final String KEY_ACTION = "action";

    // === 记忆类型 ===
    public static final String MEMORY_TYPE_USER = "user";
    public static final String MEMORY_TYPE_PROJECT = "project";
}
