package com.github.hbq969.ai.zephyr.knowledge.service.impl;

import com.github.hbq969.ai.zephyr.config.dao.ModelConfigDao;
import com.github.hbq969.ai.zephyr.knowledge.dao.KnowledgeDao;
import com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeBaseEntity;
import com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeDocEntity;
import com.github.hbq969.ai.zephyr.knowledge.model.KnowledgeVO;
import com.github.hbq969.ai.zephyr.knowledge.service.KnowledgeService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class KnowledgeServiceImpl implements KnowledgeService {

    @Resource
    private KnowledgeDao knowledgeDao;

    @Resource
    private ModelConfigDao modelConfigDao;

    @Override
    public List<KnowledgeVO> listKb(String userName) {
        List<KnowledgeBaseEntity> kbs = knowledgeDao.queryKbByUserName(userName);
        List<KnowledgeVO> vos = new ArrayList<>();
        for (KnowledgeBaseEntity kb : kbs) {
            KnowledgeVO vo = new KnowledgeVO();
            vo.setId(kb.getId());
            vo.setName(kb.getName());
            vo.setDescription(kb.getDescription());
            vo.setEmbedModelId(kb.getEmbedModelId());
            if (kb.getEmbedModelId() != null) {
                var model = modelConfigDao.queryById(kb.getEmbedModelId());
                if (model != null) {
                    vo.setEmbedModelName(model.getName());
                }
            }
            List<KnowledgeDocEntity> docs = knowledgeDao.queryDocsByKbId(kb.getId());
            vo.setDocCount(docs.size());
            vo.setCreatedAt(kb.getCreatedAt());
            vo.setUpdatedAt(kb.getUpdatedAt());
            vos.add(vo);
        }
        return vos;
    }

    @Override
    public KnowledgeBaseEntity createKb(Map<String, String> body, String userName) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserName(userName);
        entity.setName(body.get("name"));
        entity.setDescription(body.getOrDefault("description", ""));
        entity.setEmbedModelId(body.get("embedModelId"));
        long now = System.currentTimeMillis() / 1000;
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        knowledgeDao.insertKb(entity);
        return entity;
    }

    @Override
    public void updateKb(Map<String, String> body, String userName) {
        KnowledgeBaseEntity entity = knowledgeDao.queryKbById(body.get("id"));
        if (entity == null) {
            throw new RuntimeException("知识库不存在");
        }
        entity.setName(body.get("name"));
        entity.setDescription(body.getOrDefault("description", ""));
        entity.setEmbedModelId(body.get("embedModelId"));
        entity.setUpdatedAt(System.currentTimeMillis() / 1000);
        knowledgeDao.updateKb(entity);
    }

    @Override
    @Transactional
    public void deleteKb(String id, String userName) {
        KnowledgeBaseEntity kb = knowledgeDao.queryKbById(id);
        if (kb == null) {
            throw new RuntimeException("知识库不存在");
        }
        knowledgeDao.deleteDocsByKbId(id);
        knowledgeDao.deleteKb(id);
    }

    @Override
    public List<KnowledgeDocEntity> listDocs(String kbId) {
        return knowledgeDao.queryDocsByKbId(kbId);
    }

    @Override
    public void deleteDoc(String id) {
        knowledgeDao.deleteDoc(id);
    }

    @Override
    public List<String> getConversationKbIds(String conversationId) {
        return knowledgeDao.queryKbIdsByConversation(conversationId);
    }

    @Override
    public void saveConversationKbIds(String conversationId, List<String> kbIds) {
        knowledgeDao.deleteConversationKb(conversationId);
        if (kbIds != null) {
            for (String kbId : kbIds) {
                knowledgeDao.insertConversationKb(conversationId, kbId);
            }
        }
    }
}
