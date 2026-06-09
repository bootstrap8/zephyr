package com.github.hbq969.ai.zephyr.skill.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.ZipUtil;
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
    public SkillVO install(Map<String, String> body, String userName) {
        String source = body.get("source");
        String url = body.getOrDefault("url", "");
        String path = body.getOrDefault("path", "");
        String branch = body.getOrDefault("branch", "main");

        String skillName;
        Path tmpDir = null;
        try {
            switch (source) {
                case "git":
                    tmpDir = Files.createTempDirectory("skill-git-");
                    runGitClone(url, branch, tmpDir);
                    skillName = detectSkillName(tmpDir);
                    break;
                case "url":
                    tmpDir = Files.createTempDirectory("skill-url-");
                    downloadAndExtract(url, tmpDir);
                    skillName = detectSkillName(tmpDir);
                    break;
                case "local":
                    skillName = detectSkillName(Paths.get(path));
                    break;
                default:
                    throw new IllegalArgumentException("不支持的安装方式: " + source);
            }

            Path destDir = Paths.get(SKILLS_HOME, skillName);
            if (Files.exists(destDir)) FileUtil.del(destDir.toFile());

            if ("local".equals(source)) {
                FileUtil.copy(Paths.get(path).toFile(), destDir.toFile(), true);
            } else {
                FileUtil.copy(tmpDir.toFile(), destDir.toFile(), true);
            }

            SkillConfigEntity existing = skillDao.queryBySkillName(skillName, userName);
            if (existing != null) {
                if ("local".equals(source)) {
                    // already copied, clean up
                }
                throw new RuntimeException("Skill " + skillName + " 已安装");
            }

            return insertSkillConfig(destDir, skillName, source, url, userName);
        } catch (IOException e) {
            throw new RuntimeException("安装失败: " + e.getMessage(), e);
        } finally {
            if (tmpDir != null) FileUtil.del(tmpDir.toFile());
        }
    }

    @Override
    @Transactional
    public SkillVO upload(MultipartFile file, String userName) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || (!originalFilename.endsWith(".zip")
                && !originalFilename.endsWith(".tar.gz") && !originalFilename.endsWith(".tgz"))) {
            throw new IllegalArgumentException("仅支持 .zip、.tar.gz、.tgz 格式");
        }
        if (file.getSize() > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("文件大小不能超过 10MB");
        }

        Path tmpDir = null;
        try {
            tmpDir = Files.createTempDirectory("skill-upload-");
            File tmpFile = tmpDir.resolve(originalFilename).toFile();
            file.transferTo(tmpFile);

            ZipUtil.unzip(tmpFile, tmpDir.toFile());
            String skillName = detectSkillName(tmpDir);

            SkillConfigEntity existing = skillDao.queryBySkillName(skillName, userName);
            if (existing != null) {
                throw new RuntimeException("Skill " + skillName + " 已安装");
            }

            Path destDir = Paths.get(SKILLS_HOME, skillName);
            if (Files.exists(destDir)) FileUtil.del(destDir.toFile());
            FileUtil.copy(tmpDir.toFile(), destDir.toFile(), true);

            return insertSkillConfig(destDir, skillName, "upload", originalFilename, userName);
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
            if (Files.exists(destDir)) FileUtil.del(destDir.toFile());
            FileUtil.copy(srcDir.toFile(), destDir.toFile(), true);

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

    private void downloadAndExtract(String url, Path targetDir) {
        try {
            Path tmpFile = targetDir.resolve("download.tmp");
            ProcessBuilder pb = new ProcessBuilder("curl", "-L", "-o", tmpFile.toString(), url);
            pb.inheritIO();
            Process p = pb.start();
            int code = p.waitFor();
            if (code != 0) throw new RuntimeException("下载失败，退出码: " + code);

            ZipUtil.unzip(tmpFile.toFile(), targetDir.toFile());
            FileUtil.del(tmpFile.toFile());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("下载失败: " + e.getMessage(), e);
        }
    }
}
