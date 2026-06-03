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
 * @author 陈龙
 * @since 2026-06-01
 */
@Service
public class ChunkingService {

    private static final Logger log = LoggerFactory.getLogger(ChunkingService.class);

    @Value("${app.rag.chunk-size:512}")
    private int chunkSize;

    public List<Document> chunk(List<Document> documents) {
        TokenTextSplitter splitter = TokenTextSplitter.builder().withChunkSize(chunkSize).withMinChunkSizeChars(50)
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

        log.debug("Chunked {} documents into {} chunks (size={})", documents.size(), chunks.size(), chunkSize);
        return chunks;
    }
}
