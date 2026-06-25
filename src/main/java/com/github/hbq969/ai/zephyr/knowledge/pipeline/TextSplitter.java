package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextSplitter {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,4}\\s+.+$", Pattern.MULTILINE);
    private static final Pattern CODE_BLOCK = Pattern.compile("```[\\s\\S]*?```");
    // 判断一个 chunk 自身是否就是标题行，避免标题前缀重复拼接
    private static final Pattern HEADING_LINE = Pattern.compile("^#{1,4}\\s");

    // 分隔符优先级：双换行 > 中文句号 > 中文感叹号 > 中文问号 > 单换行
    private static final List<String> SEPARATORS = List.of("\n\n", "。", "！", "？", "\n");

    private final int chunkSize;
    private final int overlap;
    private final int minChunkSize;

    /**
     * @param chunkSize 最大字符数
     * @param overlap   char-level 硬切时的重叠量；基于分隔符的切分不产生重叠
     */
    public TextSplitter(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.minChunkSize = Math.max(chunkSize / 4, 100);
    }

    public TextSplitter() {
        this(DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
    }

    public List<String> split(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return result;

        Map<String, String> placeholders = new HashMap<>();
        text = protectCodeBlocks(text, placeholders);

        List<Heading> headings = extractHeadings(text);

        List<String> rawChunks = new ArrayList<>();
        splitRecursive(text, rawChunks);

        for (String chunk : rawChunks) {
            String restored = restorePlaceholders(chunk, placeholders);
            if (restored.isEmpty()) continue;
            String heading = findNearestHeading(headings, text, chunk);
            // 如果 chunk 自身就是标题行，不再重复拼接
            if (heading != null && !heading.isEmpty() && !HEADING_LINE.matcher(restored).find()) {
                result.add(heading + "\n" + restored);
            } else {
                result.add(restored);
            }
        }
        return result;
    }

    private void splitRecursive(String text, List<String> chunks) {
        if (text.length() <= chunkSize) {
            if (!text.trim().isEmpty()) chunks.add(text.trim());
            return;
        }
        String sep = findSeparator(text);
        if (sep == null) {
            hardSplit(text, chunks);
            return;
        }
        String[] parts = text.split(Pattern.quote(sep), -1);
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            if (part.length() > chunkSize) {
                // 单个 part 超过 chunkSize：先提交 current，再递归切分大 part
                if (current.length() >= minChunkSize) {
                    String s = current.toString().trim();
                    if (!s.isEmpty()) chunks.add(s);
                    current = new StringBuilder();
                }
                splitRecursive(part, chunks);
                continue;
            }
            String candidate = current.length() > 0 ? current + sep + part : part;
            if (candidate.length() > chunkSize && current.length() >= minChunkSize) {
                String s2 = current.toString().trim();
                if (!s2.isEmpty()) chunks.add(s2);
                current = new StringBuilder(part);
            } else {
                if (current.length() > 0) current.append(sep);
                current.append(part);
            }
        }
        if (current.length() > 0) {
            String s = current.toString().trim();
            if (!s.isEmpty()) {
                if (s.length() > chunkSize) splitRecursive(s, chunks);
                else chunks.add(s);
            }
        }
    }

    private void hardSplit(String text, List<String> chunks) {
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            String s = text.substring(start, end).trim();
            if (!s.isEmpty()) chunks.add(s);
            if (end >= text.length()) break;
            start = end - overlap;
        }
    }

    private String findSeparator(String text) {
        for (String sep : SEPARATORS) {
            if (text.contains(sep)) return sep;
        }
        return null;
    }

    private List<Heading> extractHeadings(String text) {
        List<Heading> result = new ArrayList<>();
        Matcher m = MARKDOWN_HEADING.matcher(text);
        while (m.find()) result.add(new Heading(m.start(), m.group().trim()));
        return result;
    }

    private String findNearestHeading(List<Heading> headings, String fullText, String chunk) {
        if (headings.isEmpty()) return null;
        int pos = fullText.indexOf(chunk.substring(0, Math.min(50, chunk.length())));
        if (pos < 0) return null;
        Heading nearest = null;
        for (Heading h : headings) {
            if (h.pos <= pos) nearest = h;
            else break;
        }
        return nearest != null ? nearest.text : null;
    }

    private String protectCodeBlocks(String text, Map<String, String> placeholders) {
        Matcher m = CODE_BLOCK.matcher(text);
        StringBuffer sb = new StringBuffer();
        int idx = 0;
        while (m.find()) {
            String key = "%%CB" + idx + "%%";
            placeholders.put(key, m.group());
            m.appendReplacement(sb, Matcher.quoteReplacement(key));
            idx++;
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String restorePlaceholders(String text, Map<String, String> placeholders) {
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            text = text.replace(e.getKey(), e.getValue());
        }
        return text;
    }

    private static class Heading {
        final int pos;
        final String text;
        Heading(int pos, String text) { this.pos = pos; this.text = text; }
    }
}
