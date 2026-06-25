package com.github.hbq969.ai.zephyr.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 从 classpath 加载 prompt 模板，支持 ~/.zephyr/prompts/ 用户覆盖。
 */
@Slf4j
@Component
public class PromptLoader {

    private static final String USER_PROMPTS_DIR = System.getProperty("user.home") + "/.zephyr/prompts";
    private static final Pattern VAR_PATTERN = Pattern.compile("\\{(\\w+)}");

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 加载 prompt 文件内容（无变量替换）。
     * 查找优先级：~/.zephyr/prompts/{name} > classpath:prompts/{name}
     */
    public String load(String name) {
        return cache.computeIfAbsent(name, k -> {
            // 1. 用户自定义覆盖
            Path userPath = Paths.get(USER_PROMPTS_DIR, k);
            if (Files.exists(userPath)) {
                log.info("Prompt '{}' 使用用户自定义覆盖: {}", k, userPath);
                try {
                    return Files.readString(userPath, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    log.warn("读取用户 prompt 失败: {}", userPath, e);
                }
            }
            // 2. classpath 默认
            try {
                ClassPathResource res = new ClassPathResource("prompts/" + k);
                return res.getContentAsString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.error("加载 prompt 失败: {}", k, e);
                return "";
            }
        });
    }

    /**
     * 加载并替换模板变量。变量格式：{variableName}
     */
    public String render(String name, Map<String, String> vars) {
        String template = load(name);
        if (vars == null || vars.isEmpty()) return template;
        return VAR_PATTERN.matcher(template).replaceAll(match -> {
            String varName = match.group(1);
            return java.util.regex.Matcher.quoteReplacement(vars.getOrDefault(varName, ""));
        });
    }

    /** 清除缓存（热重载时使用） */
    public void clearCache() {
        cache.clear();
    }
}
