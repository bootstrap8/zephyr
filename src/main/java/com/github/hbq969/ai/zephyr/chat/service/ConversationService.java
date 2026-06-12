package com.github.hbq969.ai.zephyr.chat.service;

import com.github.hbq969.ai.zephyr.chat.model.ConversationVO;

import java.util.List;
import java.util.Map;

public interface ConversationService {
    List<ConversationVO> list(String userName);
    ConversationVO create(Map<String, String> body, String userName);
    void rename(Map<String, String> body, String userName);
    void delete(String id, String userName);
    Map<String, Object> getMessages(String conversationId, String userName);
    void updateWorkspace(String id, String workspaceId, String userName);
}
