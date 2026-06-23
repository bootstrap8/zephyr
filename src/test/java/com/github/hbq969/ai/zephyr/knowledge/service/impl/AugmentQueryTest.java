package com.github.hbq969.ai.zephyr.knowledge.service.impl;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class AugmentQueryTest {

    private String callAugmentQuery(String query) throws Exception {
        KnowledgeServiceImpl svc = new KnowledgeServiceImpl();
        Method m = KnowledgeServiceImpl.class.getDeclaredMethod("augmentQuery", String.class);
        m.setAccessible(true);
        return (String) m.invoke(svc, query);
    }

    @Test
    void shouldAppendChineseKeywords() throws Exception {
        String result = callAugmentQuery("如何配置MCP服务器超时时间");
        assertTrue(result.startsWith("如何配置MCP服务器超时时间 "));
        assertTrue(result.contains("配置"));
        assertTrue(result.contains("服务器"));
        // 停用词"如何"作为独立关键词不应出现
        String[] parts = result.split(" ", 2);
        if (parts.length > 1) {
            // 作为独立 token 检查（空格分隔），允许作为子串出现（如"如何配置"是合法4-gram）
            assertFalse(java.util.Arrays.asList(parts[1].split(" ")).contains("如何"),
                    "独立停用词'如何'不应出现");
        }
    }

    @Test
    void shouldNotContainStopWords() throws Exception {
        String result = callAugmentQuery("怎么可以这样做");
        String[] parts = result.split(" ", 2);
        if (parts.length > 1) {
            var tokens = java.util.Arrays.asList(parts[1].split(" "));
            assertFalse(tokens.contains("怎么"), "独立停用词'怎么'不应出现");
            assertFalse(tokens.contains("可以"), "独立停用词'可以'不应出现");
        }
    }

    @Test
    void shouldHandleEnglishTokens() throws Exception {
        String result = callAugmentQuery("fix bug in MCP handler");
        // 原始查询保留，>=3 chars 的 token 应作为关键词追加
        assertTrue(result.startsWith("fix bug in MCP handler"));
        // 结果中应包含追加的关键词
        assertTrue(result.contains("fix"));
        assertTrue(result.contains("bug"));
        assertTrue(result.contains("mcp"));
        assertTrue(result.contains("handler"));
        // 原始查询中的 "in" 自然存在（它来自原查询），这不是 bug
    }

    @Test
    void shouldLimitAugmentedLength() throws Exception {
        String longQuery = "a".repeat(200);
        String result = callAugmentQuery(longQuery);
        assertTrue(result.length() <= longQuery.length() * 3 + 10);
    }

    @Test
    void shouldReturnOriginalForEmptyQuery() throws Exception {
        assertEquals("", callAugmentQuery(""));
        assertNull(callAugmentQuery(null));
    }
}
