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
import com.dragon.agent.service.rag.BgeM3Client;
import com.dragon.agent.service.rag.ChunkingService;
import com.dragon.agent.service.rag.HybridSearchService;
import com.dragon.agent.service.rag.RagSearchService;
import com.dragon.agent.service.rag.RerankService;
import com.dragon.agent.service.storage.FileStorageService;

/**
 * 文档管理服务——文件上传、解析、分块、向量索引和 RAG 检索的完整生命周期管理。
 *
 * 权限模型（2026-06-03 重构）：
 * <ul>
 *   <li>文档删除：上传者本人 / KB 管理者（ADMIN、DEPT_ADMIN 同部门、KB owner）</li>
 *   <li>文档上传：所有能访问 KB 的用户均可上传</li>
 *   <li>RAG 检索：按用户有权限的 KB 过滤，非 KB 文档仅限本人</li>
 * </ul>
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
    private HybridSearchService hybridSearch;

    @Autowired
    private BgeM3Client bgeM3;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private com.dragon.agent.repository.KnowledgeBaseRepository kbRepository;

    @Autowired
    private com.dragon.agent.repository.RagSearchLogRepository searchLogRepository;

    @Autowired
    private RerankService rerankService;

    @Autowired
    private RagSearchService ragSearchService;

    @Autowired(required = false)
    private com.dragon.agent.service.rag.SemanticChunker semanticChunker;

    @Autowired(required = false)
    private com.dragon.agent.service.rag.QueryCacheService cacheService;

    @Value("${app.rag.chunk-size:512}")
    private int defaultChunkSize;

    @Value("${app.rag.chunk-overlap:50}")
    private int defaultChunkOverlap;

    @Value("${app.rag.top-k:5}")
    private int topK;

    @Value("${app.rag.similarity-threshold:0.3}")
    private double similarityThreshold;

    // ==================== 上传 ====================

    /** 上传并处理文档。若指定 kbId，需校验用户对该 KB 的访问权限。 */
    @Transactional
    public DocumentResponse upload(InputStream fileData, String originalName, long fileSize, String mimeType,
            String kbId, String username) {
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("用户不存在: " + username));

        if (kbId == null || kbId.isBlank()) {
            throw new IllegalArgumentException("请先选择知识库");
        }
        if (!knowledgeBaseService.canWrite(kbId, username)) {
            throw new IllegalArgumentException("无权向此知识库上传文档");
        }

        String docId = UUID.randomUUID().toString();
        DocumentEntity entity = new DocumentEntity(docId, user.getId(), originalName, "", fileSize, mimeType);
        entity.setKbId(kbId);
        entity.setStatus(DocumentStatus.UPLOADING);
        documentRepository.save(entity);

        try {
            String objectKey = fileStorageService.store(originalName, fileSize, mimeType, fileData);
            entity.setStoredPath(objectKey);
            entity.setStatus(DocumentStatus.PARSING);
            documentRepository.save(entity);

            Document parsedDoc;
            try (InputStream storedStream = fileStorageService.read(objectKey)) {
                parsedDoc = documentParserService.parse(storedStream, originalName, mimeType);
            }
            entity.setStatus(DocumentStatus.INDEXING);
            documentRepository.save(entity);

            // 语义分块预处理：若有 SemanticChunker，先按文档结构切分为段落组，再做 token 级分块
            List<Document> chunkSources;
            if (semanticChunker != null) {
                List<Document> semanticChunks = semanticChunker.chunk(parsedDoc.getText(), mimeType, originalName);
                if (!semanticChunks.isEmpty()) {
                    chunkSources = semanticChunks;
                } else {
                    chunkSources = List.of(parsedDoc);
                }
            } else {
                chunkSources = List.of(parsedDoc);
            }

            // 滑动窗口分块：TokenTextSplitter + Chunk Overlap
            List<Document> chunks = chunkingService.chunk(chunkSources, defaultChunkSize, defaultChunkOverlap);
            if (chunks.isEmpty()) throw new RuntimeException("文档内容为空");

            for (int i = 0; i < chunks.size(); i++) {
                Document chunk = chunks.get(i);
                chunk.getMetadata().put("documentId", docId);
                chunk.getMetadata().put("originalName", originalName);
                chunk.getMetadata().put("chunkIndex", String.valueOf(i));
                chunk.getMetadata().put("totalChunks", String.valueOf(chunks.size()));
                if (user.getId() != null)
                    chunk.getMetadata().put("userId", user.getId().toString());
                if (kbId != null && !kbId.isBlank())
                    chunk.getMetadata().put("kbId", kbId);
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                var emb = bgeM3.embed(chunks.get(i).getText());
                if (emb == null) throw new RuntimeException("Embedding failed");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("dense_vector", emb.get("dense"));
                row.put("sparse_vector", emb.get("sparse"));
                row.put("content", chunks.get(i).getText());
                row.put("documentId", docId);
                row.put("originalName", originalName);
                row.put("chunkIndex", String.valueOf(i));
                row.put("userId", user.getId().toString());
                if (kbId != null && !kbId.isBlank()) row.put("kbId", kbId);
                rows.add(row);
            }
            try {
                hybridSearch.insert(rows);
            } catch (Exception e) { log.warn("Hybrid insert failed: {}", e.getMessage()); }
            int chunkCount = chunks.size();

            entity.setChunkCount(chunkCount);
            entity.setStatus(DocumentStatus.READY);
            documentRepository.save(entity);
            log.info("Document [{}] indexed: {} chunks (kb={})", originalName, chunkCount, kbId);
            // 文档变更后使缓存失效，避免返回过时的检索结果
            invalidateCacheIfNeeded();
            return enrichSingle(entity, username);
        } catch (Exception e) {
            log.error("Document processing failed [{}]: {}", originalName, e.getMessage());
            if (entity.getStoredPath() != null && !entity.getStoredPath().isBlank()) {
                try { fileStorageService.delete(entity.getStoredPath()); } catch (Exception ignored) {}
            }
            entity.setStatus(DocumentStatus.FAILED);
            entity.setErrorMessage(e.getMessage());
            documentRepository.save(entity);
            throw new RuntimeException("文档处理失败: " + e.getMessage(), e);
        }
    }

    // ==================== 列表 ====================

    /** 列出用户的所有文档 */
    public List<DocumentResponse> listDocuments(String username) {
        UserEntity user = userRepository.findByUsername(username).orElse(null);
        if (user == null) return List.of();
        return enrichBatch(documentRepository.findByUserIdOrderByCreatedAtDesc(user.getId()), user);
    }

    /** 列出知识库下的文档（需有访问权限） */
    public List<DocumentResponse> listDocumentsByKb(String kbId, String username) {
        if (!knowledgeBaseService.canAccessKb(kbId, username))
            throw new IllegalArgumentException("无权访问此知识库");
        UserEntity user = userRepository.findByUsername(username).orElse(null);
        if (user == null) return List.of();
        return enrichBatch(documentRepository.findByKbIdOrderByCreatedAtDesc(kbId), user);
    }

    /**
     * 批量富化文档响应——一次性加载 KB 名称和上传者名称，同时计算当前用户能否删除每个文档。
     */
    /** 单文档富化——上传成功后返回完整属性 */
    private DocumentResponse enrichSingle(DocumentEntity doc, String username) {
        String kbName = doc.getKbId() != null ? kbRepository.findById(doc.getKbId()).map(kb -> kb.getName()).orElse(null) : null;
        String uploaderName = userRepository.findById(doc.getUserId()).map(UserEntity::getUsername).orElse(null);
        UserEntity currentUser = userRepository.findByUsername(username).orElse(null);
        boolean canDelete = currentUser != null && doc.getUserId().equals(currentUser.getId());
        if (!canDelete && doc.getKbId() != null && currentUser != null) {
            var kb = kbRepository.findById(doc.getKbId()).orElse(null);
            canDelete = kb != null && knowledgeBaseService.canManage(kb, currentUser);
        }
        return DocumentResponse.enriched(doc, kbName, uploaderName, canDelete);
    }

    private List<DocumentResponse> enrichBatch(List<DocumentEntity> docs, UserEntity currentUser) {
        if (docs.isEmpty()) return List.of();

        var kbIds = docs.stream().map(DocumentEntity::getKbId).filter(id -> id != null).collect(Collectors.toSet());
        var userIds = docs.stream().map(DocumentEntity::getUserId).collect(Collectors.toSet());

        Map<String, String> kbNames = kbIds.isEmpty() ? Map.of()
                : kbRepository.findAllById(kbIds).stream()
                        .collect(Collectors.toMap(kb -> kb.getId(), kb -> kb.getName()));
        Map<Long, String> userNames = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, UserEntity::getUsername));
        // 预加载 KB 管理权限
        Map<String, Boolean> kbManageCache = kbIds.stream()
                .collect(Collectors.toMap(id -> id, id -> {
                    var kb = kbRepository.findById(id).orElse(null);
                    return kb != null && knowledgeBaseService.canManage(kb, currentUser);
                }));

        return docs.stream()
                .map(doc -> {
                    boolean canDelete = doc.getUserId().equals(currentUser.getId())
                            || (doc.getKbId() != null && kbManageCache.getOrDefault(doc.getKbId(), false));
                    return DocumentResponse.enriched(doc,
                            kbNames.get(doc.getKbId()),
                            userNames.get(doc.getUserId()),
                            canDelete);
                })
                .collect(Collectors.toList());
    }

    // ==================== 删除 ====================

    /**
     * 删除文档。
     * 权限：上传者本人 或 KB 管理者（ADMIN / DEPT_ADMIN 同部门 / KB owner）。
     */
    @Transactional
    public void deleteDocument(String documentId, String username) {
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("用户不存在: " + username));

        DocumentEntity entity = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在"));

        if (!canDelete(entity, user, username)) {
            throw new IllegalArgumentException("无权删除此文档");
        }

        doDelete(entity);
    }

    /** 判断用户能否删除文档 */
    private boolean canDelete(DocumentEntity entity, UserEntity user, String username) {
        // 本人上传
        if (entity.getUserId().equals(user.getId())) return true;
        // KB 管理者
        if (entity.getKbId() != null && !entity.getKbId().isBlank()
                && knowledgeBaseService.isOwnerOrEquivalent(entity.getKbId(), username)) return true;
        return false;
    }

    private void doDelete(DocumentEntity entity) {
        try { fileStorageService.delete(entity.getStoredPath()); } catch (Exception e) {
            log.warn("MinIO delete failed: {}", entity.getStoredPath());
        }
        if (vectorStore != null) {
            try { hybridSearch.deleteByExpr("documentId == '" + entity.getId() + "'"); } catch (Exception e) {
                log.warn("Vector delete failed: {}", entity.getId());
            }
        }
        documentRepository.delete(entity);
        invalidateCacheIfNeeded();
    }

    // ==================== 重试 ====================

    /** 重试失败文档，权限同删除 */
    @Transactional
    public void retryDocument(String documentId, String username) {
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("用户不存在: " + username));
        DocumentEntity entity = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在"));

        if (!canDelete(entity, user, username)) {
            throw new IllegalArgumentException("无权操作此文档");
        }
        if (entity.getStatus() != DocumentStatus.FAILED)
            throw new IllegalStateException("只能重试失败状态的文档");

        log.info("Retrying document [{}]", entity.getOriginalName());
        try (InputStream storedStream = fileStorageService.read(entity.getStoredPath())) {
            Document parsedDoc = documentParserService.parse(storedStream, entity.getOriginalName(), entity.getMimeType());
            List<Document> chunks = chunkingService.chunk(List.of(parsedDoc), defaultChunkSize, defaultChunkOverlap);
            for (int i = 0; i < chunks.size(); i++) {
                chunks.get(i).getMetadata().put("documentId", documentId);
                chunks.get(i).getMetadata().put("originalName", entity.getOriginalName());
                chunks.get(i).getMetadata().put("chunkIndex", String.valueOf(i));
                chunks.get(i).getMetadata().put("userId", entity.getUserId().toString());
                if (entity.getKbId() != null) chunks.get(i).getMetadata().put("kbId", entity.getKbId());
            }
            if (vectorStore != null) {
                try { hybridSearch.deleteByExpr("documentId == '" + documentId + "'"); } catch (Exception e) {
                    log.warn("Retry cleanup old vectors failed: {}", e.getMessage());
                }
                try { vectorStore.add(chunks); } catch (Exception e) { log.warn("Retry index failed"); }
            }
            entity.setChunkCount(chunks.size());
            entity.setStatus(DocumentStatus.READY);
            entity.setErrorMessage(null);
            documentRepository.save(entity);
            invalidateCacheIfNeeded();
        } catch (Exception e) {
            entity.setErrorMessage(e.getMessage());
            documentRepository.save(entity);
            throw new RuntimeException("重试失败: " + e.getMessage(), e);
        }
    }

    // ==================== RAG 检索 ====================

    /**
     * RAG 语义检索——委托给 {@link com.dragon.agent.service.rag.RagSearchService}。
     */
    public RagSearchResult retrieveContext(String query, Long userId) {
        var result = ragSearchService.retrieveContext(query, userId);
        return new RagSearchResult(result.context(), result.traces());
    }

    // ==================== 文件操作 ====================

    public InputStream getFileStream(String objectKey) {
        return fileStorageService.read(objectKey);
    }

    public Long getUserId(String username) {
        return userRepository.findByUsername(username).map(UserEntity::getId).orElse(null);
    }

    /** 获取文档实体并校验访问权限 */
    public DocumentEntity getDocumentEntity(String documentId, String username) {
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("用户不存在: " + username));
        DocumentEntity entity = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在"));

        if (entity.getUserId().equals(user.getId())) return entity;
        if (entity.getKbId() != null && !entity.getKbId().isBlank()
                && knowledgeBaseService.canAccessKb(entity.getKbId(), username)) return entity;

        throw new IllegalArgumentException("文档不存在或无权访问");
    }

    /** 文档变更后使检索缓存失效。缓存服务不可用（未注入）时静默跳过。 */
    private void invalidateCacheIfNeeded() {
        if (cacheService != null) {
            cacheService.invalidateAll();
        }
    }

    /** RAG 检索结果封装——委托给 RagSearchService.RagResult。 */
    public record RagSearchResult(String context, List<Map<String, Object>> traces) {
        public static final RagSearchResult EMPTY = new RagSearchResult("", List.of());
        public boolean isEmpty() { return context.isEmpty(); }
    }
}
