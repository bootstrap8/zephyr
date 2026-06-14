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
import com.github.hbq969.code.sm.login.model.UserInfo;
import com.github.hbq969.code.sm.login.session.UserContext;
import jakarta.annotation.PostConstruct;
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

    @Resource private com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties cfg;

    @Resource
    private SkillDao skillDao;

    private static final String SCOPE_USER = "user";
    private static final String SCOPE_SHARED = "shared";

    private Path skillDir(String userName) {
        Path dir = Paths.get(cfg.getSkills().getHome(), userName);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建Skill目录: " + dir, e);
        }
        return dir;
    }

    private Path sharedDir() {
        Path dir = Paths.get(cfg.getSkills().getHome(), "share");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建共享Skill目录: " + dir, e);
        }
        return dir;
    }

    private boolean isAdmin(String userName) {
        UserInfo ui = UserContext.getNoCheck();
        return ui != null && ui.isAdmin();
    }

    private void checkSharedManage(String userName) {
        if (!isAdmin(userName)) throw new RuntimeException("仅 admin 可管理共享 Skill");
    }

    // === 迁移 ===

    @PostConstruct
    public void migrateOldSkills() {
        Path home = Paths.get(cfg.getSkills().getHome());
        Path marker = home.resolve(".migrated-to-isolation");
        if (Files.exists(marker)) return;

        File[] children = home.toFile().listFiles(File::isDirectory);
        if (children == null) return;

        Path share = sharedDir();
        int moved = 0;
        for (File child : children) {
            // 跳过 share 目录和用户目录（用户名可能是 admin 等已有账户）
            String name = child.getName();
            if ("share".equals(name)) continue;

            Path childPath = child.toPath();
            Path skillMd = childPath.resolve("SKILL.md");
            if (!Files.exists(skillMd)) continue;

            Path dest = share.resolve(name);
            if (Files.exists(dest)) {
                log.info("迁移跳过（目标已存在）: {} -> {}", childPath, dest);
                continue;
            }
            try {
                Files.move(childPath, dest);
                moved++;
                log.info("迁移旧 Skill: {} -> {}", childPath, dest);
            } catch (IOException e) {
                log.warn("迁移失败: {} -> {}: {}", childPath, dest, e.getMessage());
            }
        }

        if (moved > 0) {
            // 更新数据库 install_path
            List<SkillConfigEntity> all = skillDao.queryByUserName("admin");
            for (SkillConfigEntity e : all) {
                String oldBase = cfg.getSkills().getHome();
                String newPrefix = cfg.getSkills().getHome() + "/share/";
                String newPath = e.getInstallPath().replace(oldBase + "/", newPrefix);
                if (!newPath.equals(e.getInstallPath())) {
                    e.setInstallPath(newPath);
                    e.setScope(SCOPE_SHARED);
                    skillDao.toggleById(e.getId(), e.getEnabled());
                    log.info("迁移 DB install_path: {} -> {}", e.getInstallPath(), newPath);
                }
            }
        }

        try {
            Files.createFile(marker);
        } catch (IOException e) {
            log.warn("创建迁移标记文件失败", e);
        }
        log.info("Skill 迁移完成，共移动 {} 个目录", moved);
    }

    // === API ===

    @Override
    public List<SkillVO> list(String userName) {
        List<SkillConfigEntity> own = skillDao.queryByUserName(userName);
        List<SkillConfigEntity> shared = skillDao.queryShared();

        Map<String, SkillVO> dedup = new LinkedHashMap<>();
        // 共享 skill 先放进 map
        for (SkillConfigEntity e : shared) {
            dedup.put(e.getSkillName(), SkillVO.fromEntity(e));
        }
        // 用户私有覆盖同名共享
        for (SkillConfigEntity e : own) {
            dedup.put(e.getSkillName(), SkillVO.fromEntity(e));
        }

        return new ArrayList<>(dedup.values());
    }

    @Override
    @Transactional
    public List<SkillVO> install(Map<String, String> body, String userName) {
        String source = body.get("source");
        String url = body.getOrDefault("url", "");
        String path = body.getOrDefault("path", "");
        String branch = body.getOrDefault("branch", "main");
        String scope = body.getOrDefault("scope", SCOPE_USER);
        if (SCOPE_SHARED.equals(scope)) checkSharedManage(userName);

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
            if ("git".equals(source) && packName == null) {
                packName = fallbackName;
            }
            return installSkills(tmpDir, skillRoots, packName, source, url, scope, userName);
        } catch (IOException e) {
            throw new RuntimeException("安装失败: " + e.getMessage(), e);
        } finally {
            if (tmpDir != null) FileUtil.del(tmpDir.toFile());
        }
    }

    @Override
    @Transactional
    public List<SkillVO> upload(MultipartFile file, String scope, String userName) {
        if (SCOPE_SHARED.equals(scope)) checkSharedManage(userName);
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
        if (file.getSize() > cfg.getSkill().getUpload().getMaxSizeBytes()) {
            throw new IllegalArgumentException("文件大小不能超过 " + (cfg.getSkill().getUpload().getMaxSizeBytes() / 1024 / 1024) + "MB");
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
            return installSkills(tmpDir, skillRoots, packName, "upload", originalFilename, scope != null ? scope : SCOPE_USER, userName);
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
        platforms.put("claude-code", cfg.getSkill().getSync().getClaudeSkillsPath());
        platforms.put("codex", cfg.getSkill().getSync().getCodexSkillsPath());
        platforms.put("opencode", cfg.getSkill().getSync().getOpencodeSkillsPath());

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
                vo.setScope(SCOPE_USER);
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
        String scope = body.getOrDefault("scope", SCOPE_USER);
        if (SCOPE_SHARED.equals(scope)) checkSharedManage(userName);
        if (skillNamesStr.isEmpty()) return Collections.emptyList();

        String[] skillNames = skillNamesStr.split(",");
        Map<String, String> platforms = new LinkedHashMap<>();
        platforms.put("claude-code", System.getProperty("user.home") + "/.claude/skills");
        platforms.put("codex", System.getProperty("user.home") + "/.codex/skills");
        platforms.put("opencode", System.getProperty("user.home") + "/.opencode/skills");

        String platformPath = platforms.get(platform);
        if (platformPath == null) throw new IllegalArgumentException("未知平台: " + platform);

        Path destBase = SCOPE_SHARED.equals(scope) ? sharedDir() : skillDir(userName);
        List<SkillVO> installed = new ArrayList<>();
        for (String skillName : skillNames) {
            skillName = skillName.trim();
            Path srcDir = Paths.get(platformPath, skillName);
            if (!Files.isDirectory(srcDir)) continue;

            // 检查是否有同名共享 Skill
            SkillConfigEntity sharedDup = skillDao.queryBySkillNameAndScope(skillName, SCOPE_SHARED);
            if (sharedDup != null) {
                throw new RuntimeException("已存在同名共享 Skill \"" + skillName + "\"，请使用其他名称");
            }

            SkillConfigEntity existing = SCOPE_SHARED.equals(scope)
                    ? sharedDup
                    : skillDao.queryBySkillName(skillName, userName);
            if (existing != null) {
                log.warn("Skill {} 已安装，跳过", skillName);
                continue;
            }

            Path destDir = destBase.resolve(skillName);
            deleteAndCopyDir(srcDir, destDir);
            installed.add(insertSkillConfig(destDir, skillName, scope, "sync", srcDir.toString(), userName));
        }
        return installed;
    }

    @Override
    @Transactional
    public void toggle(String id, Integer enabled, String userName) {
        SkillConfigEntity entity = skillDao.queryById(id);
        if (entity == null) throw new RuntimeException("记录不存在");
        if (SCOPE_SHARED.equals(entity.getScope())) {
            checkSharedManage(userName);
            skillDao.toggleById(id, enabled);
        } else {
            if (!entity.getUserName().equals(userName)) throw new RuntimeException("无权限");
            skillDao.toggle(id, enabled, userName);
        }
    }

    @Override
    @Transactional
    public void batchUninstall(List<String> ids, String userName) {
        for (String id : ids) {
            uninstall(id, userName);
        }
    }

    @Override
    @Transactional
    public void uninstall(String id, String userName) {
        SkillConfigEntity entity = skillDao.queryById(id);
        if (entity == null) throw new RuntimeException("记录不存在");

        if (SCOPE_SHARED.equals(entity.getScope())) {
            checkSharedManage(userName);
        } else {
            if (!entity.getUserName().equals(userName)) throw new RuntimeException("无权限");
        }

        Path skillDirPath = Paths.get(entity.getInstallPath());
        if (Files.exists(skillDirPath)) {
            FileUtil.del(skillDirPath.toFile());
        }
        // 如果 skillName 包含 pack: 前缀，检查 pack 目录是否为空，空则删除
        String skillName = entity.getSkillName();
        int colonIdx = skillName.indexOf(':');
        if (colonIdx > 0) {
            Path parent = skillDirPath.getParent();
            if (Files.isDirectory(parent)) {
                String[] remaining = parent.toFile().list();
                if (remaining == null || remaining.length == 0) {
                    FileUtil.del(parent.toFile());
                }
            }
        }
        if (SCOPE_SHARED.equals(entity.getScope())) {
            skillDao.deleteById(id);
        } else {
            skillDao.delete(id, userName);
        }
    }

    // === internal helpers ===

    private SkillVO insertSkillConfig(Path destDir, String skillName, String scope, String source, String sourceUrl, String userName) {
        Path skillMd = destDir.resolve("SKILL.md");
        Map<String, String> meta = Files.exists(skillMd) ? parseSkillMd(skillMd) : Collections.emptyMap();

        SkillConfigEntity entity = new SkillConfigEntity();
        entity.setId(UUID.fastUUID().toString(true).substring(0, 12));
        entity.setUserName(userName);
        entity.setSkillName(skillName);
        entity.setDisplayName(meta.getOrDefault("name", skillName));
        entity.setDescription(meta.getOrDefault("description", ""));
        entity.setScope(scope);
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

    private List<Path> findSkillRoots(Path dir) {
        List<Path> result = new ArrayList<>();
        collectSkillMdDirs(dir, result);
        if (result.isEmpty() && Files.exists(dir.resolve("SKILL.md"))) {
            result.add(dir);
        }
        result.sort(Comparator.comparing(Path::toString));
        return result;
    }

    private void collectSkillMdDirs(Path dir, List<Path> result) {
        File[] children = dir.toFile().listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            Path childPath = child.toPath();
            if (Files.exists(childPath.resolve("SKILL.md"))) {
                result.add(childPath);
            } else {
                collectSkillMdDirs(childPath, result);
            }
        }
    }

    private String detectPackName(Path tmpDir, List<Path> skillRoots, String fallbackName) {
        if (skillRoots.size() <= 1) return null;
        Path topSkillMd = tmpDir.resolve("SKILL.md");
        if (Files.exists(topSkillMd)) {
            Map<String, String> meta = parseSkillMd(topSkillMd);
            String name = meta.get("name");
            if (name != null && !name.isEmpty()) return name;
        }
        Path first = tmpDir.relativize(skillRoots.get(0));
        if (first.getNameCount() > 0) {
            String prefix = first.getName(0).toString();
            for (int i = 1; i < skillRoots.size(); i++) {
                Path rel = tmpDir.relativize(skillRoots.get(i));
                if (rel.getNameCount() == 0 || !rel.getName(0).toString().equals(prefix)) {
                    return fallbackName;
                }
            }
            if (!"skills".equals(prefix) && !prefix.isEmpty()) {
                return prefix;
            }
        }
        return fallbackName;
    }

    private String extractSourceName(String source, String url, String path) {
        return switch (source) {
            case "git" -> {
                String name = url.substring(url.lastIndexOf('/') + 1);
                if (name.endsWith(".git")) name = name.substring(0, name.length() - 4);
                Matcher m = Pattern.compile("github\\.com[:/]([^/]+)/([^/]+)").matcher(url);
                if (m.find()) {
                    String repo = m.group(2);
                    if (repo.endsWith(".git")) repo = repo.substring(0, repo.length() - 4);
                    name = m.group(1) + "-" + repo;
                }
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
                                         String source, String sourceUrl, String scope, String userName) {
        Path destBase = SCOPE_SHARED.equals(scope) ? sharedDir() : skillDir(userName);
        List<SkillVO> installed = new ArrayList<>();
        for (Path skillRoot : skillRoots) {
            String skillName = detectSkillName(skillRoot);
            String fullName = packName != null ? packName + ":" + skillName : skillName;

            // 检查是否有同名共享 Skill
            SkillConfigEntity sharedDup = skillDao.queryBySkillNameAndScope(fullName, SCOPE_SHARED);
            if (sharedDup != null) {
                throw new RuntimeException("已存在同名共享 Skill \"" + fullName + "\"，请使用其他名称");
            }

            SkillConfigEntity existing = SCOPE_SHARED.equals(scope)
                    ? sharedDup
                    : skillDao.queryBySkillName(fullName, userName);
            if (existing != null) {
                log.warn("Skill {} 已安装，跳过", fullName);
                continue;
            }

            Path destDir = packName != null
                    ? destBase.resolve(packName).resolve(skillName)
                    : destBase.resolve(skillName);

            deleteAndCopyDir(skillRoot, destDir);
            installed.add(insertSkillConfig(destDir, fullName, scope, source, sourceUrl, userName));
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

    private static void deleteAndCopyDir(Path src, Path dest) {
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
        try {
            Files.createDirectories(dest);
        } catch (IOException e) {
            throw new RuntimeException("创建目录失败: " + dest, e);
        }
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
            String fileName = url.substring(url.lastIndexOf('/') + 1);
            int qi = fileName.indexOf('?');
            if (qi >= 0) fileName = fileName.substring(0, qi);
            if (fileName.isEmpty()) fileName = "download.tmp";
            Path tmpFile = targetDir.resolve(fileName);
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
