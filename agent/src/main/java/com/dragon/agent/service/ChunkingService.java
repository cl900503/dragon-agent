package com.dragon.agent.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 文档分块服务——使用 Spring AI TokenTextSplitter 按 token 数切分文档。
 *
 * 参数通过 application.yaml 的 app.rag.chunk-size 和 chunk-overlap 配置。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
@Service
public class ChunkingService {

    private static final Logger log = LoggerFactory.getLogger(ChunkingService.class);

    private final int chunkSize;

    public ChunkingService(@Value("${app.rag.chunk-size:512}") int chunkSize) {
        this.chunkSize = chunkSize;
    }

    /**
     * 对文档列表进行 Token 分块。
     *
     * 每个分块继承原文档元数据并附加 chunkIndex 和 totalChunks 标记。
     *
     * @param documents 原始文档列表
     * @return 分块后的文档列表
     */
    public List<Document> chunk(List<Document> documents) {
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkSizeChars(50)
                .build();

        List<Document> chunks = new ArrayList<>();
        for (Document doc : documents) {
            List<Document> docChunks = splitter.split(doc);
            for (int i = 0; i < docChunks.size(); i++) {
                Document chunk = docChunks.get(i);
                chunk.getMetadata().put("chunkIndex", String.valueOf(i));
                chunk.getMetadata().put("totalChunks", String.valueOf(docChunks.size()));
            }
            chunks.addAll(docChunks);
        }

        log.debug("Chunked {} documents into {} chunks (size={})",
                documents.size(), chunks.size(), chunkSize);
        return chunks;
    }
}
