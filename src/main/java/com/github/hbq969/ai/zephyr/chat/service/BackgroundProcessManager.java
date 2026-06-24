package com.github.hbq969.ai.zephyr.chat.service;

import com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class BackgroundProcessManager {

    @Resource
    private ZephyrConfigProperties cfg;

    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, TrackedProcess>> userProcesses = new ConcurrentHashMap<>();

    @Data
    public static class TrackedProcess {
        private final long pid;
        private final String userName;
        private final String command;
        private final String workspacePath;
        private final Instant startedAt;
    }

    public long register(String userName, Process p, String command, String workspacePath) {
        long pid = p.pid();
        TrackedProcess tp = new TrackedProcess(pid, userName, command, workspacePath, Instant.now());
        userProcesses.computeIfAbsent(userName, k -> new ConcurrentHashMap<>()).put(pid, tp);
        log.info("[后台进程] 注册 user={}, pid={}, cmd={}", userName, pid, command);
        return pid;
    }

    public List<TrackedProcess> list(String userName) {
        ConcurrentHashMap<Long, TrackedProcess> m = userProcesses.get(userName);
        if (m == null) return List.of();
        return new ArrayList<>(m.values());
    }

    public boolean kill(String userName, long pid) {
        ConcurrentHashMap<Long, TrackedProcess> m = userProcesses.get(userName);
        if (m == null) return false;
        TrackedProcess tp = m.remove(pid);
        if (tp == null) return false;
        try {
            ProcessHandle.of(pid).ifPresent(ph -> {
                ph.descendants().forEach(ProcessHandle::destroyForcibly);
                ph.destroyForcibly();
            });
            cleanupLogFile(tp);
            log.info("[后台进程] 已终止 user={}, pid={}, cmd={}", userName, pid, tp.getCommand());
            return true;
        } catch (Exception e) {
            log.warn("[后台进程] 终止失败 user={}, pid={}", userName, pid, e);
            return false;
        }
    }

    public void killAll(String userName) {
        ConcurrentHashMap<Long, TrackedProcess> m = userProcesses.remove(userName);
        if (m == null) return;
        log.info("[后台进程] 批量终止 user={}, 数量={}", userName, m.size());
        for (TrackedProcess tp : m.values()) {
            try {
                ProcessHandle.of(tp.getPid()).ifPresent(ph -> {
                    ph.descendants().forEach(ProcessHandle::destroyForcibly);
                    ph.destroyForcibly();
                });
                cleanupLogFile(tp);
            } catch (Exception e) {
                log.warn("[后台进程] 终止失败 pid={}", tp.getPid(), e);
            }
        }
    }

    @Scheduled(fixedRateString = "${zephyr.shell.cleanup-interval-seconds:60}000")
    public void enforceLimits() {
        long lifetimeSec = cfg.getShell().getMaxBackgroundLifetimeSeconds();
        Instant cutoff = Instant.now().minusSeconds(lifetimeSec);
        userProcesses.forEach((user, processes) -> {
            List<TrackedProcess> expired = new ArrayList<>();
            for (TrackedProcess tp : processes.values()) {
                if (tp.getStartedAt().isBefore(cutoff)) {
                    expired.add(tp);
                }
            }
            for (TrackedProcess tp : expired) {
                kill(user, tp.getPid());
                log.info("[后台进程] 超时清理 user={}, pid={}, cmd={}", user, tp.getPid(), tp.getCommand());
            }
        });
    }

    public void enforceQuota(String userName) {
        int max = cfg.getShell().getMaxBackgroundProcesses();
        ConcurrentHashMap<Long, TrackedProcess> m = userProcesses.get(userName);
        if (m != null && m.size() >= max) {
            throw new RuntimeException("后台进程数已达上限 " + max + "，请先使用 kill_process 终止旧进程");
        }
    }

    @PostConstruct
    void cleanupStaleLogs() {
        // 清理所有 workspace 下的残留 .zephyr-logs 文件（进程已随上次 JVM 退出终止）
        try {
            Path home = Paths.get(System.getProperty("user.home"), ".zephyr", "workspaces");
            if (Files.isDirectory(home)) {
                Files.list(home).forEach(wsDir -> {
                    Path logsDir = wsDir.resolve(".zephyr-logs");
                    if (Files.isDirectory(logsDir)) {
                        try (DirectoryStream<Path> ds = Files.newDirectoryStream(logsDir, "*.log")) {
                            for (Path f : ds) {
                                Files.deleteIfExists(f);
                                log.debug("[后台进程] 清理残留日志: {}", f);
                            }
                        } catch (IOException ignored) {}
                    }
                });
            }
        } catch (IOException e) {
            log.debug("[后台进程] 扫描残留日志失败: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("[后台进程] 应用关闭，终止所有后台进程");
        for (String user : userProcesses.keySet()) {
            killAll(user);
        }
    }

    private void cleanupLogFile(TrackedProcess tp) {
        try {
            Path logFile = Paths.get(tp.getWorkspacePath(), ".zephyr-logs", tp.getPid() + ".log");
            Files.deleteIfExists(logFile);
        } catch (Exception e) {
            log.debug("[后台进程] 清理日志文件失败 pid={}", tp.getPid());
        }
    }
}
