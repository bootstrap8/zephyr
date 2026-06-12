package com.github.hbq969.ai.zephyr.chat.dao;

import com.github.hbq969.ai.zephyr.chat.dao.entity.ConversationEntity;
import com.github.hbq969.ai.zephyr.chat.dao.entity.MessageEntity;
import com.github.hbq969.code.common.datasource.DS;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
@DS
public interface ChatDao {

    void createConversationsTable();
    void createMessagesTable();

    List<ConversationEntity> queryConversations(@Param("userName") String userName);
    void insertConversation(ConversationEntity entity);
    void updateConversationTitle(@Param("id") String id, @Param("title") String title, @Param("updatedAt") Long updatedAt, @Param("userName") String userName);
    void deleteConversation(@Param("id") String id, @Param("userName") String userName);
    ConversationEntity queryConversationById(@Param("id") String id);

    List<MessageEntity> queryMessages(@Param("conversationId") String conversationId);
    void insertMessage(MessageEntity entity);
    void deleteMessagesByConvId(@Param("conversationId") String conversationId);
    void updateWorkspace(@Param("id") String id, @Param("workspaceId") String workspaceId, @Param("userName") String userName);
}
