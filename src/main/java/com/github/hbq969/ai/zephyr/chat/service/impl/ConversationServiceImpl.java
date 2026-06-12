package com.github.hbq969.ai.zephyr.chat.service.impl;

import cn.hutool.core.lang.UUID;
import com.github.hbq969.ai.zephyr.chat.dao.ChatDao;
import com.github.hbq969.ai.zephyr.chat.dao.entity.ConversationEntity;
import com.github.hbq969.ai.zephyr.chat.dao.entity.MessageEntity;
import com.github.hbq969.ai.zephyr.chat.model.ConversationVO;
import com.github.hbq969.ai.zephyr.chat.service.ConversationService;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ConversationServiceImpl implements ConversationService {

    private static final Gson gson = new Gson();

    @Resource
    private ChatDao chatDao;

    @Override
    public List<ConversationVO> list(String userName) {
        List<ConversationEntity> entities = chatDao.queryConversations(userName);
        List<ConversationVO> vos = new ArrayList<>();
        for (ConversationEntity e : entities) {
            vos.add(ConversationVO.builder()
                    .id(e.getId())
                    .title(e.getTitle())
                    .createdAt(e.getCreatedAt())
                    .updatedAt(e.getUpdatedAt())
                    .workspaceId(e.getWorkspaceId())
                    .messageCount(chatDao.queryMessages(e.getId()).size())
                    .build());
        }
        return vos;
    }

    @Override
    @Transactional
    public ConversationVO create(Map<String, String> body, String userName) {
        String title = body.getOrDefault("title", "新对话");
        long now = System.currentTimeMillis() / 1000;
        ConversationEntity entity = new ConversationEntity();
        entity.setId(UUID.fastUUID().toString(true).substring(0, 12));
        entity.setUserName(userName);
        entity.setTitle(title);
        entity.setWorkspaceId(body.get("workspaceId"));
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        chatDao.insertConversation(entity);
        return ConversationVO.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .messageCount(0)
                .build();
    }

    @Override
    @Transactional
    public void rename(Map<String, String> body, String userName) {
        long now = System.currentTimeMillis() / 1000;
        chatDao.updateConversationTitle(body.get("id"), body.get("title"), now, userName);
    }

    @Override
    @Transactional
    public void delete(String id, String userName) {
        ConversationEntity conv = chatDao.queryConversationById(id);
        if (conv == null || !conv.getUserName().equals(userName)) {
            throw new RuntimeException("无权限或记录不存在");
        }
        chatDao.deleteMessagesByConvId(id);
        chatDao.deleteConversation(id, userName);
    }

    @Override
    @Transactional
    public void updateWorkspace(String id, String workspaceId, String userName) {
        ConversationEntity conv = chatDao.queryConversationById(id);
        if (conv == null || !conv.getUserName().equals(userName)) {
            throw new RuntimeException("无权限或记录不存在");
        }
        chatDao.updateWorkspace(id, workspaceId, userName);
    }

    @Override
    public Map<String, Object> getMessages(String conversationId, String userName) {
        ConversationEntity conv = chatDao.queryConversationById(conversationId);
        if (conv == null || !conv.getUserName().equals(userName)) {
            throw new RuntimeException("无权限或记录不存在");
        }
        List<MessageEntity> entities = chatDao.queryMessages(conversationId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (MessageEntity e : entities) {
            // 跳过后端内部消息（工具返回结果），仅展示给 LLM 的上下文用
            if ("tool".equals(e.getRole())) continue;

            Map<String, Object> msg = new HashMap<>();
            msg.put("id", e.getId());
            msg.put("role", e.getRole());
            msg.put("content", e.getContent());
            msg.put("thinking", e.getThinking());
            msg.put("timestamp", e.getCreatedAt());
            if (e.getToolCallsJson() != null && !e.getToolCallsJson().isBlank()) {
                msg.put("toolCalls", gson.fromJson(e.getToolCallsJson(),
                        new TypeToken<List<Map<String, Object>>>(){}.getType()));
            }
            // toolCalls 传给前端用于展示工具调用卡片
            result.add(msg);
        }
        Map<String, Object> response = new HashMap<>();
        response.put("messages", result);
        response.put("workspaceId", conv.getWorkspaceId());
        return response;
    }
}
