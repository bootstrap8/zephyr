package com.github.hbq969.ai.zephyr.knowledge.dao;

import com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeBaseEntity;
import com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeDocEntity;
import com.github.hbq969.code.common.datasource.DS;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
@DS
public interface KnowledgeDao {
    // DDL
    void createKnowledgeBaseTable();
    void createKnowledgeDocTable();
    void createConversationKbTable();

    // Knowledge Base CRUD
    List<KnowledgeBaseEntity> queryKbByUserName(@Param("userName") String userName);
    KnowledgeBaseEntity queryKbById(@Param("id") String id);
    List<KnowledgeBaseEntity> queryKbByIds(@Param("ids") List<String> ids);
    void insertKb(KnowledgeBaseEntity entity);
    void updateKb(KnowledgeBaseEntity entity);
    void deleteKb(@Param("id") String id);

    // Document CRUD
    List<KnowledgeDocEntity> queryDocsByKbId(@Param("kbId") String kbId);
    KnowledgeDocEntity queryDocById(@Param("id") String id);
    void insertDoc(KnowledgeDocEntity entity);
    void updateDocStatus(@Param("id") String id, @Param("status") String status,
                         @Param("chunkCount") Integer chunkCount, @Param("errorMsg") String errorMsg);
    void deleteDoc(@Param("id") String id);
    void deleteDocsByKbId(@Param("kbId") String kbId);

    // Conversation-KB association
    List<String> queryKbIdsByConversation(@Param("conversationId") String conversationId);
    void insertConversationKb(@Param("conversationId") String conversationId, @Param("kbId") String kbId);
    void deleteConversationKb(@Param("conversationId") String conversationId);
    void deleteConversationKbByKbId(@Param("kbId") String kbId);
}
