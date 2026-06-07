package com.dragon.agent.service.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 语义感知分块器——根据文档类型和结构自适应切分。
 *
 * <p>三层策略：
 * <ol>
 *   <li><b>结构感知</b>：按 Markdown 标题、PDF 段落边界等文档结构做语义边界检测</li>
 *   <li><b>自适应大小</b>：根据文档类型（论文/FAQ/合同）和文档长度动态调整 chunk size</li>
 *   <li><b>段落完整性</b>：以段落为最小单位，确保 chunk 不切断语义完整的段落</li>
 * </ol>
 *
 * <p>文档类型 → 推荐 chunk size：
 * <ul>
 *   <li>Markdown/技术文档：512 token</li>
 *   <li>PDF 长文（>50000 字符）：1024 token</li>
 *   <li>PDF 短文/合同：512 token</li>
 *   <li>纯文本/FAQ：256 token</li>
 *   <li>代码文件：512 token</li>
 * </ul>
 *
 * @author 陈龙
 * @since 2026-06-07
 */
@Service
public class SemanticChunker {

    private static final Logger log = LoggerFactory.getLogger(SemanticChunker.class);

    /** Markdown 标题分割正则 */
    private static final Pattern MD_HEADING = Pattern.compile("(?=\\n#{1,6}\\s)");

    /** 段落边界——连续双换行 */
    private static final Pattern PARAGRAPH_BOUNDARY = Pattern.compile("\\n\\s*\\n");

    /** 句子边界——句号、问号、感叹号后跟换行 */
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。！？.!?])\\s*\\n");

    /** 长段落切分——在 256 字符以上且遇到逗号/分号等自然断点 */
    private static final Pattern SOFT_BOUNDARY = Pattern.compile("(?<=[，,；;：:])\\s*");

    @Value("${app.rag.semantic-chunking:true}")
    private boolean semanticEnabled;

    /**
     * 语义分块——根据文档 MIME 类型选择最佳分块策略。
     *
     * @param content  文档原文
     * @param mimeType MIME 类型
     * @param fileName 文件名（辅助判断文档类型）
     * @return 语义分块列表
     */
    public List<Document> chunk(String content, String mimeType, String fileName) {
        if (!semanticEnabled || content == null || content.isBlank()) {
            return List.of();
        }

        int adaptiveSize = adaptiveChunkSize(mimeType, fileName, content.length());

        List<String> paragraphs;
        if (mimeType != null && mimeType.contains("markdown")) {
            paragraphs = splitMarkdown(content);
        } else {
            paragraphs = splitByParagraph(content);
        }

        // 合并段落直到接近 adaptiveSize
        List<Document> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        int currentTokens = 0;

        for (String para : paragraphs) {
            int paraTokens = estimateTokens(para);

            if (currentTokens + paraTokens > adaptiveSize && currentTokens > 0) {
                // 当前块已满，保存并开始新块
                chunks.add(createDocument(currentChunk.toString(), chunks.size()));
                currentChunk = new StringBuilder();
                currentTokens = 0;
            }

            if (paraTokens > adaptiveSize) {
                // 超级长的段落——在句子边界切分
                if (!currentChunk.isEmpty()) {
                    chunks.add(createDocument(currentChunk.toString(), chunks.size()));
                    currentChunk = new StringBuilder();
                    currentTokens = 0;
                }
                for (String subChunk : splitLongParagraph(para, adaptiveSize)) {
                    chunks.add(createDocument(subChunk, chunks.size()));
                }
            } else {
                if (!currentChunk.isEmpty()) currentChunk.append("\n\n");
                currentChunk.append(para);
                currentTokens += paraTokens;
            }
        }

        // 最后一个块
        if (!currentChunk.isEmpty()) {
            chunks.add(createDocument(currentChunk.toString(), chunks.size()));
        }

        log.debug("Semantic chunking: {} chars, {} paras → {} chunks (size={}, type={})",
                content.length(), paragraphs.size(), chunks.size(), adaptiveSize, mimeType);
        return chunks;
    }

    // ==================== 分割策略 ====================

    /**
     * Markdown 按标题层级分割。
     */
    List<String> splitMarkdown(String content) {
        String[] sections = MD_HEADING.split(content);
        List<String> result = new ArrayList<>();
        for (String section : sections) {
            String trimmed = section.trim();
            if (!trimmed.isEmpty()) {
                // 如果 section 很大，按段落再切分
                if (trimmed.length() > 2000) {
                    result.addAll(splitByParagraph(trimmed));
                } else {
                    result.add(trimmed);
                }
            }
        }
        return result.isEmpty() ? List.of(content) : result;
    }

    /**
     * 通用段落分割——按连续双换行边界。
     */
    List<String> splitByParagraph(String content) {
        List<String> result = new ArrayList<>();
        for (String para : PARAGRAPH_BOUNDARY.split(content)) {
            String trimmed = para.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result.isEmpty() ? List.of(content) : result;
    }

    /**
     * 超长段落在句子边界处切分。
     */
    List<String> splitLongParagraph(String paragraph, int maxTokens) {
        List<String> result = new ArrayList<>();
        String[] sentences = SENTENCE_BOUNDARY.split(paragraph);
        StringBuilder chunk = new StringBuilder();
        int tokens = 0;

        for (String sent : sentences) {
            int sentTokens = estimateTokens(sent);
            if (tokens + sentTokens > maxTokens && tokens > 0) {
                result.add(chunk.toString().trim());
                chunk = new StringBuilder();
                tokens = 0;
            }
            if (!chunk.isEmpty()) chunk.append(" ");
            chunk.append(sent.trim());
            tokens += sentTokens;
        }

        if (!chunk.isEmpty()) {
            result.add(chunk.toString().trim());
        }
        return result.isEmpty() ? List.of(paragraph) : result;
    }

    // ==================== 自适应大小 ====================

    /**
     * 根据文档类型和长度自适应确定 chunk size（token 数）。
     */
    int adaptiveChunkSize(String mimeType, String fileName, int contentLength) {
        // 默认 512
        int size = 512;

        if (mimeType == null) mimeType = "";

        if (mimeType.contains("markdown")) {
            size = 512;
        } else if (mimeType.contains("pdf") || mimeType.contains("word")) {
            size = contentLength > 50000 ? 1024 : 512;
        } else if (mimeType.contains("text/plain") || mimeType.contains("csv")) {
            size = 256;
        } else if (mimeType.contains("java") || mimeType.contains("python") || mimeType.contains("x-c")) {
            size = 512;
        }

        // 文件名微调
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            if (lower.contains("faq") || lower.contains("q&a")) size = 256;
            if (lower.contains("论文") || lower.contains("report") || lower.contains("报告")) size = 1024;
        }

        log.debug("Adaptive chunk size: {} for {} ({} chars)", size, mimeType, contentLength);
        return size;
    }

    // ==================== 辅助方法 ====================

    /** 字符数估算 token 数（中文 ~1 char/token，英文 ~3.5 char/token，取折中 ~2） */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / 2);
    }

    private Document createDocument(String text, int index) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chunkIndex", String.valueOf(index));
        return new Document(text.trim(), metadata);
    }
}
