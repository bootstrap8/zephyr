package com.github.hbq969.ai.zephyr.chat.service;

import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

public interface ChatService {
    SseEmitter send(String userName, String conversationId, String workspaceId, String message, String mode, List<String> filePaths);
    void cancel(String userName);
    void cancelByConversationId(String conversationId);
    Map<String, Object> contextUsage(String userName, String conversationId, String mode);
    Map<String, Object> upload(MultipartFile file, String workspaceId, String userName);
    void confirm(String confirmId, boolean allowed);
}
