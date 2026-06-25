package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文本清洗器：在 Tika 解析后、TextSplitter 之前执行。
 * <p>
 * 处理控制字符、多余空白、无意义行等常见文档导入脏数据。
 */
@Component
public class TextCleaner {

    /** 控制字符正则（保留 \n \r \t），用于替换为空格 */
    private static final Pattern CONTROL_CHAR = CONTROL_CHAR_PATTERN;

    /** 连续空白（空格/制表符） */
    private static final Pattern MULTI_SPACE = MULTI_SPACE_PATTERN;

    /** 3 个及以上连续换行 */
    private static final Pattern MULTI_NEWLINE = MULTI_NEWLINE_PATTERN;

    /** 纯标点/数字/空白行（\\p{P} 已覆盖中英文标点，\\p{Z} 覆盖全角空格） */
    private static final Pattern MEANINGLESS_LINE = MEANINGLESS_LINE_PATTERN;

    /** 最短有效行长度 */
    private static final int MIN_LINE_LENGTH = 3;

    public String clean(String raw) {
        if (raw == null || raw.isEmpty()) return raw;

        // 1. 控制字符 → 空格
        String text = CONTROL_CHAR.matcher(raw).replaceAll(" ");

        // 2. 连续空格/制表符 → 单个空格
        text = MULTI_SPACE.matcher(text).replaceAll(" ");

        // 3. 3+ 换行 → 2 个换行（保留段落分隔，去掉大片空白）
        text = MULTI_NEWLINE.matcher(text).replaceAll("\n\n");

        // 4. 逐行过滤无意义行
        String[] lines = text.split("\n");
        List<String> kept = new ArrayList<>();
        String prevLine = null;
        for (String line : lines) {
            String trimmed = line.trim();
            // 去除行首尾空白
            if (trimmed.isEmpty()) {
                kept.add("");
                continue;
            }
            // 过滤过短行
            if (trimmed.length() < MIN_LINE_LENGTH) continue;
            // 过滤纯标点/数字行
            if (MEANINGLESS_LINE.matcher(trimmed).matches()) continue;
            // 合并连续完全相同的行
            if (prevLine != null && prevLine.equals(trimmed)) continue;
            kept.add(trimmed);
            prevLine = trimmed;
        }

        // 5. 末尾空白收尾
        String result = String.join("\n", kept);

        // 6. 最后再做一次连续换行压缩（步骤 4 可能产生新的空行聚集）
        result = MULTI_NEWLINE.matcher(result).replaceAll("\n\n");

        return result.strip();
    }

    /**
     * 过滤低质量 chunk：过短、信息密度过低。
     */
    public List<String> filterLowQualityChunks(List<String> chunks) {
        return chunks.stream()
                .filter(c -> {
                    // 长度阈值：至少 20 字符
                    int len = c.codePointCount(0, c.length());
                    if (len < MIN_CHUNK_CODE_POINTS) return false;
                    // 信息密度：有效字符（非标点非空白）/ 总长度 >= 40%
                    int meaningful = 0;
                    for (int i = 0; i < c.length(); ) {
                        int cp = c.codePointAt(i);
                        if (Character.isLetterOrDigit(cp) || Character.isIdeographic(cp)) {
                            meaningful++;
                        }
                        i += Character.charCount(cp);
                    }
                    return (double) meaningful / len >= 0.4;
                })
                .collect(Collectors.toList());
    }
}
