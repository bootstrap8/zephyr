package com.github.hbq969.ai.zephyr.security;

import com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Java 层安全评估器，在 LLM 自评估之外做模式匹配防御。
 * <p>
 * Phase 1：聚焦于高置信度的模式匹配（命令关键词、路径模式）。
 * 复杂语义判定由 LLM 通过 system prompt 中的安全规则自行处理。
 */
@Slf4j
@Component
public class SecurityEvaluator {

    @Resource
    private ZephyrConfigProperties cfg;

    @Resource
    private AuditLogger auditLogger;

    // === HARD BLOCK 模式（代码级确定性检查） ===

    private static final List<Pattern> HARD_BLOCK_SHELL_PATTERNS = List.of(
            Pattern.compile(".*(?:cat|head|tail|read).*(?:\\.env|credentials|private.?key|secret|token|password).*(?:\\||>|curl|http|nc ).*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*chmod\\s+777.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*--no-verify.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*--insecure.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*Set-ExecutionPolicy\\s+Bypass.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*sudoers.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*kubectl\\s+delete\\s+(?:secret|configmap).*", Pattern.CASE_INSENSITIVE)
    );

    private static final List<String> HARD_BLOCK_PATH_PREFIXES = List.of(
            "prompts/security/",
            "prompts/modes/",
            "prompts/tools/"
    );

    // === SOFT BLOCK 模式 ===

    private static final List<Pattern> SOFT_BLOCK_SHELL_PATTERNS = List.of(
            Pattern.compile(".*\\brm\\s+(-[a-zA-Z]*[rf][a-zA-Z]*\\s+)+.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*git\\s+push\\s+.*(?:--force|--force-with-lease).*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*git\\s+(?:reset\\s+--hard|clean\\s+-fdx).*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*(?:curl|wget).*(?:\\||>|bash|sh|python|eval|exec).*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*(?:DROP\\s+(?:TABLE|DATABASE)|TRUNCATE|DELETE\\s+FROM).*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*kubectl\\s+delete.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*docker\\s+(?:rm|stop|kill).*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*(?:kill\\s+-9|pkill).*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*>\\s*\\S+.*", Pattern.CASE_INSENSITIVE)
    );

    /** 评估结果 */
    public enum Decision { ALLOW, CONFIRM, BLOCK }

    public record Result(Decision decision, String rule, String reason) {
        public static Result allow() { return new Result(Decision.ALLOW, "", ""); }
        public static Result confirm(String rule, String reason) { return new Result(Decision.CONFIRM, rule, reason); }
        public static Result block(String rule, String reason) { return new Result(Decision.BLOCK, rule, reason); }
    }

    /**
     * 评估工具调用。仅对 execute_shell 和文件写入类工具做模式匹配，
     * 其余工具调用返回 ALLOW（由 LLM 自评估负责）。
     */
    public Result evaluate(String toolName, Map<String, Object> arguments, String userName) {
        if (!cfg.getSecurity().isEnabled()) {
            return Result.allow();
        }

        Result result = switch (toolName) {
            case "execute_shell" -> evaluateShell(arguments);
            case "write_file", "edit_file" -> evaluateFileWrite(arguments);
            default -> Result.allow();
        };

        // 审计日志
        if (result.decision() != Decision.ALLOW) {
            auditLogger.log("SECURITY_CHECK", toolName, result.decision().name(),
                    result.rule() + ": " + result.reason(), userName);
        }

        return result;
    }

    private Result evaluateShell(Map<String, Object> arguments) {
        if (arguments == null || !arguments.containsKey("command")) {
            return Result.allow();
        }
        Object cmdObj = arguments.get("command");
        if (cmdObj == null) return Result.allow();
        String command = cmdObj.toString().trim();

        // 1. HARD BLOCK 检查
        for (Pattern p : HARD_BLOCK_SHELL_PATTERNS) {
            if (p.matcher(command).matches()) {
                return Result.block("HARD_BLOCK", "命令匹配安全红线规则，禁止执行");
            }
        }

        // 2. SOFT BLOCK 检查
        for (Pattern p : SOFT_BLOCK_SHELL_PATTERNS) {
            if (p.matcher(command).matches()) {
                return Result.confirm("SOFT_BLOCK", "该命令具有破坏性，需要用户确认");
            }
        }

        return Result.allow();
    }

    private Result evaluateFileWrite(Map<String, Object> arguments) {
        String filePath = arguments.getOrDefault("file_path", "").toString();
        if (filePath.isEmpty()) {
            filePath = arguments.getOrDefault("filePath", "").toString();
        }

        // HARD BLOCK：修改安全 prompt 文件（大小写不敏感）
        String lowerPath = filePath.toLowerCase();
        for (String prefix : HARD_BLOCK_PATH_PREFIXES) {
            if (lowerPath.contains(prefix)) {
                return Result.block("HARD_BLOCK", "禁止修改安全规则文件: " + filePath);
            }
        }

        // HARD BLOCK：修改 application.yml
        if (filePath.endsWith("application.yml") || filePath.endsWith("application-me.yml")
                || filePath.endsWith("application-prod.yml")) {
            return Result.block("HARD_BLOCK", "禁止修改应用配置文件: " + filePath);
        }

        return Result.allow();
    }
}
