package com.github.hbq969.ai.zephyr.skill.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.ZipUtil;
import java.util.Comparator;
import java.util.stream.Stream;
import com.github.hbq969.ai.zephyr.skill.dao.SkillDao;
import com.github.hbq969.ai.zephyr.skill.dao.entity.SkillConfigEntity;
import com.github.hbq969.ai.zephyr.skill.model.SkillVO;
import com.github.hbq969.ai.zephyr.skill.service.SkillService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class SkillServiceImpl implements SkillService {

    private static final String SKILLS_HOME = System.getProperty("user.home") + "/.zephyr/skills";

    @Resource
    private SkillDao skillDao;

    @Override
    public List<SkillVO> list(String userName) {
        List<SkillConfigEntity> entities = skillDao.queryByUserName(userName);
        List<SkillVO> vos = new ArrayList<>();
        for (SkillConfigEntity e : entities) {
            vos.add(SkillVO.fromEntity(e));
        }
        return vos;
    }

    @Override
    @Transactional
    public List<SkillVO> install(Map<String, String> body, String userName) {
        String source = body.get("source");
        String url = body.getOrDefault("url", "");
        String path = body.getOrDefault("path", "");
        String branch = body.getOrDefault("branch", "main");

        Path tmpDir = null;
        try {
            switch (source) {
                case "git":
                    tmpDir = Files.createTempDirectory("skill-git-");
                    runGitClone(url, branch, tmpDir);
                    break;
                case "url":
                    tmpDir = Files.createTempDirectory("skill-url-");
                    if (url.endsWith(".md")) {
                        downloadMd(url, tmpDir);
                    } else {
                        downloadAndExtract(url, tmpDir);
                    }
                    break;
                case "local":
                    if (path.endsWith(".md")) {
                        tmpDir = Files.createTempDirectory("skill-local-md-");
                        Path srcFile = Paths.get(path);
                        if (!Files.isRegularFile(srcFile)) {
                            throw new IllegalArgumentException("文件不存在: " + path);
                        }
                        Files.copy(srcFile, tmpDir.resolve("SKILL.md"));
                    } else if (path.endsWith(".zip") || path.endsWith(".tar.gz")
                            || path.endsWith(".tgz") || path.endsWith(".tar")) {
                        tmpDir = Files.createTempDirectory("skill-local-archive-");
                        extractArchive(Paths.get(path).toFile(), tmpDir);
                    } else {
                        Path srcPath = Paths.get(path);
                        if (!Files.isDirectory(srcPath)) {
                            throw new IllegalArgumentException("路径不存在或不是目录: " + path);
                        }
                        tmpDir = Files.createTempDirectory("skill-local-dir-");
                        deleteAndCopyDir(srcPath, tmpDir);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("不支持的安装方式: " + source);
            }

            List<Path> skillRoots = findSkillRoots(tmpDir);
            if (skillRoots.isEmpty()) {
                throw new RuntimeException("未找到任何 SKILL.md，请确认包内包含有效 Skill");
            }
            String fallbackName = extractSourceName(source, url, path);
            String packName = detectPackName(tmpDir, skillRoots, fallbackName);
            return installSkills(tmpDir, skillRoots, packName, source, url, userName);
        } catch (IOException e) {
            throw new RuntimeException("安装失败: " + e.getMessage(), e);
        } finally {
            if (tmpDir != null) FileUtil.del(tmpDir.toFile());
        }
    }

    @Override
    @Transactional
    public List<SkillVO> upload(MultipartFile file, String userName) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new IllegalArgumentException("文件名为空");
        }
        boolean isArchive = originalFilename.endsWith(".zip")
                || originalFilename.endsWith(".tar.gz") || originalFilename.endsWith(".tgz")
                || originalFilename.endsWith(".tar");
        boolean isSkillMd = originalFilename.endsWith(".md");
        if (!isArchive && !isSkillMd) {
            throw new IllegalArgumentException("仅支持 .zip、.tar、.tar.gz、.tgz、.md 格式");
        }
        if (file.getSize() > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("文件大小不能超过 10MB");
        }

        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("skill-upload-");
            if (isSkillMd) {
                file.transferTo(tmpDir.resolve("SKILL.md").toFile());
            } else {
                File tmpFile = tmpDir.resolve(originalFilename).toFile();
                file.transferTo(tmpFile);
                extractArchive(tmpFile, tmpDir);
            }

            List<Path> skillRoots = findSkillRoots(tmpDir);
            if (skillRoots.isEmpty()) {
                throw new RuntimeException("未找到任何 SKILL.md，请确认包内包含有效 Skill");
            }
            String fallbackName = originalFilename.contains(".")
                    ? originalFilename.substring(0, originalFilename.lastIndexOf('.'))
                    : originalFilename;
            String packName = detectPackName(tmpDir, skillRoots, fallbackName);
            return installSkills(tmpDir, skillRoots, packName, "upload", originalFilename, userName);
        } catch (IOException e) {
            throw new RuntimeException("上传失败: " + e.getMessage(), e);
        } finally {
            if (tmpDir != null) FileUtil.del(tmpDir.toFile());
        }
    }

    @Override
    public List<SkillVO> syncScan(String userName) {
        List<SkillVO> result = new ArrayList<>();
        Map<String, String> platforms = new LinkedHashMap<>();
        platforms.put("claude-code", System.getProperty("user.home") + "/.claude/skills");
        platforms.put("codex", System.getProperty("user.home") + "/.codex/skills");
        platforms.put("opencode", System.getProperty("user.home") + "/.opencode/skills");

        for (Map.Entry<String, String> entry : platforms.entrySet()) {
            Path platformDir = Paths.get(entry.getValue());
            if (!Files.isDirectory(platformDir)) continue;

            File[] skillDirs = platformDir.toFile().listFiles(File::isDirectory);
            if (skillDirs == null) continue;

            for (File skillDir : skillDirs) {
                Path skillMd = skillDir.toPath().resolve("SKILL.md");
                if (!Files.exists(skillMd)) continue;

                Map<String, String> meta = parseSkillMd(skillMd);
                SkillVO vo = new SkillVO();
                vo.setSkillName(skillDir.getName());
                vo.setDisplayName(meta.getOrDefault("name", skillDir.getName()));
                vo.setDescription(meta.getOrDefault("description", ""));
                vo.setVersion(meta.getOrDefault("version", ""));
                vo.setSource("sync");
                vo.setPlatform(entry.getKey());
                vo.setPlatformPath(skillDir.getAbsolutePath());
                vo.setEnabled(false);
                result.add(vo);
            }
        }
        return result;
    }

    @Override
    @Transactional
    public List<SkillVO> syncInstall(Map<String, String> body, String userName) {
        String platform = body.get("platform");
        String skillNamesStr = body.getOrDefault("skillNames", "");
        if (skillNamesStr.isEmpty()) return Collections.emptyList();

        String[] skillNames = skillNamesStr.split(",");
        Map<String, String> platforms = new LinkedHashMap<>();
        platforms.put("claude-code", System.getProperty("user.home") + "/.claude/skills");
        platforms.put("codex", System.getProperty("user.home") + "/.codex/skills");
        platforms.put("opencode", System.getProperty("user.home") + "/.opencode/skills");

        String platformPath = platforms.get(platform);
        if (platformPath == null) throw new IllegalArgumentException("未知平台: " + platform);

        List<SkillVO> installed = new ArrayList<>();
        for (String skillName : skillNames) {
            skillName = skillName.trim();
            Path srcDir = Paths.get(platformPath, skillName);
            if (!Files.isDirectory(srcDir)) continue;

            Path destDir = Paths.get(SKILLS_HOME, skillName);
            deleteAndCopyDir(srcDir, destDir);

            SkillConfigEntity existing = skillDao.queryBySkillName(skillName, userName);
            if (existing == null) {
                installed.add(insertSkillConfig(destDir, skillName, "sync", srcDir.toString(), userName));
            }
        }
        return installed;
    }

    @Override
    @Transactional
    public void toggle(String id, Integer enabled, String userName) {
        skillDao.toggle(id, enabled, userName);
    }

    @Override
    @Transactional
    public void uninstall(String id, String userName) {
        SkillConfigEntity entity = skillDao.queryById(id);
        if (entity == null || !entity.getUserName().equals(userName)) {
            throw new RuntimeException("无权限或记录不存在");
        }
        Path skillDir = Paths.get(entity.getInstallPath());
        if (Files.exists(skillDir)) {
            FileUtil.del(skillDir.toFile());
        }
        // 如果 skillName 包含 pack: 前缀，检查 pack 目录是否为空，空则删除
        String skillName = entity.getSkillName();
        int colonIdx = skillName.indexOf(':');
        if (colonIdx > 0) {
            Path packDir = Paths.get(SKILLS_HOME, skillName.substring(0, colonIdx));
            if (Files.isDirectory(packDir)) {
                String[] remaining = packDir.toFile().list();
                if (remaining == null || remaining.length == 0) {
                    FileUtil.del(packDir.toFile());
                }
            }
        }
        skillDao.delete(id, userName);
    }

    private SkillVO insertSkillConfig(Path destDir, String skillName, String source, String sourceUrl, String userName) {
        Path skillMd = destDir.resolve("SKILL.md");
        Map<String, String> meta = Files.exists(skillMd) ? parseSkillMd(skillMd) : Collections.emptyMap();

        SkillConfigEntity entity = new SkillConfigEntity();
        entity.setId(UUID.fastUUID().toString(true).substring(0, 12));
        entity.setUserName(userName);
        entity.setSkillName(skillName);
        entity.setDisplayName(meta.getOrDefault("name", skillName));
        entity.setDescription(meta.getOrDefault("description", ""));
        entity.setSource(source);
        entity.setSourceUrl(sourceUrl);
        entity.setVersion(meta.getOrDefault("version", ""));
        entity.setEnabled(1);
        entity.setInstallPath(destDir.toString());
        long now = System.currentTimeMillis() / 1000;
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        skillDao.insert(entity);
        return SkillVO.fromEntity(entity);
    }

    /**
     * 递归查找所有包含 SKILL.md 的目录，返回每个 skill 的根目录列表。
     * 跳过名为 skills 的中间层目录。
     */
    private List<Path> findSkillRoots(Path dir) {
        List<Path> result = new ArrayList<>();
        File[] children = dir.toFile().listFiles();
        if (children == null) return result;
        // 顶层自身有 SKILL.md → 记录但不作为 skill（仅在 detectPackName 中用于取包名）
        boolean topHasSkillMd = Files.exists(dir.resolve("SKILL.md"));
        for (File child : children) {
            if (!child.isDirectory()) continue;
            Path childPath = child.toPath();
            if ("skills".equals(child.getName())) {
                File[] inner = child.listFiles(File::isDirectory);
                if (inner != null) {
                    for (File innerDir : inner) {
                        if (Files.exists(innerDir.toPath().resolve("SKILL.md"))) {
                            result.add(innerDir.toPath());
                        }
                    }
                }
            } else if (Files.exists(childPath.resolve("SKILL.md"))) {
                result.add(childPath);
            }
        }
        // 无子目录 skill 但顶层有 SKILL.md → 单 skill
        if (result.isEmpty() && topHasSkillMd) {
            result.add(dir);
        }
        return result;
    }

    /**
     * 判定是否组合包并返回包名。只有 1 个 skill 返回 null（无包）。
     * fallbackName 为从来源 URL/路径中提取的名称，作为最后回退。
     */
    private String detectPackName(Path tmpDir, List<Path> skillRoots, String fallbackName) {
        if (skillRoots.size() <= 1) return null;
        Path topSkillMd = tmpDir.resolve("SKILL.md");
        if (Files.exists(topSkillMd)) {
            Map<String, String> meta = parseSkillMd(topSkillMd);
            String name = meta.get("name");
            if (name != null && !name.isEmpty()) return name;
        }
        return fallbackName;
    }

    /** 从来源 URL/路径中提取项目名，作为组合包名的回退值。 */
    private String extractSourceName(String source, String url, String path) {
        return switch (source) {
            case "git" -> {
                String name = url.substring(url.lastIndexOf('/') + 1);
                if (name.endsWith(".git")) name = name.substring(0, name.length() - 4);
                yield name;
            }
            case "url" -> {
                String u = url;
                int q = u.indexOf('?');
                if (q >= 0) u = u.substring(0, q);
                String name = u.substring(u.lastIndexOf('/') + 1);
                int dot = name.lastIndexOf('.');
                if (dot >= 0) name = name.substring(0, dot);
                yield name;
            }
            case "local" -> Paths.get(path).getFileName().toString();
            default -> "unknown";
        };
    }

    private List<SkillVO> installSkills(Path tmpDir, List<Path> skillRoots, String packName,
                                         String source, String sourceUrl, String userName) {
        List<SkillVO> installed = new ArrayList<>();
        for (Path skillRoot : skillRoots) {
            String skillName = detectSkillName(skillRoot);
            String fullName = packName != null ? packName + ":" + skillName : skillName;

            SkillConfigEntity existing = skillDao.queryBySkillName(fullName, userName);
            if (existing != null) {
                log.warn("Skill {} 已安装，跳过", fullName);
                continue;
            }

            Path destDir = packName != null
                    ? Paths.get(SKILLS_HOME, packName, skillName)
                    : Paths.get(SKILLS_HOME, skillName);

            deleteAndCopyDir(skillRoot, destDir);
            installed.add(insertSkillConfig(destDir, fullName, source, sourceUrl, userName));
        }
        return installed;
    }

    private String detectSkillName(Path dir) {
        Path skillMd = dir.resolve("SKILL.md");
        if (Files.exists(skillMd)) {
            Map<String, String> meta = parseSkillMd(skillMd);
            String name = meta.get("name");
            if (name != null && !name.isEmpty()) return name;
        }
        File[] subDirs = dir.toFile().listFiles(File::isDirectory);
        if (subDirs != null && subDirs.length == 1) {
            Path nestedSkillMd = subDirs[0].toPath().resolve("SKILL.md");
            if (Files.exists(nestedSkillMd)) {
                Map<String, String> meta = parseSkillMd(nestedSkillMd);
                String name = meta.get("name");
                if (name != null && !name.isEmpty()) return name;
            }
            return subDirs[0].getName();
        }
        return dir.getFileName().toString();
    }

    private Map<String, String> parseSkillMd(Path skillMd) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            String content = Files.readString(skillMd, StandardCharsets.UTF_8);
            Matcher fm = Pattern.compile("^---\\s*\\n(.*?)\\n---", Pattern.DOTALL).matcher(content);
            if (fm.find()) {
                String yaml = fm.group(1);
                Matcher kv = Pattern.compile("^([a-zA-Z_-]+)\\s*:\\s*(.+)$", Pattern.MULTILINE).matcher(yaml);
                while (kv.find()) {
                    String key = kv.group(1).trim();
                    String value = kv.group(2).trim();
                    if (value.startsWith("|")) {
                        value = value.substring(1).trim();
                    }
                    result.put(key, value);
                }
            }
        } catch (IOException e) {
            log.warn("解析 SKILL.md 失败: {}", skillMd, e);
        }
        return result;
    }

    private void runGitClone(String url, String branch, Path targetDir) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "clone", "--depth", "1", "--branch", branch, url, targetDir.toString());
            pb.inheritIO();
            Process p = pb.start();
            int code = p.waitFor();
            if (code != 0) throw new RuntimeException("git clone 失败，退出码: " + code);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("git clone 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 先删除目标目录（如果存在），再将源目录的*内容*复制到目标目录。
     * 完全使用 NIO 实现，确保不会出现嵌套目录问题。
     */
    private static void deleteAndCopyDir(Path src, Path dest) {
        // 递归删除目标
        if (Files.exists(dest)) {
            try (Stream<Path> stream = Files.walk(dest)) {
                stream.sorted(Comparator.reverseOrder())
                      .forEach(p -> {
                          try { Files.delete(p); } catch (IOException ignored) {}
                      });
            } catch (IOException e) {
                throw new RuntimeException("删除目标目录失败: " + dest, e);
            }
        }
        // 创建目标目录
        try {
            Files.createDirectories(dest);
        } catch (IOException e) {
            throw new RuntimeException("创建目录失败: " + dest, e);
        }
        // NIO 递归复制源目录内容到目标（不嵌套）
        try (Stream<Path> stream = Files.walk(src)) {
            stream.forEach(source -> {
                try {
                    Path target = dest.resolve(src.relativize(source));
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(source, target);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("复制文件失败: " + source, e);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("遍历源目录失败: " + src, e);
        }
    }

    private void downloadMd(String url, Path targetDir) {
        try {
            Path dest = targetDir.resolve("SKILL.md");
            ProcessBuilder pb = new ProcessBuilder("curl", "-L", "-o", dest.toString(), url);
            pb.inheritIO();
            Process p = pb.start();
            int code = p.waitFor();
            if (code != 0) throw new RuntimeException("下载 SKILL.md 失败，退出码: " + code);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("下载 SKILL.md 失败: " + e.getMessage(), e);
        }
    }

    private void downloadAndExtract(String url, Path targetDir) {
        try {
            Path tmpFile = targetDir.resolve("download.tmp");
            ProcessBuilder pb = new ProcessBuilder("curl", "-L", "-o", tmpFile.toString(), url);
            pb.inheritIO();
            Process p = pb.start();
            int code = p.waitFor();
            if (code != 0) throw new RuntimeException("下载失败，退出码: " + code);

            extractArchive(tmpFile.toFile(), targetDir);
            FileUtil.del(tmpFile.toFile());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("下载失败: " + e.getMessage(), e);
        }
    }

    private void extractArchive(File archive, Path targetDir) {
        String name = archive.getName().toLowerCase();
        if (name.endsWith(".zip")) {
            ZipUtil.unzip(archive, targetDir.toFile());
        } else if (name.endsWith(".tar.gz") || name.endsWith(".tgz") || name.endsWith(".tar")) {
            try {
                ProcessBuilder pb = new ProcessBuilder("tar", "-xf", archive.getAbsolutePath(), "-C", targetDir.toString());
                pb.inheritIO();
                int code = pb.start().waitFor();
                if (code != 0) throw new RuntimeException("tar 解压失败，退出码: " + code);
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException("tar 解压失败: " + e.getMessage(), e);
            }
        } else {
            throw new IllegalArgumentException("不支持的压缩格式: " + name);
        }
    }
}
