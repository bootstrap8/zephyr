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
import com.github.hbq969.ai.zephyr.knowledge.pipeline.TextCleaner;
import com.github.hbq969.ai.zephyr.knowledge.pipeline.TextSplitter;
import com.github.hbq969.ai.zephyr.knowledge.pipeline.TikaParser;
import com.github.hbq969.ai.zephyr.knowledge.client.LightRagClient;
import com.github.hbq969.ai.zephyr.knowledge.service.KnowledgeService;
import com.github.hbq969.code.sm.login.model.UserInfo;
import com.github.hbq969.code.sm.login.session.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    private TextCleaner textCleaner;

    @Resource
    private EmbeddingClient embeddingClient;

    @Resource
    private ChromaClient chromaClient;

    @Resource
    private KeywordIndex keywordIndex;

    @Lazy
    @Resource
    private KnowledgeServiceImpl self;

    @Resource
    private LightRagClient lightRagClient;

    private static final String SCOPE_SHARED = "shared";
    private static final String SCOPE_USER = "user";

    private static final Set<String> STOP_WORDS = Set.of(
            "如何", "怎么", "怎样", "可以", "这个", "那个", "什么", "为什么",
            "哪些", "哪里", "是否", "能不能", "有没有", "怎么办",
            "的", "了", "吗", "呢", "吧", "啊", "嘛"
    );

    /**
     * 查询增强：从查询中提取关键词拼接到原查询后面，拉近与 chunk 的语义距离。
     * <p>
     * 中文：提取 2-4 字连续汉字片段，过滤停用词，去重。
     * 英文：提取长度 ≥ 3 的字母数字 token，去重。
     */
    private String augmentQuery(String query) {
        if (query == null || query.trim().isEmpty()) return query;

        List<String> keywords = new ArrayList<>();

        StringBuilder cnBuf = new StringBuilder();
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                cnBuf.append(c);
            } else {
                extractChineseKeywords(cnBuf.toString(), keywords);
                cnBuf.setLength(0);
            }
        }
        extractChineseKeywords(cnBuf.toString(), keywords);

        for (String token : query.split("[\\s\\p{Punct}]+")) {
            token = token.trim().toLowerCase();
            if (token.length() >= 3 && token.matches("[a-z0-9]+")) {
                keywords.add(token);
            }
        }

        if (keywords.isEmpty()) return query;

        String augmented = query + " " + String.join(" ", keywords);
        if (augmented.length() > query.length() * 3) {
            StringBuilder sb = new StringBuilder(query);
            for (String kw : keywords) {
                if (sb.length() + kw.length() + 1 > query.length() * 2) break;
                sb.append(" ").append(kw);
            }
            return sb.toString();
        }
        return augmented;
    }

    private void extractChineseKeywords(String cnText, List<String> out) {
        if (cnText.length() < 2) return;
        int maxWindow = Math.min(4, cnText.length());
        for (int w = maxWindow; w >= 2; w--) {
            for (int i = 0; i <= cnText.length() - w; i++) {
                String kw = cnText.substring(i, i + w);
                if (!STOP_WORDS.contains(kw)) {
                    out.add(kw);
                }
            }
        }
    }

    private boolean isAdmin() {
        UserInfo ui = UserContext.getNoCheck();
        return ui != null && ui.isAdmin();
    }

    private void checkSharedManage() {
        if (!isAdmin()) throw new RuntimeException("仅 admin 可管理共享知识库");
    }

    @Override
    public List<KnowledgeVO> listKb(String userName) {
        List<KnowledgeBaseEntity> all = new ArrayList<>();
        all.addAll(knowledgeDao.queryKbByUserName(userName));
        all.addAll(knowledgeDao.querySharedKbs());
        // deduplicate by id (user's own KB takes precedence)
        Map<String, KnowledgeBaseEntity> dedup = new LinkedHashMap<>();
        for (KnowledgeBaseEntity kb : all) {
            dedup.putIfAbsent(kb.getId(), kb);
        }
        boolean admin = isAdmin();
        List<KnowledgeVO> vos = new ArrayList<>();
        for (KnowledgeBaseEntity kb : dedup.values()) {
            KnowledgeVO vo = new KnowledgeVO();
            vo.setId(kb.getId());
            vo.setName(kb.getName());
            vo.setDescription(kb.getDescription());
            vo.setEmbedModelId(kb.getEmbedModelId());
            vo.setScope(kb.getScope() != null ? kb.getScope() : SCOPE_USER);
            vo.setGraphEnabled(Integer.valueOf(1).equals(kb.getGraphEnabled()));
            if (kb.getEmbedModelId() != null) {
                var model = modelConfigDao.queryById(kb.getEmbedModelId());
                if (model != null) vo.setEmbedModelName(model.getName());
            }
            vo.setDocCount(knowledgeDao.queryDocsByKbId(kb.getId()).size());
            vo.setCreatedAt(kb.getCreatedAt());
            vo.setUpdatedAt(kb.getUpdatedAt());
            // canManage: shared KBs only admin; own KBs admin + owner
            if (SCOPE_SHARED.equals(vo.getScope())) {
                vo.setCanManage(admin);
            } else {
                vo.setCanManage(admin || kb.getUserName().equals(userName));
            }
            vos.add(vo);
        }
        return vos;
    }

    @Override
    public KnowledgeBaseEntity createKb(Map<String, Object> body, String userName) {
        String scope = (String) body.getOrDefault("scope", SCOPE_USER);
        if (SCOPE_SHARED.equals(scope)) checkSharedManage();
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserName(userName);
        entity.setName((String) body.get("name"));
        entity.setDescription((String) body.getOrDefault("description", ""));
        entity.setEmbedModelId((String) body.get("embedModelId"));
        entity.setScope(scope);
        Object ge = body.get("graphEnabled");
        entity.setGraphEnabled((ge != null && (Boolean.TRUE.equals(ge) || "true".equals(String.valueOf(ge)))) ? 1 : 0);
        long now = System.currentTimeMillis() / 1000;
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        knowledgeDao.insertKb(entity);
        return entity;
    }

    @Override
    public void updateKb(Map<String, Object> body, String userName) {
        KnowledgeBaseEntity entity = knowledgeDao.queryKbById((String) body.get("id"));
        if (entity == null) {
            throw new RuntimeException("知识库不存在");
        }
        if (SCOPE_SHARED.equals(entity.getScope())) checkSharedManage();
        entity.setName((String) body.get("name"));
        entity.setDescription((String) body.getOrDefault("description", ""));
        entity.setEmbedModelId((String) body.get("embedModelId"));
        Object ge = body.get("graphEnabled");
        entity.setGraphEnabled((ge != null && (Boolean.TRUE.equals(ge) || "true".equals(String.valueOf(ge)))) ? 1 : 0);
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
        if (SCOPE_SHARED.equals(kb.getScope())) checkSharedManage();
        knowledgeDao.deleteConversationKbByKbId(id);
        keywordIndex.removeKb(id);
        knowledgeDao.deleteDocsByKbId(id);
        lightRagClient.deleteKb(id);
        knowledgeDao.deleteKb(id);
        chromaClient.deleteCollection("kb_" + id);
        deleteKbDataDir(id);
    }

    @Override
    public List<KnowledgeDocEntity> listDocs(String kbId) {
        return knowledgeDao.queryDocsByKbId(kbId);
    }

    @Override
    public void deleteDoc(String id) {
        KnowledgeDocEntity doc = knowledgeDao.queryDocById(id);
        if (doc != null) {
            KnowledgeBaseEntity kb = knowledgeDao.queryKbById(doc.getKbId());
            if (kb != null && SCOPE_SHARED.equals(kb.getScope())) checkSharedManage();
            keywordIndex.removeDoc(doc.getKbId(), id);
            lightRagClient.deleteDoc(doc.getKbId(), id);
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
        if (SCOPE_SHARED.equals(kb.getScope())) checkSharedManage();

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

        self.processDocAsync(docId, kbId, dataDir.resolve(docId + "_" + file.getOriginalFilename()));
        return docId;
    }

    @Override
    public void reParseDoc(String docId, String kbId, String userName) {
        KnowledgeDocEntity doc = knowledgeDao.queryDocById(docId);
        if (doc == null) throw new RuntimeException("文档不存在");
        KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
        if (kb != null && SCOPE_SHARED.equals(kb.getScope())) checkSharedManage();
        knowledgeDao.updateDocStatus(docId, "processing", 0, null);
        keywordIndex.removeDoc(kbId, docId);
        lightRagClient.deleteDoc(kbId, docId);
        Path dataDir = Paths.get(cfg.getKnowledge().getDataDir(), kbId);
        self.processDocAsync(docId, kbId, dataDir.resolve(docId + "_" + doc.getFileName()));
    }

    @Override
    public String createInlineDoc(String kbId, String title, String content, String userName) {
        KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
        if (kb == null) throw new RuntimeException("知识库不存在");
        if (SCOPE_SHARED.equals(kb.getScope())) checkSharedManage();

        String docId = UUID.randomUUID().toString();
        String fileName = title + ".md";
        Path dataDir = Paths.get(cfg.getKnowledge().getDataDir(), kbId);
        try {
            Files.createDirectories(dataDir);
            Files.writeString(dataDir.resolve(docId + "_" + fileName), content);
        } catch (IOException e) {
            throw new RuntimeException("保存文件失败", e);
        }

        KnowledgeDocEntity doc = new KnowledgeDocEntity();
        doc.setId(docId);
        doc.setKbId(kbId);
        doc.setFileName(fileName);
        doc.setFileType("md");
        doc.setFileSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        doc.setContent(content);
        doc.setSourceType("inline");
        doc.setStatus("processing");
        doc.setChunkCount(0);
        doc.setCreatedAt(System.currentTimeMillis() / 1000);
        knowledgeDao.insertDoc(doc);

        self.processDocContentAsync(docId, kbId, content, fileName);
        return docId;
    }

    @Override
    public void updateInlineDoc(String docId, String title, String content, String userName) {
        KnowledgeDocEntity doc = knowledgeDao.queryDocById(docId);
        if (doc == null) throw new RuntimeException("文档不存在");
        if (!"inline".equals(doc.getSourceType())) throw new RuntimeException("仅内联文档支持在线编辑");
        KnowledgeBaseEntity kb = knowledgeDao.queryKbById(doc.getKbId());
        if (kb != null && SCOPE_SHARED.equals(kb.getScope())) checkSharedManage();

        String fileName = title + ".md";
        Path dataDir = Paths.get(cfg.getKnowledge().getDataDir(), doc.getKbId());
        try {
            Files.createDirectories(dataDir);
            Files.writeString(dataDir.resolve(docId + "_" + fileName), content);
        } catch (IOException e) {
            throw new RuntimeException("保存文件失败", e);
        }

        // 删除旧索引
        keywordIndex.removeDoc(doc.getKbId(), docId);
        lightRagClient.deleteDoc(doc.getKbId(), docId);

        doc.setFileName(fileName);
        doc.setContent(content);
        doc.setFileSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        doc.setStatus("processing");
        doc.setChunkCount(0);
        doc.setUpdatedAt(System.currentTimeMillis() / 1000);
        knowledgeDao.updateDoc(doc);

        self.processDocContentAsync(docId, doc.getKbId(), content, fileName);
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

        String augmentedQuery = augmentQuery(query);

        for (Map.Entry<String, List<String>> entry : kbByModel.entrySet()) {
            ModelConfigEntity embedModel = modelConfigDao.queryById(entry.getKey());
            if (embedModel == null) {
                log.warn("Embedding 模型 {} 不存在，跳过", entry.getKey());
                continue;
            }
            List<float[]> embeddings = embeddingClient.embed(List.of(augmentedQuery), embedModel);
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
                SearchResult sr = new SearchResult(vr.getDocument(),
                        vr.getMetadata() != null ? vr.getMetadata().getOrDefault("file_name", "") : "",
                        vr.getScore());
                sr.setVecScore(vr.getScore());
                sr.setKwScore(kwResults.getOrDefault(chunkId, 0f).doubleValue());
                int rank = mergedIds.indexOf(chunkId) + 1;
                sr.setRrfScore(1.0 / (60 + rank));
                results.add(sr);
            } else {
                String text = keywordIndex.getChunkText(chunkId);
                if (text != null) {
                    double kwScore = kwResults.getOrDefault(chunkId, 0f).doubleValue();
                    SearchResult sr = new SearchResult(text, "关键词匹配", kwScore);
                    sr.setVecScore(0);
                    sr.setKwScore(kwScore);
                    int rank = mergedIds.indexOf(chunkId) + 1;
                    sr.setRrfScore(1.0 / (60 + rank));
                    results.add(sr);
                }
            }
        }
        // 图谱检索增强 — 结果作为独立上下文区块（不参与 RRF 混排）
        // --- 上下文窗口扩展 ---
        final int WINDOW_SIZE = 2;
        final int MAX_TOTAL_CHARS = 8000;

        Set<String> expandedIds = new LinkedHashSet<>();
        for (String chunkId : mergedIds) {
            List<String> window = keywordIndex.expandWindow(chunkId, WINDOW_SIZE);
            expandedIds.addAll(window);
        }

        List<String> sortedExpanded = new ArrayList<>(expandedIds);
        sortedExpanded.sort(Comparator.comparing(id -> {
            int i = id.lastIndexOf('_');
            if (i < 0) return id;
            return id.substring(0, i) + String.format("%010d", Integer.parseInt(id.substring(i + 1)));
        }));

        StringBuilder expandedText = new StringBuilder();
        for (String id : sortedExpanded) {
            String chunkText = keywordIndex.getChunkText(id);
            if (chunkText == null) continue;
            if (expandedText.length() + chunkText.length() + 1 > MAX_TOTAL_CHARS) break;
            if (expandedText.length() > 0) expandedText.append("\n\n");
            expandedText.append(chunkText);
        }

        if (!results.isEmpty() && expandedText.length() > 0) {
            SearchResult first = results.get(0);
            results.add(0, new SearchResult(expandedText.toString(),
                    first.getSourceFile() + "（上下文窗口）", first.getScore()));
        }

        for (String kbId : kbIds) {
            KnowledgeBaseEntity kb = knowledgeDao.queryKbById(kbId);
            if (kb == null || !Integer.valueOf(1).equals(kb.getGraphEnabled())) continue;

            List<KnowledgeDocEntity> docs = knowledgeDao.queryDocsByKbId(kbId);
            boolean graphNotReady = docs.stream().anyMatch(d -> "indexing".equals(d.getGraphStatus()));
            if (graphNotReady) {
                results.add(new SearchResult(
                        "知识库\"" + kb.getName() + "\"的图谱仍在索引中，暂不支持图谱增强检索",
                        "系统提示", 0.0));
                continue;
            }

            List<LightRagClient.GraphSearchResult> graphResults =
                    lightRagClient.search(kbId, query, "hybrid", topK);
            for (LightRagClient.GraphSearchResult gr : graphResults) {
                SearchResult sr = new SearchResult(
                        gr.getContent() != null ? gr.getContent() : "",
                        "图谱",
                        0.0);
                results.add(sr);
            }
        }
        return results;
    }

    @Async
    public void processDocAsync(String docId, String kbId, Path filePath) {
        try (InputStream in = Files.newInputStream(filePath)) {
            KnowledgeDocEntity doc = knowledgeDao.queryDocById(docId);
            if (doc == null) { log.warn("文档已被删除，取消处理: docId={}", docId); return; }
            String text = tikaParser.parse(in);
            self.processDocContentAsync(docId, kbId, text, filePath.getFileName().toString().replace(docId + "_", ""));
        } catch (Exception e) {
            log.error("文档处理失败: docId={}", docId, e);
            knowledgeDao.updateDocStatus(docId, "error", 0, e.getMessage());
        }
    }

    @Async
    public void processDocContentAsync(String docId, String kbId, String text, String displayName) {
        try {
            KnowledgeDocEntity doc = knowledgeDao.queryDocById(docId);
            if (doc == null) { log.warn("文档已被删除，取消处理: docId={}", docId); return; }

            text = textCleaner.clean(text);

            TextSplitter splitter = new TextSplitter(800, 150);
            List<String> chunks = splitter.split(text);
            chunks = textCleaner.filterLowQualityChunks(chunks);
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

            int batchSize = 100;
            for (int batchStart = 0; batchStart < chunks.size(); batchStart += batchSize) {
                int batchEnd = Math.min(batchStart + batchSize, chunks.size());
                List<String> batchChunks = chunks.subList(batchStart, batchEnd);

                List<String> batchIds = new ArrayList<>();
                List<Map<String, String>> batchMetas = new ArrayList<>();
                for (int i = batchStart; i < batchEnd; i++) {
                    batchIds.add(docId + "_" + i);
                    Map<String, String> meta = new HashMap<>();
                    meta.put("doc_id", docId);
                    meta.put("file_name", displayName);
                    meta.put("chunk_index", String.valueOf(i));
                    batchMetas.add(meta);
                }

                List<float[]> batchEmbeddings = embeddingClient.embed(batchChunks, embedModel);
                chromaClient.add(collId, batchIds, batchEmbeddings, batchMetas, batchChunks);
                log.info("文档处理进度: docId={}, {}/{} chunks", docId, batchEnd, chunks.size());
            }

            keywordIndex.addChunks(kbId, docId, chunks);
            knowledgeDao.updateDocStatus(docId, "ready", chunks.size(), null);
            log.info("文档处理完成: docId={}, chunks={}", docId, chunks.size());

            // 图谱索引（ready 之后执行，追踪状态）
            KnowledgeBaseEntity kbRef = knowledgeDao.queryKbById(kbId);
            if (kbRef != null && Integer.valueOf(1).equals(kbRef.getGraphEnabled())) {
                try {
                    knowledgeDao.updateDocGraphStatus(docId, "indexing");
                    lightRagClient.index(kbId, docId, text);
                    knowledgeDao.updateDocGraphStatus(docId, "ready");
                } catch (Exception e) {
                    log.warn("图谱索引失败: docId={}", docId, e);
                    knowledgeDao.updateDocGraphStatus(docId, "error");
                }
            }
        } catch (Exception e) {
            log.error("文档处理失败: docId={}", docId, e);
            knowledgeDao.updateDocStatus(docId, "error", 0, e.getMessage());
        }
    }

    private void deleteKbDataDir(String kbId) {
        try {
            Path dataDir = Paths.get(cfg.getKnowledge().getDataDir(), kbId);
            if (Files.exists(dataDir)) {
                try (var stream = Files.walk(dataDir)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.delete(p); } catch (IOException e) { log.warn("删除文件失败: {}", p, e); }
                    });
                }
            }
        } catch (Exception e) {
            log.warn("清理知识库文件目录失败: kbId={}", kbId, e);
        }
    }

    private String fileType(String fileName) {
        if (fileName == null) return "unknown";
        int i = fileName.lastIndexOf('.');
        return i >= 0 ? fileName.substring(i + 1).toLowerCase() : "unknown";
    }

    @Override
    @Transactional
    public void toggleKbScope(String id, String scope, String userName) {
        checkSharedManage();
        knowledgeDao.updateKbScope(id, scope);
    }
}
