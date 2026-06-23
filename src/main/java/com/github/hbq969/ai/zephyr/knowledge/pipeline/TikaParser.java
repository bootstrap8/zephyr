package com.github.hbq969.ai.zephyr.knowledge.pipeline;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

@Component
public class TikaParser {
    // Tika 默认 BodyContentHandler writeLimit 为 100000 字符，超过会截断。
    // 设为 -1 禁用截断，避免 JSON 等大文件尾部被切导致解析失败。
    private static final int WRITE_LIMIT = -1;

    public String parse(InputStream in) throws IOException, TikaException {
        BodyContentHandler handler = new BodyContentHandler(WRITE_LIMIT);
        try {
            new AutoDetectParser().parse(in, handler, new Metadata(), new ParseContext());
        } catch (org.xml.sax.SAXException e) {
            throw new TikaException("Tika 解析失败", e);
        }
        return handler.toString();
    }
}
