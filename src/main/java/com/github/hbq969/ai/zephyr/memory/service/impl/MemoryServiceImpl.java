package com.github.hbq969.ai.zephyr.memory.service.impl;

import com.github.hbq969.ai.zephyr.memory.model.MemoryVO;
import com.github.hbq969.ai.zephyr.memory.service.MemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.annotation.Resource;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;

@Service
@Slf4j
public class MemoryServiceImpl implements MemoryService {

    @Resource private com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties cfg;

    private Path userDir(String userName) {
        Path dir = Paths.get(cfg.getMemory().getHome(), userName);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建记忆目录: " + dir, e);
        }
        return dir;
    }

    @Override
    public List<MemoryVO> list(String type, String userName) {
        Path dir = userDir(userName);
        List<MemoryVO> result = new ArrayList<>();
        File[] files = dir.toFile().listFiles(f -> f.getName().endsWith(EXT_MD) && !f.getName().equals(MEMORY_INDEX_FILE));
        if (files == null) return result;

        for (File file : files) {
            Map<String, String> fm = parseFrontmatter(file.toPath());
            if (fm.isEmpty()) continue;
            String memType = fm.get("type");
            if (type != null && !type.isEmpty() && !type.equals(memType)) continue;

            MemoryVO vo = new MemoryVO();
            vo.setName(fm.get("name"));
            vo.setType(memType);
            vo.setDescription(fm.getOrDefault("description", ""));
            vo.setEnabled(!"false".equals(fm.get("enabled")));
            vo.setCreatedAt(Long.parseLong(fm.getOrDefault("created_at", "0")));
            vo.setUpdatedAt(Long.parseLong(fm.getOrDefault("updated_at", "0")));
            result.add(vo);
        }
        result.sort(Comparator.comparingLong(MemoryVO::getUpdatedAt).reversed());
        return result;
    }

    @Override
    public MemoryVO detail(String name, String userName) {
        Path dir = userDir(userName);
        Path file = resolveFile(dir, name);
        if (!Files.exists(file)) throw new RuntimeException("记忆不存在: " + name);

        Map<String, String> fm = parseFrontmatter(file);
        String body = readBody(file);

        MemoryVO vo = new MemoryVO();
        vo.setName(fm.get("name"));
        vo.setType(fm.get("type"));
        vo.setDescription(fm.getOrDefault("description", ""));
        vo.setEnabled(!"false".equals(fm.get("enabled")));
        vo.setContent(body);
        vo.setCreatedAt(Long.parseLong(fm.getOrDefault("created_at", "0")));
        vo.setUpdatedAt(Long.parseLong(fm.getOrDefault("updated_at", "0")));
        return vo;
    }

    @Override
    public void create(Map<String, String> body, String userName) {
        String name = body.get("name");
        String type = body.get("type");
        String content = body.getOrDefault("content", "");
        boolean enabled = !"false".equals(body.get("enabled"));

        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("名称不能为空");
        if (!MEMORY_TYPE_USER.equals(type) && !MEMORY_TYPE_PROJECT.equals(type)) throw new IllegalArgumentException("类型无效: " + type);

        name = name.trim();
        Path dir = userDir(userName);

        Path file = resolveFile(dir, name);
        if (Files.exists(file)) throw new RuntimeException("记忆已存在: " + name);

        long now = System.currentTimeMillis() / 1000;
        String description = content.length() > 60 ? content.substring(0, 60).replace("\n", " ") + "..." : content.replace("\n", " ");

        writeMemoryFile(file, name, type, description, content, now, now, enabled);
        appendToIndex(dir, name, description, type);
    }

    @Override
    public void update(Map<String, String> body, String userName) {
        String name = body.get("name");
        String type = body.get("type");
        String content = body.getOrDefault("content", "");
        String oldName = body.get("oldName");
        boolean enabled = !"false".equals(body.getOrDefault("enabled", "true"));

        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("名称不能为空");
        if (!MEMORY_TYPE_USER.equals(type) && !MEMORY_TYPE_PROJECT.equals(type)) throw new IllegalArgumentException("类型无效: " + type);

        name = name.trim();
        Path dir = userDir(userName);

        Path oldFile = resolveFile(dir, oldName != null ? oldName : name);
        if (!Files.exists(oldFile)) throw new RuntimeException("记忆不存在: " + (oldName != null ? oldName : name));

        Map<String, String> oldFm = parseFrontmatter(oldFile);
        long createdAt = Long.parseLong(oldFm.getOrDefault("created_at", String.valueOf(System.currentTimeMillis() / 1000)));
        long now = System.currentTimeMillis() / 1000;
        String description = content.length() > 60 ? content.substring(0, 60).replace("\n", " ") + "..." : content.replace("\n", " ");

        if (oldName != null && !oldName.equals(name)) {
            try { Files.delete(oldFile); } catch (IOException ignored) {}
            removeFromIndex(dir, oldName);
        }

        Path newFile = resolveFile(dir, name);
        writeMemoryFile(newFile, name, type, description, content, createdAt, now, enabled);
        upsertIndex(dir, name, description, type);
    }

    @Override
    public void delete(String namesStr, String userName) {
        if (namesStr == null || namesStr.isEmpty()) throw new IllegalArgumentException("名称不能为空");
        String[] names = namesStr.split(",");
        Path dir = userDir(userName);

        for (String name : names) {
            name = name.trim();
            Path file = resolveFile(dir, name);
            try { Files.deleteIfExists(file); } catch (IOException e) { log.warn("删除记忆文件失败: {}", file, e); }
            removeFromIndex(dir, name);
        }
    }

    @Override
    public void toggleEnabled(String name, boolean enabled, String userName) {
        Path dir = userDir(userName);
        Path file = resolveFile(dir, name);
        if (!Files.exists(file)) throw new RuntimeException("记忆不存在: " + name);

        Map<String, String> fm = parseFrontmatter(file);
        String body = readBody(file);

        String memName = fm.getOrDefault("name", name);
        String type = fm.getOrDefault("type", MEMORY_TYPE_USER);
        String description = fm.getOrDefault("description", "");
        long createdAt = Long.parseLong(fm.getOrDefault("created_at", String.valueOf(System.currentTimeMillis() / 1000)));
        long now = System.currentTimeMillis() / 1000;

        writeMemoryFile(file, memName, type, description, body, createdAt, now, enabled);
    }

    // === file helpers ===

    private String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
    }

    private Path resolveFile(Path dir, String name) {
        File[] files = dir.toFile().listFiles(f -> f.getName().endsWith(EXT_MD) && !f.getName().equals(MEMORY_INDEX_FILE));
        if (files != null) {
            for (File f : files) {
                Map<String, String> fm = parseFrontmatter(f.toPath());
                if (name.equals(fm.get("name"))) return f.toPath();
            }
        }
        return dir.resolve(sanitize(name) + EXT_MD);
    }

    private void writeMemoryFile(Path file, String name, String type, String description, String content, long createdAt, long updatedAt, boolean enabled) {
        String yaml = "---\n" +
                "name: " + name + "\n" +
                "description: " + description + "\n" +
                "metadata:\n" +
                "  type: " + type + "\n" +
                "  enabled: " + enabled + "\n" +
                "  created_at: " + createdAt + "\n" +
                "  updated_at: " + updatedAt + "\n" +
                "---\n\n" + content + "\n";
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, yaml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("写入记忆文件失败: " + file, e);
        }
    }

    // === frontmatter parsing ===

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^---\\s*\\n(.*?)\\n---", Pattern.DOTALL);
    private static final Pattern KV_PATTERN = Pattern.compile("^(\\S+)\\s*:\\s*(.+)$", Pattern.MULTILINE);

    private Map<String, String> parseFrontmatter(Path file) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Matcher fm = FRONTMATTER_PATTERN.matcher(content);
            if (!fm.find()) return result;

            String yaml = fm.group(1);
            Matcher kv = KV_PATTERN.matcher(yaml);
            while (kv.find()) {
                String key = kv.group(1).trim();
                String value = kv.group(2).trim();
                result.put(key, value);
            }

            // parse nested metadata.type, metadata.created_at, metadata.updated_at
            if (yaml.contains("metadata:")) {
                String[] lines = yaml.split("\n");
                boolean inMetadata = false;
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("metadata:")) { inMetadata = true; continue; }
                    if (inMetadata) {
                        if (line.length() > 0 && !Character.isWhitespace(line.charAt(0))) {
                            inMetadata = false;
                        }
                        if (inMetadata) {
                            Matcher mkv = Pattern.compile("^\\s+(\\S+)\\s*:\\s*(.+)$").matcher(line);
                            if (mkv.find()) result.put(mkv.group(1).trim(), mkv.group(2).trim());
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("解析记忆文件失败: {}", file, e);
        }
        return result;
    }

    private String readBody(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Matcher fm = FRONTMATTER_PATTERN.matcher(content);
            if (fm.find()) {
                return content.substring(fm.end()).trim();
            }
            return content.trim();
        } catch (IOException e) {
            throw new RuntimeException("读取记忆文件失败: " + file, e);
        }
    }

    // === MEMORY.md index helpers ===

    private Path indexPath(Path dir) {
        return dir.resolve(MEMORY_INDEX_FILE);
    }

    private void appendToIndex(Path dir, String name, String description, String type) {
        String line = "- [" + name + "](" + sanitize(name) + EXT_MD + ") — " + description + "\n";
        try {
            Files.writeString(indexPath(dir), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("更新 MEMORY.md 索引失败", e);
        }
    }

    private void removeFromIndex(Path dir, String name) {
        Path idx = indexPath(dir);
        if (!Files.exists(idx)) return;
        try {
            List<String> lines = Files.readAllLines(idx, StandardCharsets.UTF_8);
            String sanitized = sanitize(name);
            List<String> filtered = lines.stream()
                    .filter(l -> !l.contains("(" + sanitized + EXT_MD + ")"))
                    .collect(Collectors.toList());
            Files.writeString(idx, String.join("\n", filtered) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("更新 MEMORY.md 索引失败", e);
        }
    }

    private void upsertIndex(Path dir, String name, String description, String type) {
        removeFromIndex(dir, name);
        appendToIndex(dir, name, description, type);
    }
}
