package com.dragon.agent.service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dragon.agent.dto.DocumentResponse;
import com.dragon.agent.entity.DocumentEntity;
import com.dragon.agent.entity.DocumentStatus;
import com.dragon.agent.entity.UserEntity;
import com.dragon.agent.repository.DocumentRepository;
import com.dragon.agent.repository.UserRepository;
import com.dragon.agent.service.parser.DocumentParserService;
import com.dragon.agent.service.storage.FileStorageService;

/**
 * 文档管理服务——文件上传、解析、分块、向量索引和 RAG 检索的完整生命周期管理。
 *
 * 职责：
 * - 文档上传流程：MinIO 存储 → Tika 解析 → TokenTextSplitter 分块 → Milvus 向量索引
 * - 文档列表与删除（按用户隔离）
 * - RAG 语义检索：查询向量化 → Milvus 相似度搜索 → 格式化上下文
 *
 * @author 陈龙
 * @since 2026-06-01
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    @Autowired
    private FileStorageService fileStorageService;

    @Autowired
    private DocumentParserService documentParserService;

    @Autowired
    private ChunkingService chunkingService;

    @Autowired(required = false)
    private VectorStore vectorStore;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private UserRepository userRepository;

    @Value("${app.rag.top-k:5}")
    private int topK;

    @Value("${app.rag.similarity-threshold:0.3}")
    private double similarityThreshold;

    /**
     * 上传并处理文档——存储到 MinIO、解析文本、分块、写入向量索引。
     *
     * @param fileData       文件输入流
     * @param originalName   原始文件名
     * @param fileSize       文件大小（字节）
     * @param mimeType       MIME 类型
     * @param conversationId 关联会话 ID，可为空
     * @param username       上传者用户名
     * @return 文档响应 DTO
     */
    @Transactional
    public DocumentResponse upload(InputStream fileData, String originalName, long fileSize,
                                    String mimeType, String conversationId, String username) {
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("用户不存在: " + username));
        String docId = UUID.randomUUID().toString();

        DocumentEntity entity = new DocumentEntity(docId, user.getId(), conversationId,
                originalName, "", fileSize, mimeType);
        entity.setStatus(DocumentStatus.UPLOADING);
        documentRepository.save(entity);

        try {
            String objectKey = fileStorageService.store(originalName, fileSize, mimeType, fileData);
            entity.setStoredPath(objectKey);
            entity.setStatus(DocumentStatus.PARSING);
            documentRepository.save(entity);

            InputStream storedStream = fileStorageService.read(objectKey);
            Document parsedDoc = documentParserService.parse(storedStream, originalName, mimeType);
            storedStream.close();
            entity.setStatus(DocumentStatus.INDEXING);
            documentRepository.save(entity);

            List<Document> chunks = chunkingService.chunk(List.of(parsedDoc));
            if (chunks.isEmpty()) throw new RuntimeException("文档内容为空");
            for (int i = 0; i < chunks.size(); i++) {
                Document chunk = chunks.get(i);
                chunk.getMetadata().put("documentId", docId);
                chunk.getMetadata().put("originalName", originalName);
                chunk.getMetadata().put("chunkIndex", String.valueOf(i));
                chunk.getMetadata().put("totalChunks", String.valueOf(chunks.size()));
                if (conversationId != null && !conversationId.isBlank())
                    chunk.getMetadata().put("conversationId", conversationId);
                if (user.getId() != null)
                    chunk.getMetadata().put("userId", user.getId().toString());
            }

            if (vectorStore != null) {
                try { vectorStore.add(chunks); }
                catch (Exception e) { log.warn("Vector indexing failed for [{}]: {}", originalName, e.getMessage()); }
            }

            entity.setChunkCount(chunks.size());
            entity.setStatus(DocumentStatus.READY);
            documentRepository.save(entity);
            log.info("Document [{}] indexed: {} chunks", originalName, chunks.size());
            return DocumentResponse.from(entity);
        } catch (Exception e) {
            log.error("Document processing failed [{}]: {}", originalName, e.getMessage());
            entity.setStatus(DocumentStatus.FAILED);
            entity.setErrorMessage(e.getMessage());
            documentRepository.save(entity);
            throw new RuntimeException("文档处理失败: " + e.getMessage(), e);
        }
    }

    /**
     * 列出用户的文档。
     *
     * @param username       用户名
     * @param conversationId 会话 ID，为空时列出全局文档
     * @return 文档列表
     */
    public List<DocumentResponse> listDocuments(String username, String conversationId) {
        UserEntity user = userRepository.findByUsername(username).orElse(null);
        if (user == null) return List.of();
        List<DocumentEntity> entities = (conversationId != null && !conversationId.isBlank())
                ? documentRepository.findByUserIdAndConversationIdOrderByCreatedAtDesc(user.getId(), conversationId)
                : documentRepository.findByUserIdAndConversationIdIsNullOrderByCreatedAtDesc(user.getId());
        return entities.stream().map(DocumentResponse::from).collect(Collectors.toList());
    }

    /**
     * 删除文档——清理 MinIO 文件、Milvus 向量和 MySQL 元数据。
     *
     * @param documentId 文档 ID
     * @param username   所有者用户名
     */
    @Transactional
    public void deleteDocument(String documentId, String username) {
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("用户不存在: " + username));
        DocumentEntity entity = documentRepository.findByIdAndUserId(documentId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("文档不存在或无权访问"));
        try { fileStorageService.delete(entity.getStoredPath()); } catch (Exception e) { log.warn("MinIO delete failed"); }
        if (vectorStore != null) {
            try { vectorStore.delete("documentId == '" + documentId + "'"); } catch (Exception e) { log.warn("Vector delete failed"); }
        }
        documentRepository.delete(entity);
    }

    /**
     * RAG 语义检索——将查询向量化后在 Milvus 中搜索相似文档片段。
     *
     * @param query 用户查询文本
     * @return 检索结果，包含格式化上下文和追溯数据
     */
    public RagResult retrieveContext(String query) {
        if (vectorStore == null) return RagResult.EMPTY;
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query).topK(topK).similarityThreshold(similarityThreshold).build();
            List<Document> results = vectorStore.similaritySearch(request);
            if (results.isEmpty()) return RagResult.EMPTY;
            log.debug("RAG retrieved {} chunks", results.size());
            List<Map<String, Object>> traces = buildTraces(results);
            return new RagResult(formatContext(results), traces);
        } catch (Exception e) {
            log.warn("RAG retrieval failed: {}", e.getMessage());
            return RagResult.EMPTY;
        }
    }

    /**
     * 重试处理失败的文档——重新解析、分块、索引。
     *
     * @param documentId 文档 ID
     * @param username   所有者用户名
     */
    @Transactional
    public void retryDocument(String documentId, String username) {
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("用户不存在: " + username));
        DocumentEntity entity = documentRepository.findByIdAndUserId(documentId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("文档不存在或无权访问"));
        if (entity.getStatus() != DocumentStatus.FAILED)
            throw new IllegalStateException("只能重试失败状态的文档");
        log.info("Retrying document [{}]", entity.getOriginalName());
        try {
            InputStream storedStream = fileStorageService.read(entity.getStoredPath());
            Document parsedDoc = documentParserService.parse(storedStream, entity.getOriginalName(), entity.getMimeType());
            storedStream.close();
            List<Document> chunks = chunkingService.chunk(List.of(parsedDoc));
            for (int i = 0; i < chunks.size(); i++) {
                chunks.get(i).getMetadata().put("documentId", documentId);
                chunks.get(i).getMetadata().put("originalName", entity.getOriginalName());
                chunks.get(i).getMetadata().put("chunkIndex", String.valueOf(i));
            }
            if (vectorStore != null) {
                try { vectorStore.add(chunks); } catch (Exception e) { log.warn("Retry index failed"); }
            }
            entity.setChunkCount(chunks.size());
            entity.setStatus(DocumentStatus.READY);
            entity.setErrorMessage(null);
            documentRepository.save(entity);
        } catch (Exception e) {
            entity.setErrorMessage(e.getMessage());
            documentRepository.save(entity);
            throw new RuntimeException("重试失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 MinIO 文件输入流。
     *
     * @param objectKey MinIO 对象 Key
     * @return 文件输入流
     */
    public InputStream getFileStream(String objectKey) { return fileStorageService.read(objectKey); }

    /**
     * 获取文档实体并校验所有权。
     *
     * @param documentId 文档 ID
     * @param username   所有者用户名
     * @return 文档实体
     */
    public DocumentEntity getDocumentEntity(String documentId, String username) {
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("用户不存在: " + username));
        return documentRepository.findByIdAndUserId(documentId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("文档不存在或无权访问"));
    }

    private List<Map<String, Object>> buildTraces(List<Document> documents) {
        List<Map<String, Object>> traces = new ArrayList<>();
        for (Document doc : documents) {
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("documentName", doc.getMetadata().getOrDefault("originalName", "未知文档"));
            trace.put("chunkIndex", Integer.parseInt((String) doc.getMetadata().getOrDefault("chunkIndex", "0")));
            trace.put("score", doc.getScore() != null ? doc.getScore() : 0.0);
            String text = doc.getText();
            trace.put("contentSnippet", text.length() > 200 ? text.substring(0, 200) + "..." : text);
            traces.add(trace);
        }
        return traces;
    }

    private String formatContext(List<Document> documents) {
        StringBuilder sb = new StringBuilder();
        for (Document doc : documents) {
            String name = (String) doc.getMetadata().getOrDefault("originalName", "未知");
            sb.append("📄 %s\n%s\n\n".formatted(name, doc.getText()));
        }
        return sb.toString();
    }

    /** RAG 检索结果封装 */
    public record RagResult(String context, List<Map<String, Object>> traces) {
        public static final RagResult EMPTY = new RagResult("", List.of());
        public boolean isEmpty() { return context.isEmpty(); }
    }
}
