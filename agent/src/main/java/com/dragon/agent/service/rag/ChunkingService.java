package com.dragon.agent.service.rag;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 文档分块服务——使用滑动窗口实现有重叠的分块策略。
 *
 * <p>重叠窗口确保关键信息不会因分块边界而被切断，提高跨块信息的检索完整度。
 *
 * <p>双阶段分块：
 * <ol>
 *   <li>第一阶段用 TokenTextSplitter 做 token 级粗切分</li>
 *   <li>第二阶段对相邻块按 overlap 参数添加重叠文本</li>
 * </ol>
 *
 * @author 陈龙
 * @since 2026-06-01
 */
@Service
public class ChunkingService {

    private static final Logger log = LoggerFactory.getLogger(ChunkingService.class);

    @Value("${app.rag.chunk-size:512}")
    private int defaultChunkSize;

    @Value("${app.rag.chunk-overlap:50}")
    private int defaultChunkOverlap;

    /**
     * 使用默认 chunkSize 和 chunkOverlap 进行分块。
     */
    public List<Document> chunk(List<Document> documents) {
        return chunk(documents, defaultChunkSize, defaultChunkOverlap);
    }

    /**
     * 使用指定参数进行分块。
     *
     * <p>分块策略：
     * <ol>
     *   <li>首先用 TokenTextSplitter 将文档按 chunkSize 切分为初始块</li>
     *   <li>然后对相邻块边界应用 overlap，每个块（除首块外）的开头包含前一个块的尾部文本作为重叠</li>
     * </ol>
     *
     * @param documents    源文档列表
     * @param chunkSize    目标块大小（token 数）
     * @param chunkOverlap 相邻块重叠 token 数（>=0，为 0 时无重叠）
     * @return 带重叠的分块列表，每个块含 chunkIndex 和 totalChunks 元数据
     */
    public List<Document> chunk(List<Document> documents, int chunkSize, int chunkOverlap) {
        // 第一阶段：基础 token 级切分（无重叠）
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkSizeChars(50)
                .build();

        List<Document> rawChunks = new ArrayList<>();
        for (Document doc : documents) {
            List<Document> docChunks = splitter.split(doc);
            for (int i = 0; i < docChunks.size(); i++) {
                Document chunk = docChunks.get(i);
                chunk.getMetadata().putAll(doc.getMetadata());
                chunk.getMetadata().put("chunkIndex", String.valueOf(i));
                chunk.getMetadata().put("totalChunks", String.valueOf(docChunks.size()));
            }
            rawChunks.addAll(docChunks);
        }

        // 第二阶段：对相邻块应用重叠——向非首块的开头追加前一块尾部文本
        if (chunkOverlap <= 0 || rawChunks.size() <= 1) {
            log.debug("Chunked {} documents into {} chunks (size={}, overlap=0)", documents.size(), rawChunks.size(),
                    chunkSize);
            return rawChunks;
        }

        List<Document> overlapped = new ArrayList<>();
        overlapped.add(rawChunks.get(0)); // 首块不变

        for (int i = 1; i < rawChunks.size(); i++) {
            Document prev = rawChunks.get(i - 1);
            Document curr = rawChunks.get(i);

            String prevText = prev.getText();
            int overlapChars = estimateOverlapChars(prevText, chunkSize, chunkOverlap);
            if (overlapChars > 0 && !prevText.isEmpty()) {
                String overlapText = prevText.substring(Math.max(0, prevText.length() - overlapChars));
                Document merged = new Document(overlapText + "\n" + curr.getText(), curr.getMetadata());
                overlapped.add(merged);
            } else {
                overlapped.add(curr);
            }
        }

        // 更新 totalChunks 元数据
        for (int i = 0; i < overlapped.size(); i++) {
            overlapped.get(i).getMetadata().put("totalChunks", String.valueOf(overlapped.size()));
        }

        log.debug("Chunked {} documents into {} chunks (size={}, overlap={})", documents.size(), overlapped.size(),
                chunkSize, chunkOverlap);
        return overlapped;
    }

    /**
     * 按比例估算要取的前块尾部字符数。
     *
     * <p>近似公式：overlapChars = ceil(prevText.length() * chunkOverlap / chunkSize)
     * 限制不超过 prevText 长度的 50%，防止小文本被过度重叠。</p>
     */
    static int estimateOverlapChars(String prevText, int chunkSize, int chunkOverlap) {
        if (prevText == null || prevText.isEmpty() || chunkSize <= 0 || chunkOverlap <= 0) {
            return 0;
        }
        int overlapChars = (int) Math.ceil(prevText.length() * (double) chunkOverlap / chunkSize);
        int maxOverlap = prevText.length() / 2;
        return Math.min(overlapChars, maxOverlap);
    }
}
