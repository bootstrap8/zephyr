package com.github.hbq969.ai.zephyr.chat.service;

import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

public interface ChatService {
    SseEmitter send(String userName, String conversationId, String workspaceId, String message, List<String> filePaths);
    void cancel(String userName);
    Map<String, Object> contextUsage(String userName, String conversationId);
    Map<String, Object> upload(MultipartFile file, String workspaceId, String userName);
}
