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
 * HARD/SOFT BLOCK 规则全部从 {@code application.yml} 的 {@code zephyr.security} 读取，规则变更后需重启生效。
 */
@Slf4j
@Component
public class SecurityEvaluator {

    @Resource
    private ZephyrConfigProperties cfg;

    @Resource
    private AuditLogger auditLogger;

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

        hardBlockShellPatterns = compileShellPatterns(hb.getShellPatterns(), "HARD_BLOCK");
        hardBlockPathPrefixes = new ArrayList<>(hb.getPathPrefixes() != null ? hb.getPathPrefixes() : List.of());
        softBlockShellPatterns = compileShellPatterns(sb.getShellPatterns(), "SOFT_BLOCK");

        log.info("规则加载完成 — HARD_BLOCK shell: {} 条, path: {} 条; SOFT_BLOCK shell: {} 条",
                hardBlockShellPatterns.size(), hardBlockPathPrefixes.size(), softBlockShellPatterns.size());
    }

    private List<Pattern> compileShellPatterns(List<String> patterns, String ruleType) {
        if (patterns == null) return List.of();
        List<Pattern> result = new ArrayList<>();
        for (String raw : patterns) {
            try {
                result.add(PatternPool.get(raw, Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException e) {
                log.warn("{} 规则正则非法，已跳过: [{}], 错误: {}", ruleType, raw, e.getMessage());
            }
        }
        return result;
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
            case "execute_shell" -> evaluateShell(arguments, mode, boundary);
            case "write_file", "edit_file" -> evaluateFileWrite(arguments, mode, boundary);
            default -> Result.allow();
        };

        if (result.decision() != Decision.ALLOW) {
            auditLogger.log("SECURITY_CHECK", toolName, result.decision().name(),
                    result.rule() + ": " + result.reason(), userName);
        }

        return result;
    }

    private Result evaluateShell(Map<String, Object> arguments, String mode, WorkspaceBoundary boundary) {
        if (arguments == null || !arguments.containsKey("command")) {
            return Result.allow();
        }
        Object cmdObj = arguments.get("command");
        if (cmdObj == null) return Result.allow();
        String command = cmdObj.toString().trim();

        // 1. HARD BLOCK 检查（所有模式强制执行）
        for (Pattern p : hardBlockShellPatterns) {
            if (ReUtil.contains(p, command)) {
                log.info("[安全] HARD_BLOCK 触发拒绝 — 命令: {}, 命中规则: {}", command, p.pattern());
                return Result.block("HARD_BLOCK", "命令匹配安全红线规则，禁止执行");
            }
        }

        // 2. bypass 模式：放行
        if ("bypass".equalsIgnoreCase(mode)) {
            return Result.allow();
        }

        // 3. workspace 边界检查：命令中的绝对路径必须在 workspace 内
        if (boundary.isPresent() && hasPathOutsideWorkspace(command, boundary)) {
            log.info("[安全] WORKSPACE_BOUNDARY 触发确认 — shell 命令: {}", command);
            return Result.confirm(RULE_WORKSPACE_BOUNDARY,
                    "shell 命令中的路径超出工作空间范围");
        }

        // 4. SOFT BLOCK 检查
        for (Pattern p : softBlockShellPatterns) {
            if (!ReUtil.contains(p, command)) continue;

            // acceptEdits 模式：shell 重定向到 workspace 内文件应免批（文件编辑语义）
            if ("acceptEdits".equalsIgnoreCase(mode) && isRedirectPattern(p.pattern())
                    && boundary.isPresent() && !hasPathOutsideWorkspace(command, boundary)) {
                continue;
            }
            log.info("[安全] SOFT_BLOCK 触发确认 — 命令: {}, 命中规则: {}", command, p.pattern());
            return Result.confirm("SOFT_BLOCK", "该命令具有破坏性，需要用户确认");
        }

        // 5. default 模式：只读命令放行，其余需确认
        if (!"acceptEdits".equalsIgnoreCase(mode) && !"bypass".equalsIgnoreCase(mode)) {
            if (command.contains("&&") || command.contains("||")
                    || command.contains(";") || command.contains("\n")) {
                log.info("[安全] MODE_DEFAULT 触发确认 — 复合命令: {}", command);
                return Result.confirm("MODE_DEFAULT", "复合命令需用户确认");
            }
            String cmdName = command.split("\\s+", 2)[0];
            int lastSlash = cmdName.lastIndexOf('/');
            if (lastSlash >= 0) {
                cmdName = cmdName.substring(lastSlash + 1);
            }
            if (!readOnlyCommands.contains(cmdName)) {
                log.info("[安全] MODE_DEFAULT 触发确认 — 非只读命令: {} (主命令: {})", command, cmdName);
                return Result.confirm("MODE_DEFAULT", "Default 模式下 shell 命令需要用户确认");
            }
        }

        return Result.allow();
    }

    /** 判定 SOFT BLOCK 模式是否为输出重定向类型（在 acceptEdits 模式下应视为文件编辑免批） */
    private boolean isRedirectPattern(String rawPattern) {
        return rawPattern.contains(">") && (rawPattern.contains("\\s*\\\\S")
                || rawPattern.contains("redirect") || rawPattern.contains("overwrite"));
    }

    /** 检查 shell 命令中是否有绝对路径指向 workspace 外部 */
    private boolean hasPathOutsideWorkspace(String command, WorkspaceBoundary boundary) {
        // 提取命令名，如果是绝对路径则跳过（如 /usr/bin/mkdir）
        String cmdName = command.split("\\s+", 2)[0];
        boolean cmdIsAbsPath = cmdName.startsWith("/");

        // 提取命令中所有绝对路径
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("/(?:[^\\s;|&<>\"'`$()\\\\]+/)*[^\\s;|&<>\"'`$()\\\\]+")
                .matcher(command);
        while (m.find()) {
            String pathStr = m.group();
            // 跳过命令名本身
            if (cmdIsAbsPath && pathStr.equals(cmdName)) continue;
            // 跳过已知的非文件系统路径
            if (pathStr.startsWith("/dev/") || pathStr.startsWith("/proc/") || pathStr.startsWith("/sys/")) continue;
            try {
                Path targetPath = boundary.resolveTarget(pathStr);
                if (!boundary.contains(targetPath)) {
                    return true;
                }
            } catch (Exception ignored) {
                return true;
            }
        }
        return false;
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
                log.info("[安全] HARD_BLOCK 触发拒绝 — 文件: {}, 命中路径前缀: {}", filePath, prefix);
                return Result.block("HARD_BLOCK", "禁止修改安全规则文件: " + filePath);
            }
        }

        // HARD BLOCK：修改 application.yml（含路径遍历绕过检测）
        for (String configFile : APP_CONFIG_FILES) {
            if (lowerPath.contains(configFile)) {
                log.info("[安全] HARD_BLOCK 触发拒绝 — 文件: {}, 命中配置保护: {}", filePath, configFile);
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
                log.info("[安全] WORKSPACE_BOUNDARY 触发确认 — 目标: {} (规范化: {}), workspace: {}",
                        filePath, targetPath, boundary.path());
                return Result.confirm(RULE_WORKSPACE_BOUNDARY,
                        "目标路径 " + filePath + " 不在工作空间 " + boundary.path() + " 内");
            }
        }

        if (!"acceptEdits".equalsIgnoreCase(mode)) {
            log.info("[安全] MODE_DEFAULT 触发确认 — 文件写入: {}, mode: {}", filePath, mode);
            return Result.confirm(RULE_MODE_DEFAULT, "Default 模式下文件写入需要用户确认");
        }

        return Result.allow();
    }
}
