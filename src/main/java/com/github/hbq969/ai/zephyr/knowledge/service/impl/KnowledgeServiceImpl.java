package com.github.hbq969.ai.zephyr.knowledge.service.impl;

import com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties;
import com.github.hbq969.ai.zephyr.config.dao.ModelConfigDao;
import com.github.hbq969.ai.zephyr.knowledge.dao.KnowledgeDao;
import com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeBaseEntity;
import com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeDocEntity;
import com.github.hbq969.ai.zephyr.knowledge.model.KnowledgeVO;
import com.github.hbq969.ai.zephyr.knowledge.pipeline.ChromaClient;
import com.github.hbq969.ai.zephyr.knowledge.pipeline.EmbeddingClient;
import com.github.hbq969.ai.zephyr.knowledge.pipeline.TextSplitter;
import com.github.hbq969.ai.zephyr.knowledge.pipeline.TikaParser;
import com.github.hbq969.ai.zephyr.knowledge.service.KnowledgeService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Slf4j
@Service
public class KnowledgeServiceImpl implements KnowledgeService {

    @Resource
    private KnowledgeDao knowledgeDao;

    @Resource
    private ModelConfigDao modelConfigDao;

    @Resource
    private ZephyrConfigProperties cfg;

    @Resource
    private TikaParser tikaParser;

    @Resource
    private EmbeddingClient embeddingClient;

    @Resource
    private ChromaClient chromaClient;

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

    @Override
    public String uploadDoc(String kbId, MultipartFile file, String userName) {
        KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
        if (kb == null) throw new RuntimeException("知识库不存在");

        String docId = UUID.randomUUID().toString();
        Path dataDir = Paths.get(cfg.getKnowledge().getDataDir(), kbId);
        try {
            Files.createDirectories(dataDir);
            file.transferTo(dataDir.resolve(docId + "_" + file.getOriginalFilename()).toFile());
        } catch (IOException e) {
            throw new RuntimeException("保存文件失败", e);
        }

        KnowledgeDocEntity doc = new KnowledgeDocEntity();
        doc.setId(docId);
        doc.setKbId(kbId);
        doc.setFileName(file.getOriginalFilename());
        doc.setFileType(fileType(file.getOriginalFilename()));
        doc.setFileSize(file.getSize());
        doc.setStatus("processing");
        doc.setChunkCount(0);
        doc.setCreatedAt(System.currentTimeMillis() / 1000);
        knowledgeDao.insertDoc(doc);

        processDocAsync(docId, kbId, dataDir.resolve(docId + "_" + file.getOriginalFilename()));
        return docId;
    }

    @Override
    public void reParseDoc(String docId, String kbId, String userName) {
        KnowledgeDocEntity doc = knowledgeDao.queryDocById(docId);
        if (doc == null) throw new RuntimeException("文档不存在");
        knowledgeDao.updateDocStatus(docId, "processing", 0, null);
        Path dataDir = Paths.get(cfg.getKnowledge().getDataDir(), kbId);
        processDocAsync(docId, kbId, dataDir.resolve(docId + "_" + doc.getFileName()));
    }

    @Async
    public void processDocAsync(String docId, String kbId, Path filePath) {
        try (InputStream in = Files.newInputStream(filePath)) {
            String text = tikaParser.parse(in);
            TextSplitter splitter = new TextSplitter(800, 150);
            List<String> chunks = splitter.split(text);
            if (chunks.isEmpty()) {
                knowledgeDao.updateDocStatus(docId, "error", 0, "文档内容为空");
                return;
            }

            KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
            var embedModel = modelConfigDao.queryById(kb.getEmbedModelId());
            if (embedModel == null) {
                knowledgeDao.updateDocStatus(docId, "error", 0, "Embedding 模型未配置");
                return;
            }

            String collection = "kb_" + kbId;
            chromaClient.createCollection(collection);

            List<String> ids = new ArrayList<>();
            List<Map<String, String>> metadatas = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                ids.add(docId + "_" + i);
                Map<String, String> meta = new HashMap<>();
                meta.put("doc_id", docId);
                meta.put("file_name", filePath.getFileName().toString().replace(docId + "_", ""));
                meta.put("chunk_index", String.valueOf(i));
                metadatas.add(meta);
            }

            List<float[]> embeddings = embeddingClient.embed(chunks, embedModel);
            chromaClient.add(collection, ids, embeddings, metadatas, chunks);

            knowledgeDao.updateDocStatus(docId, "ready", chunks.size(), null);
            log.info("文档处理完成: docId={}, chunks={}", docId, chunks.size());
        } catch (Exception e) {
            log.error("文档处理失败: docId={}", docId, e);
            knowledgeDao.updateDocStatus(docId, "error", 0, e.getMessage());
        }
    }

    private String fileType(String fileName) {
        if (fileName == null) return "unknown";
        int i = fileName.lastIndexOf('.');
        return i >= 0 ? fileName.substring(i + 1).toLowerCase() : "unknown";
    }
}
