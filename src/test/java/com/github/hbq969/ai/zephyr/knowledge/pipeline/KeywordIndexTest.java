package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KeywordIndexTest {

    private KeywordIndex idx;
    private static final String KB1 = "test-kb";
    private static final String KB2 = "test-kb-2";

    @BeforeEach
    void setUp() {
        idx = new KeywordIndex();
    }

    @Test
    void bm25_shouldBoostRareTerms() {
        idx.addChunks(KB1, "doc1", List.of(
            "旋耕机是农业机械的一种，用于土壤耕作"
        ));
        idx.addChunks(KB1, "doc2", List.of(
            "农业机械的种类很多",
            "包括拖拉机、收割机等",
            "现代农业技术发展迅速",
            "旋耕机属于耕作机械",
            "机械化是农业现代化的标志"
        ));

        Map<String, Float> results = idx.search("旋耕机", List.of(KB1), 5);

        assertFalse(results.isEmpty(), "应至少召回一个 chunk");
        assertTrue(results.containsKey("doc1_0"));
        assertTrue(results.containsKey("doc2_3"));
    }

    @Test
    void bm25_idf_shouldBeDocumentLevel() {
        idx.addChunks(KB1, "long-doc", List.of(
            "API 接口定义了请求格式",
            "API 响应包含状态码",
            "API 认证使用 Bearer Token"
        ));
        idx.addChunks(KB1, "short-doc", List.of("使用 API 前需要先申请密钥"));

        Map<String, Float> results = idx.search("API", List.of(KB1), 10);
        assertEquals(4, results.size(), "所有含 API 的 chunk 都应被命中");
        assertTrue(results.containsKey("short-doc_0"));
    }

    @Test
    void bm25_shouldHandleChineseQuery() {
        idx.addChunks(KB1, "zh-doc", List.of(
            "如何配置 MCP 服务器的超时时间",
            "MCP 服务器支持多种传输协议",
            "超时时间默认为 30 秒"
        ));

        Map<String, Float> results = idx.search("MCP服务器超时时间", List.of(KB1), 5);
        assertFalse(results.isEmpty(), "中文查询应返回结果");
    }

    @Test
    void addAndRemove_shouldMaintainCorrectStats() {
        idx.addChunks(KB1, "doc-a", List.of("hello world", "hello java"));
        idx.addChunks(KB1, "doc-b", List.of("world of java"));

        Map<String, Float> results1 = idx.search("hello", List.of(KB1), 10);
        assertTrue(results1.containsKey("doc-a_0"));
        assertTrue(results1.containsKey("doc-a_1"));

        idx.removeDoc(KB1, "doc-a");
        Map<String, Float> results2 = idx.search("hello", List.of(KB1), 10);
        assertTrue(results2.isEmpty(), "移除文档后不应再命中其 chunk");
    }

    @Test
    void expandWindow_shouldReturnAdjacentChunks() {
        idx.addChunks(KB1, "doc-window", List.of(
            "第零段", "第一段", "第二段", "第三段", "第四段"
        ));

        List<String> window = idx.expandWindow("doc-window_2", 2);
        assertEquals(List.of("doc-window_0", "doc-window_1", "doc-window_2", "doc-window_3", "doc-window_4"), window);
    }

    @Test
    void expandWindow_shouldHandleBoundaries() {
        idx.addChunks(KB1, "doc-edge", List.of("首段", "次段", "尾段"));

        List<String> window = idx.expandWindow("doc-edge_0", 2);
        assertEquals(List.of("doc-edge_0", "doc-edge_1", "doc-edge_2"), window);

        window = idx.expandWindow("doc-edge_2", 2);
        assertEquals(List.of("doc-edge_0", "doc-edge_1", "doc-edge_2"), window);
    }

    @Test
    void expandWindow_shouldReturnOnlySelfWhenNoNeighbors() {
        idx.addChunks(KB1, "solo-doc", List.of("唯一一段"));

        List<String> window = idx.expandWindow("solo-doc_0", 2);
        assertEquals(List.of("solo-doc_0"), window);
    }

    @Test
    void expandWindow_shouldFindCorrectKb() {
        idx.addChunks(KB1, "kb1-doc", List.of("A1", "A2", "A3"));
        idx.addChunks(KB2, "kb2-doc", List.of("B1", "B2", "B3"));

        List<String> w1 = idx.expandWindow("kb1-doc_1", 1);
        assertEquals(List.of("kb1-doc_0", "kb1-doc_1", "kb1-doc_2"), w1,
                "应从 KB1 展开，不含 KB2 的 chunk");

        List<String> w2 = idx.expandWindow("kb2-doc_1", 1);
        assertEquals(List.of("kb2-doc_0", "kb2-doc_1", "kb2-doc_2"), w2,
                "应从 KB2 展开，不含 KB1 的 chunk");
    }
}
