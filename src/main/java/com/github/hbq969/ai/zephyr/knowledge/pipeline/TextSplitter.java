package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextSplitter {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^#{1,4}\\s+.+$", Pattern.MULTILINE);
    private static final Pattern CODE_BLOCK = Pattern.compile("```[\\s\\S]*?```");

    // 分隔符优先级：双换行 > 中文句号 > 中文感叹号 > 中文问号 > 单换行
    private static final List<String> SEPARATORS = List.of("\n\n", "。", "！", "？", "\n");

    private final int chunkSize;
    private final int overlap;
    private final int minChunkSize;

    public TextSplitter(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.minChunkSize = Math.max(chunkSize / 4, 100);
    }

    public TextSplitter() {
        this(800, 150);
    }

    public List<String> split(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return result;

        List<Heading> headings = extractHeadings(text);

        Map<String, String> placeholders = new HashMap<>();
        text = protectCodeBlocks(text, placeholders);

        List<String> rawChunks = new ArrayList<>();
        splitRecursive(text, rawChunks);

        for (String chunk : rawChunks) {
            String restored = restorePlaceholders(chunk, placeholders);
            String heading = findNearestHeading(headings, text, chunk);
            if (heading != null && !heading.isEmpty()) {
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
            String candidate = current.length() > 0 ? current + sep + part : part;
            if (candidate.length() > chunkSize && current.length() >= minChunkSize) {
                chunks.add(current.toString().trim());
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
            chunks.add(text.substring(start, end).trim());
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
