package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通用 JSON 预处理器：将 JSON 中的结构化记录展平为自包含文本块，
 * 确保同一条记录的所有字段始终在一起，不被 TextSplitter 拆分。
 *
 * <h3>处理规则</h3>
 * <ul>
 *   <li>根为 {@code [...]} 且元素是对象 → 每个对象一个块</li>
 *   <li>根为 {@code {...}} 且某字段是对象数组 → 展开该数组，每个对象一个块</li>
 *   <li>根为 {@code {...}} 且无对象数组字段 → 整个对象展平为一个块</li>
 *   <li>非 JSON 文本 → 原样返回</li>
 * </ul>
 *
 * <p>嵌套对象用 {@code parent.child} 键路径展平，嵌套对象数组递归展开。
 */
@Slf4j
@Component
public class JsonPreprocessor {

    private static final int MAX_DEPTH = 3;

    public String preprocess(String raw) {
        if (raw == null || raw.isEmpty()) return raw;

        JsonElement root;
        try {
            root = JsonParser.parseString(raw.trim());
        } catch (Exception e) {
            return raw;
        }

        List<JsonObject> records = extractRecords(root);
        if (records.isEmpty()) {
            // 没有可展开的对象记录，原样返回
            return raw;
        }

        log.info("JSON 预处理：从 {} 中提取 {} 条记录，展平为独立文本块", raw.length(), records.size());
        List<String> blocks = new ArrayList<>();
        for (JsonObject rec : records) {
            blocks.add(flatten(rec, 0));
        }
        return String.join("\n\n", blocks);
    }

    /**
     * 从 JSON 根节点中提取"记录"列表。
     */
    private List<JsonObject> extractRecords(JsonElement root) {
        List<JsonObject> records = new ArrayList<>();

        if (root.isJsonArray()) {
            // 数组 → 每个对象元素是一条记录
            JsonArray arr = root.getAsJsonArray();
            for (JsonElement el : arr) {
                if (el.isJsonObject()) {
                    records.add(el.getAsJsonObject());
                }
            }
            if (!records.isEmpty()) return records;

            // 全是基本类型 → 构造一条虚拟记录
            JsonObject wrapper = new JsonObject();
            wrapper.add("items", arr);
            records.add(wrapper);
            return records;
        }

        if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            // 查找字段中最大的对象数组作为记录源
            String bestKey = null;
            int bestSize = 0;
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                if (entry.getValue().isJsonArray()) {
                    JsonArray arr = entry.getValue().getAsJsonArray();
                    int objCount = 0;
                    for (JsonElement el : arr) {
                        if (el.isJsonObject()) objCount++;
                    }
                    if (objCount > bestSize) {
                        bestSize = objCount;
                        bestKey = entry.getKey();
                    }
                }
            }
            if (bestKey != null) {
                JsonArray arr = obj.getAsJsonArray(bestKey);
                for (JsonElement el : arr) {
                    if (el.isJsonObject()) {
                        records.add(el.getAsJsonObject());
                    }
                }
                return records;
            }

            // 无对象数组字段 → 整个对象作为一条记录
            records.add(obj);
            return records;
        }

        return records;
    }

    /**
     * 递归展平一个 JSON 对象为 key: value 行。
     */
    private String flatten(JsonObject obj, int depth) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
            String key = entry.getKey();
            JsonElement val = entry.getValue();
            if (val.isJsonNull()) continue;
            appendValue(sb, key, val, depth);
        }
        return sb.toString().stripTrailing();
    }

    private void appendValue(StringBuilder sb, String key, JsonElement val, int depth) {
        if (val.isJsonPrimitive()) {
            JsonPrimitive p = val.getAsJsonPrimitive();
            if (p.isString()) {
                String s = p.getAsString();
                if (s.isEmpty()) return;
                sb.append(key).append(": ").append(s.replace("\n", "\n  ")).append("\n");
            } else if (p.isNumber()) {
                sb.append(key).append(": ").append(formatNumber(p.getAsDouble())).append("\n");
            } else {
                sb.append(key).append(": ").append(p.getAsString()).append("\n");
            }
        } else if (val.isJsonArray()) {
            JsonArray arr = val.getAsJsonArray();
            if (arr.isEmpty()) return;
            if (depth >= MAX_DEPTH) {
                sb.append(key).append(": [...]\n");
                return;
            }
            // 对象数组 → 每个元素独立展开
            if (isObjectArray(arr)) {
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject item = arr.get(i).getAsJsonObject();
                    String child = flatten(item, depth + 1);
                    if (!child.isEmpty()) {
                        sb.append(key).append("[").append(i).append("]:\n");
                        for (String line : child.split("\n")) {
                            sb.append("  ").append(line).append("\n");
                        }
                    }
                }
            } else {
                // 基本类型数组 → 逗号拼接
                List<String> items = new ArrayList<>();
                for (JsonElement el : arr) {
                    if (el.isJsonPrimitive()) {
                        items.add(el.getAsString());
                    }
                }
                if (!items.isEmpty()) {
                    sb.append(key).append(": ").append(String.join(", ", items)).append("\n");
                }
            }
        } else if (val.isJsonObject()) {
            if (depth >= MAX_DEPTH) {
                sb.append(key).append(": {...}\n");
                return;
            }
            String child = flatten(val.getAsJsonObject(), depth + 1);
            if (!child.isEmpty()) {
                for (String line : child.split("\n")) {
                    sb.append(key).append(".").append(line).append("\n");
                }
            }
        }
    }

    private boolean isObjectArray(JsonArray arr) {
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) return false;
        }
        return true;
    }

    private String formatNumber(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }
}
