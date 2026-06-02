package com.dragon.agent.service.parser;

import java.io.InputStream;
import java.util.Map;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * 文档解析服务——使用 Apache Tika 提取文件文本内容。
 *
 * 支持 PDF、Word、Excel、PPT、TXT、Markdown 等 1000+ 文件格式。
 * 解析结果返回为 Spring AI Document 对象，附带文件名和 MIME 类型元数据。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
@Service
public class DocumentParserService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserService.class);

    /**
     * 解析文件内容为 Spring AI Document。
     *
     * @param inputStream 文件输入流
     * @param fileName    原始文件名（用于元数据记录）
     * @param mimeType    MIME 类型
     * @return 包含文本内容和元数据的 Document
     */
    public Document parse(InputStream inputStream, String fileName, String mimeType) {
        try {
            BodyContentHandler handler = new BodyContentHandler(-1); // 无长度限制
            Metadata metadata = new Metadata();
            metadata.set("resourceName", fileName);
            if (mimeType != null) {
                metadata.set(Metadata.CONTENT_TYPE, mimeType);
            }

            AutoDetectParser parser = new AutoDetectParser();
            ParseContext context = new ParseContext();
            parser.parse(inputStream, handler, metadata, context);

            String content = handler.toString().trim();
            log.info("Parsed file [{}]: {} characters extracted", fileName, content.length());

            return new Document(content,
                    Map.of(
                            "source", fileName,
                            "mimeType", mimeType != null ? mimeType : "unknown",
                            "charCount", String.valueOf(content.length())
                    ));
        } catch (Exception e) {
            log.error("Failed to parse file [{}]: {}", fileName, e.getMessage(), e);
            throw new RuntimeException("文档解析失败: " + fileName + " - " + e.getMessage(), e);
        }
    }
}
