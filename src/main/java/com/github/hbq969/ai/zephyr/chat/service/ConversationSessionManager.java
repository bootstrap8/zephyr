package com.github.hbq969.ai.zephyr.chat.service;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;

import com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class ConversationSessionManager {

    private final ConcurrentHashMap<String, SessionHandle> sessions = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Resource
    private ZephyrConfigProperties cfg;

    @Resource
    private TaskScheduler taskScheduler;

    public SessionHandle register(String conversationId, String userName, String workspacePath) {
        SessionHandle handle = new SessionHandle(conversationId, userName, workspacePath);
        sessions.put(conversationId, handle);
        log.info("[SSE] 注册连接 cid={}, user={}, 当前活跃连接: {}", conversationId, userName, sessions.size());
        return handle;
    }

    public SessionHandle get(String conversationId) {
        return sessions.get(conversationId);
    }

    public List<SessionHandle> getByUser(String userName) {
        return sessions.values().stream()
                .filter(h -> h.getUserName().equals(userName))
                .toList();
    }

    public void remove(String conversationId) {
        sessions.remove(conversationId);
        log.info("[SSE] 连接关闭 cid={}, 剩余活跃连接: {}", conversationId, sessions.size());
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    @jakarta.annotation.PostConstruct
    void startScan() {
        long intervalMs = cfg.getChat().getSessionScanIntervalSeconds() * 1000L;
        taskScheduler.scheduleAtFixedRate(this::scanExpired,
                Instant.now().plusMillis(intervalMs),
                Duration.ofMillis(intervalMs));
    }

    void scanExpired() {
        long now = System.currentTimeMillis() / 1000;
        long idleTimeout = cfg.getChat().getSessionIdleTimeoutSeconds();
        sessions.values().forEach(h -> {
            if (!h.cancelled && now - h.lastActivityTime > idleTimeout) {
                log.info("SSE 连接空闲超时取消: conversationId={}, idle={}s",
                        h.conversationId, now - h.lastActivityTime);
                h.cancel();
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        log.info("ConversationSessionManager 关闭，取消所有活跃 SSE 连接");
        sessions.values().forEach(SessionHandle::cancel);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static class SessionHandle {
        private final String conversationId;
        private final String userName;
        private final String workspacePath;
        volatile long lastActivityTime;
        volatile boolean cancelled;

        SessionHandle(String conversationId, String userName, String workspacePath) {
            this.conversationId = conversationId;
            this.userName = userName;
            this.workspacePath = workspacePath;
            this.lastActivityTime = System.currentTimeMillis() / 1000;
        }

        public String getConversationId() { return conversationId; }
        public String getUserName() { return userName; }
        public String getWorkspacePath() { return workspacePath; }

        public void touch() {
            this.lastActivityTime = System.currentTimeMillis() / 1000;
        }

        public long getLastActivityTime() {
            return lastActivityTime;
        }

        private final List<ProcessSlot> trackedProcesses = new CopyOnWriteArrayList<>();

        public ProcessSlot reserveProcessSlot(String command) {
            ProcessSlot slot = new ProcessSlot(command);
            trackedProcesses.add(slot);
            return slot;
        }

        public void killTrackedProcesses() {
            for (ProcessSlot s : trackedProcesses) {
                if (s.state == ProcessSlot.State.BOUND) {
                    try {
                        ProcessHandle.of(s.pid).ifPresent(ph -> {
                            ph.descendants().forEach(ProcessHandle::destroyForcibly);
                            ph.destroyForcibly();
                        });
                        log.info("[SSE] 清理进程 cid={}, pid={}, cmd={}", conversationId, s.pid, s.command);
                    } catch (Exception e) {
                        log.warn("[SSE] 清理进程失败 cid={}, pid={}", conversationId, s.pid, e);
                    }
                }
            }
            trackedProcesses.clear();
        }

        public void cancel() {
            if (!this.cancelled) {
                this.cancelled = true;
                log.info("[SSE] 连接标记取消 cid={}, idle={}s",
                        conversationId, System.currentTimeMillis() / 1000 - lastActivityTime);
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public void checkCancel() {
            if (cancelled) {
                throw new CancelSessionException(conversationId);
            }
        }
    }

    public static class ProcessSlot {
        enum State { RESERVED, BOUND, FAILED }

        final String command;
        volatile State state = State.RESERVED;
        volatile long pid;

        ProcessSlot(String command) {
            this.command = command;
        }

        public void bind(long pid) {
            this.pid = pid;
            this.state = State.BOUND;
        }

        public void markFailed() {
            this.state = State.FAILED;
        }
    }

    public static class CancelSessionException extends RuntimeException {
        public CancelSessionException(String conversationId) {
            super("会话已取消: " + conversationId);
        }
    }
}
