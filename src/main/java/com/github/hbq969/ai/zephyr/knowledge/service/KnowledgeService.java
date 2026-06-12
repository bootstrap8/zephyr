package com.github.hbq969.ai.zephyr.knowledge.service;

import com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeBaseEntity;
import com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeDocEntity;
import com.github.hbq969.ai.zephyr.knowledge.model.KnowledgeVO;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface KnowledgeService {
    List<KnowledgeVO> listKb(String userName);

    KnowledgeBaseEntity createKb(Map<String, String> body, String userName);

    void updateKb(Map<String, String> body, String userName);

    void deleteKb(String id, String userName);

    List<KnowledgeDocEntity> listDocs(String kbId);

    void deleteDoc(String id);

    List<String> getConversationKbIds(String conversationId);

    void saveConversationKbIds(String conversationId, List<String> kbIds);

    String uploadDoc(String kbId, MultipartFile file, String userName);

    void reParseDoc(String docId, String kbId, String userName);

    @Data
    class SearchResult {
        private final String content;
        private final String sourceFile;
        private final double score;
    }
}
