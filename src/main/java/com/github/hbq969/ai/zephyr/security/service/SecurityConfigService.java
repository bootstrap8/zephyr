package com.github.hbq969.ai.zephyr.security.service;

import com.github.hbq969.ai.zephyr.security.dao.SecurityConfigDao;
import com.github.hbq969.ai.zephyr.security.dao.entity.SecurityRuleEntity;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;

@Slf4j
@Service
public class SecurityConfigService {

    @Resource
    private SecurityConfigDao dao;

    private volatile ConfigSnapshot snapshot;

    @PostConstruct
    void init() {
        refresh();
    }

    public ConfigSnapshot getSnapshot() {
        return snapshot;
    }

    public synchronized void refresh() {
        snapshot = loadFromDb();
        log.info("[SecurityConfig] 刷新完成 — shellAllowed={}, defaultAllow={}, hardBlock={}, softBlock={}",
                snapshot.shellAllowedCommands().size(), snapshot.defaultAllowCommands().size(),
                snapshot.hardBlockPatterns().size(), snapshot.softBlockPatterns().size());
    }

    private ConfigSnapshot loadFromDb() {
        try {
            List<SecurityRuleEntity> all = dao.queryAll();
            Set<String> shellAllowed = new LinkedHashSet<>();
            Set<String> defaultAllow = new LinkedHashSet<>();
            List<String> hardBlockRaw = new ArrayList<>();
            List<String> softBlockRaw = new ArrayList<>();
            for (SecurityRuleEntity r : all) {
                switch (r.getRuleType()) {
                    case RULE_TYPE_SHELL_ALLOWED -> shellAllowed.add(r.getRuleValue());
                    case RULE_TYPE_DEFAULT_ALLOW -> defaultAllow.add(r.getRuleValue());
                    case RULE_TYPE_HARD_BLOCK -> hardBlockRaw.add(r.getRuleValue());
                    case RULE_TYPE_SOFT_BLOCK -> softBlockRaw.add(r.getRuleValue());
                }
            }
            return new ConfigSnapshot(shellAllowed, defaultAllow,
                    compilePatterns(hardBlockRaw), compilePatterns(softBlockRaw));
        } catch (Exception e) {
            // 表尚未创建（@PostConstruct 早于 InitialServiceImpl），返空快照待后续 refresh
            log.warn("[SecurityConfig] DB not ready, returning empty snapshot: {}", e.getMessage());
            return new ConfigSnapshot(Set.of(), Set.of(), List.of(), List.of());
        }
    }

    private static List<Pattern> compilePatterns(List<String> patterns) {
        if (patterns == null) return List.of();
        List<Pattern> result = new ArrayList<>();
        for (String raw : patterns) {
            try {
                result.add(Pattern.compile(raw, Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException e) {
                log.warn("[SecurityConfig] 跳过非法正则: [{}], 错误: {}", raw, e.getMessage());
            }
        }
        return result;
    }

    public record ConfigSnapshot(
            Set<String> shellAllowedCommands,
            Set<String> defaultAllowCommands,
            List<Pattern> hardBlockPatterns,
            List<Pattern> softBlockPatterns
    ) {}
}
