package com.github.hbq969.ai.zephyr.knowledge.service.impl;

import com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties;
import com.github.hbq969.ai.zephyr.config.dao.ModelConfigDao;
import com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity;
import com.github.hbq969.ai.zephyr.knowledge.dao.KnowledgeDao;
import com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeBaseEntity;
import com.github.hbq969.ai.zephyr.knowledge.dao.entity.KnowledgeDocEntity;
import com.github.hbq969.ai.zephyr.knowledge.model.KnowledgeVO;
import com.github.hbq969.ai.zephyr.knowledge.pipeline.ChromaClient;
import com.github.hbq969.ai.zephyr.knowledge.pipeline.EmbeddingClient;
import com.github.hbq969.ai.zephyr.knowledge.pipeline.KeywordIndex;
import com.github.hbq969.ai.zephyr.knowledge.pipeline.RrfMerger;
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

    @Resource
    private KeywordIndex keywordIndex;

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
        knowledgeDao.deleteConversationKbByKbId(id);
        keywordIndex.removeKb(id);
        knowledgeDao.deleteDocsByKbId(id);
        knowledgeDao.deleteKb(id);
    }

    @Override
    public List<KnowledgeDocEntity> listDocs(String kbId) {
        return knowledgeDao.queryDocsByKbId(kbId);
    }

    @Override
    public void deleteDoc(String id) {
        KnowledgeDocEntity doc = knowledgeDao.queryDocById(id);
        if (doc != null) {
            keywordIndex.removeDoc(doc.getKbId(), id);
        }
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
        keywordIndex.removeDoc(kbId, docId);
        Path dataDir = Paths.get(cfg.getKnowledge().getDataDir(), kbId);
        processDocAsync(docId, kbId, dataDir.resolve(docId + "_" + doc.getFileName()));
    }

    @Override
    public List<SearchResult> search(String query, List<String> kbIds, int topK) {
        if (kbIds == null || kbIds.isEmpty()) return List.of();

        Map<String, List<String>> kbByModel = new LinkedHashMap<>();
        for (String kbId : kbIds) {
            KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
            if (kb == null || kb.getEmbedModelId() == null) {
                log.warn("知识库 {} 未配置 Embedding 模型，跳过", kbId);
                continue;
            }
            kbByModel.computeIfAbsent(kb.getEmbedModelId(), k -> new ArrayList<>()).add(kbId);
        }
        if (kbByModel.isEmpty()) throw new RuntimeException("所选知识库均未配置 Embedding 模型");

        int fetchSize = topK * 2;
        List<ChromaClient.QueryResult> allVecResults = new ArrayList<>();

        for (Map.Entry<String, List<String>> entry : kbByModel.entrySet()) {
            ModelConfigEntity embedModel = modelConfigDao.queryById(entry.getKey());
            if (embedModel == null) {
                log.warn("Embedding 模型 {} 不存在，跳过", entry.getKey());
                continue;
            }
            List<float[]> embeddings = embeddingClient.embed(List.of(query), embedModel);
            if (embeddings.isEmpty()) continue;
            for (String kbId : entry.getValue()) {
                try {
                    String collId = chromaClient.getOrCreateCollection("kb_" + kbId);
                    allVecResults.addAll(chromaClient.query(collId, embeddings.get(0), fetchSize));
                } catch (Exception e) {
                    log.warn("知识库 {} 向量检索失败: {}", kbId, e.getMessage());
                }
            }
        }

        Map<String, Float> kwResults = keywordIndex.search(query, kbIds, fetchSize);

        allVecResults.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        RrfMerger merger = new RrfMerger(60);
        List<String> mergedIds = merger.merge(allVecResults, kwResults, topK);

        Map<String, ChromaClient.QueryResult> vecMap = new HashMap<>();
        for (ChromaClient.QueryResult r : allVecResults) {
            vecMap.put(r.getId(), r);
        }

        List<SearchResult> results = new ArrayList<>();
        for (String chunkId : mergedIds) {
            ChromaClient.QueryResult vr = vecMap.get(chunkId);
            if (vr != null) {
                results.add(new SearchResult(vr.getDocument(),
                        vr.getMetadata() != null ? vr.getMetadata().getOrDefault("file_name", "") : "",
                        vr.getScore()));
            } else {
                String text = keywordIndex.getChunkText(chunkId);
                if (text != null) {
                    double kwScore = kwResults.getOrDefault(chunkId, 0f).doubleValue();
                    results.add(new SearchResult(text, "关键词匹配", kwScore));
                }
            }
        }
        return results;
    }

    @Async
    public void processDocAsync(String docId, String kbId, Path filePath) {
        try (InputStream in = Files.newInputStream(filePath)) {
            KnowledgeDocEntity doc = knowledgeDao.queryDocById(docId);
            if (doc == null) {
                log.warn("文档已被删除，取消处理: docId={}", docId);
                return;
            }
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
            String collId = chromaClient.getOrCreateCollection(collection);

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
            chromaClient.add(collId, ids, embeddings, metadatas, chunks);
            keywordIndex.addChunks(kbId, docId, chunks);

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
