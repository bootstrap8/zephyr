package com.github.hbq969.ai.zephyr.chat.service;

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

    public SessionHandle register(String conversationId, String userName) {
        SessionHandle handle = new SessionHandle(conversationId, userName);
        sessions.put(conversationId, handle);
        log.debug("会话注册: conversationId={}, userName={}", conversationId, userName);
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
        log.debug("会话注销: conversationId={}", conversationId);
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
                log.info("会话超时取消: conversationId={}, idle={}s",
                        h.conversationId, now - h.lastActivityTime);
                h.cancel();
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        log.info("ConversationSessionManager 关闭，取消所有活跃会话");
        sessions.values().forEach(SessionHandle::cancel);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
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
        volatile long lastActivityTime;
        volatile boolean cancelled;

        SessionHandle(String conversationId, String userName) {
            this.conversationId = conversationId;
            this.userName = userName;
            this.lastActivityTime = System.currentTimeMillis() / 1000;
        }

        public String getConversationId() { return conversationId; }
        public String getUserName() { return userName; }

        public void touch() {
            this.lastActivityTime = System.currentTimeMillis() / 1000;
        }

        public long getLastActivityTime() {
            return lastActivityTime;
        }

        public void cancel() {
            this.cancelled = true;
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

    public static class CancelSessionException extends RuntimeException {
        public CancelSessionException(String conversationId) {
            super("会话已取消: " + conversationId);
        }
    }
}
