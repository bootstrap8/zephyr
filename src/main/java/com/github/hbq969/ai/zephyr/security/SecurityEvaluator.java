package com.github.hbq969.ai.zephyr.security;

import cn.hutool.core.lang.PatternPool;
import cn.hutool.core.util.ReUtil;
import com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;

/**
 * Java 层安全评估器，在 LLM 自评估之外做模式匹配防御。
 * <p>
 * HARD/SOFT BLOCK 规则从 {@code application.yml} 的 {@code zephyr.security.hard-block} /
 * {@code zephyr.security.soft-block} 读取，规则变更后需重启生效。
 * 配置为空或缺失时 fallback 到代码内置默认规则。
 */
@Slf4j
@Component
public class SecurityEvaluator {

    @Resource
    private ZephyrConfigProperties cfg;

    @Resource
    private AuditLogger auditLogger;

    // === 代码内置默认规则（配置为空时 fallback） ===

    private static final List<String> DEFAULT_HARD_BLOCK_SHELL_PATTERNS = List.of(
            ".*(?:cat|head|tail|read).*(?:\\.env|credentials|private.?key|secret|token|password).*(?:\\||>|curl|http|nc ).*",
            ".*chmod\\s+777.*",
            ".*--no-verify.*",
            ".*--insecure.*",
            ".*Set-ExecutionPolicy\\s+Bypass.*",
            ".*sudoers.*",
            ".*kubectl\\s+delete\\s+(?:secret|configmap).*"
    );

    private static final List<String> DEFAULT_HARD_BLOCK_PATH_PREFIXES = List.of(
            "prompts/security/",
            "prompts/modes/",
            "prompts/tools/"
    );

    private static final List<String> DEFAULT_SOFT_BLOCK_SHELL_PATTERNS = List.of(
            ".*\\brm\\s+(-[a-zA-Z]*[rf][a-zA-Z]*\\s+)+.*",
            ".*git\\s+push\\s+.*(?:--force|--force-with-lease).*",
            ".*git\\s+(?:reset\\s+--hard|clean\\s+-fdx).*",
            ".*(?:curl|wget).*(?:\\||>|bash|sh|python|eval|exec).*",
            ".*(?:DROP\\s+(?:TABLE|DATABASE)|TRUNCATE|DELETE\\s+FROM).*",
            ".*kubectl\\s+delete.*",
            ".*docker\\s+(?:rm|stop|kill).*",
            ".*(?:kill\\s+-9|pkill).*",
            ".*>\\s*\\S+.*"
    );

    // === 运行时编译后的 Pattern 缓存 ===

    private List<Pattern> hardBlockShellPatterns;
    private List<String> hardBlockPathPrefixes;
    private List<Pattern> softBlockShellPatterns;

    // === 安全规则标识常量 ===

    public static final String RULE_WORKSPACE_BOUNDARY = "WORKSPACE_BOUNDARY";
    public static final String RULE_MODE_DEFAULT = "MODE_DEFAULT";

    /** 评估结果 */
    public enum Decision { ALLOW, CONFIRM, BLOCK }

    public record Result(Decision decision, String rule, String reason) {
        public static Result allow() { return new Result(Decision.ALLOW, "", ""); }
        public static Result confirm(String rule, String reason) { return new Result(Decision.CONFIRM, rule, reason); }
        public static Result block(String rule, String reason) { return new Result(Decision.BLOCK, rule, reason); }
    }

    public record WorkspaceBoundary(Path path) {
        public static final WorkspaceBoundary NONE = new WorkspaceBoundary(null);

        public boolean isPresent() { return path != null; }

        public boolean contains(Path target) {
            return isPresent() && target.startsWith(path);
        }

        public Path resolveTarget(String filePath) {
            Path targetPath = Path.of(filePath);
            if (!targetPath.isAbsolute()) {
                targetPath = path.resolve(targetPath);
            }
            return targetPath.normalize();
        }
    }

    private Set<String> readOnlyCommands;

    @jakarta.annotation.PostConstruct
    void init() {
        initReadOnlyCommands();
        initPatterns();
    }

    private void initReadOnlyCommands() {
        readOnlyCommands = parseCommandList(cfg.getSecurity().getDefaultAllowCommands());
    }

    private void initPatterns() {
        ZephyrConfigProperties.Security.HardBlock hb = cfg.getSecurity().getHardBlock();
        ZephyrConfigProperties.Security.SoftBlock sb = cfg.getSecurity().getSoftBlock();

        hardBlockShellPatterns = compileShellPatterns(
                hb.getShellPatterns(), hb.getMergeMode(), DEFAULT_HARD_BLOCK_SHELL_PATTERNS, "HARD_BLOCK");
        hardBlockPathPrefixes = mergePathPrefixes(
                hb.getPathPrefixes(), hb.getPathMergeMode(), DEFAULT_HARD_BLOCK_PATH_PREFIXES, "HARD_BLOCK");
        softBlockShellPatterns = compileShellPatterns(
                sb.getShellPatterns(), sb.getMergeMode(), DEFAULT_SOFT_BLOCK_SHELL_PATTERNS, "SOFT_BLOCK");

        log.info("规则加载完成 — HARD_BLOCK shell: {} 条, path: {} 条; SOFT_BLOCK shell: {} 条",
                hardBlockShellPatterns.size(), hardBlockPathPrefixes.size(), softBlockShellPatterns.size());
    }

    private List<Pattern> compileShellPatterns(List<String> configPatterns, ZephyrConfigProperties.Security.MergeMode mode,
                                                List<String> defaults, String ruleType) {
        List<String> merged = mergeLists(configPatterns, mode, defaults);
        List<Pattern> result = new ArrayList<>();
        for (String raw : merged) {
            try {
                Pattern p = PatternPool.get(raw, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                result.add(p);
            } catch (PatternSyntaxException e) {
                log.warn("{} 规则正则非法，已跳过: [{}], 错误: {}", ruleType, raw, e.getMessage());
            }
        }
        return result;
    }

    private List<String> mergePathPrefixes(List<String> configPaths, ZephyrConfigProperties.Security.MergeMode mode,
                                            List<String> defaults, String ruleType) {
        return mergeLists(configPaths, mode, defaults);
    }

    private List<String> mergeLists(List<String> configList, ZephyrConfigProperties.Security.MergeMode mode,
                                     List<String> defaults) {
        if (mode == ZephyrConfigProperties.Security.MergeMode.REPLACE) {
            return configList.isEmpty() ? new ArrayList<>(defaults) : new ArrayList<>(configList);
        }
        // EXTEND: 默认规则 + 用户配置追加
        List<String> merged = new ArrayList<>(defaults);
        merged.addAll(configList);
        return merged;
    }

    public static Set<String> parseCommandList(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * 评估工具调用。仅对 execute_shell 和文件写入类工具做模式匹配，
     * 其余工具调用返回 ALLOW（由 LLM 自评估负责）。
     *
     * @param mode 权限模式：default | acceptEdits | bypass
     */
    public Result evaluate(String toolName, Map<String, Object> arguments, String userName, String mode,
                           WorkspaceBoundary boundary) {
        if (!cfg.getSecurity().isEnabled()) {
            return Result.allow();
        }

        Result result = switch (toolName) {
            case "execute_shell" -> evaluateShell(arguments, mode);
            case "write_file", "edit_file" -> evaluateFileWrite(arguments, mode, boundary);
            default -> Result.allow();
        };

        if (result.decision() != Decision.ALLOW) {
            auditLogger.log("SECURITY_CHECK", toolName, result.decision().name(),
                    result.rule() + ": " + result.reason(), userName);
        }

        return result;
    }

    private Result evaluateShell(Map<String, Object> arguments, String mode) {
        if (arguments == null || !arguments.containsKey("command")) {
            return Result.allow();
        }
        Object cmdObj = arguments.get("command");
        if (cmdObj == null) return Result.allow();
        String command = cmdObj.toString().trim();

        // 1. HARD BLOCK 检查（所有模式强制执行）
        for (Pattern p : hardBlockShellPatterns) {
            if (ReUtil.contains(p, command)) {
                return Result.block("HARD_BLOCK", "命令匹配安全红线规则，禁止执行");
            }
        }

        // 2. bypass 模式：放行
        if ("bypass".equalsIgnoreCase(mode)) {
            return Result.allow();
        }

        // 3. SOFT BLOCK 检查（default / acceptEdits 模式）
        for (Pattern p : softBlockShellPatterns) {
            if (ReUtil.contains(p, command)) {
                return Result.confirm("SOFT_BLOCK", "该命令具有破坏性，需要用户确认");
            }
        }

        // 4. default 模式：只读命令放行，其余需确认
        if (!"acceptEdits".equalsIgnoreCase(mode) && !"bypass".equalsIgnoreCase(mode)) {
            if (command.contains("&&") || command.contains("||")
                    || command.contains(";") || command.contains("\n")) {
                return Result.confirm("MODE_DEFAULT", "复合命令需用户确认");
            }
            String cmdName = command.split("\\s+", 2)[0];
            int lastSlash = cmdName.lastIndexOf('/');
            if (lastSlash >= 0) {
                cmdName = cmdName.substring(lastSlash + 1);
            }
            if (!readOnlyCommands.contains(cmdName)) {
                return Result.confirm("MODE_DEFAULT", "Default 模式下 shell 命令需要用户确认");
            }
        }

        return Result.allow();
    }

    private Result evaluateFileWrite(Map<String, Object> arguments, String mode, WorkspaceBoundary boundary) {
        String filePath = arguments.getOrDefault("file_path", "").toString();
        if (filePath.isEmpty()) {
            filePath = arguments.getOrDefault("filePath", "").toString();
        }
        String normalizedPath = Path.of(filePath).normalize().toString();

        // HARD BLOCK：修改安全 prompt 文件（大小写不敏感）
        String lowerPath = normalizedPath.toLowerCase();
        for (String prefix : hardBlockPathPrefixes) {
            if (lowerPath.contains(prefix)) {
                return Result.block("HARD_BLOCK", "禁止修改安全规则文件: " + filePath);
            }
        }

        // HARD BLOCK：修改 application.yml（含路径遍历绕过检测）
        for (String configFile : APP_CONFIG_FILES) {
            if (lowerPath.contains(configFile)) {
                return Result.block("HARD_BLOCK", "禁止修改应用配置文件: " + filePath);
            }
        }

        // bypass 模式：放行
        if ("bypass".equalsIgnoreCase(mode)) {
            return Result.allow();
        }

        if (boundary.isPresent()) {
            Path targetPath = boundary.resolveTarget(filePath);
            try {
                targetPath = targetPath.toRealPath();
            } catch (IOException ignored) {
            }

            if (!boundary.contains(targetPath)) {
                return Result.confirm(RULE_WORKSPACE_BOUNDARY,
                        "目标路径 " + filePath + " 不在工作空间 " + boundary.path() + " 内");
            }
        }

        if (!"acceptEdits".equalsIgnoreCase(mode)) {
            return Result.confirm(RULE_MODE_DEFAULT, "Default 模式下文件写入需要用户确认");
        }

        return Result.allow();
    }
}
