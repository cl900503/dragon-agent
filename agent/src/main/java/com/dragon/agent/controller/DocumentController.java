package com.dragon.agent.controller;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.dragon.agent.dto.DocumentResponse;
import com.dragon.agent.entity.DocumentEntity;
import com.dragon.agent.service.DocumentService;
import com.dragon.agent.support.SecurityHelper;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 文档管理接口——文件上传、列表、删除、下载。
 *
 * 若向量数据库（Milvus）未就绪，上传仍可成功但跳过向量索引。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final SecurityHelper securityHelper;

    public DocumentController(@org.springframework.beans.factory.annotation.Autowired(
                required = false) DocumentService documentService,
            SecurityHelper securityHelper) {
        this.documentService = documentService;
        this.securityHelper = securityHelper;
    }

    private static final long MAX_FILE_SIZE = 20 * 1024 * 1024; // 20MB

    @PostMapping("/upload")
    public Mono<ResponseEntity<Object>> upload(
            @RequestPart("file") FilePart file,
            @RequestParam(name = "conversationId", required = false) String conversationId) {
        if (documentService == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "文档服务未就绪，请稍后重试")));
        }
        return securityHelper.currentUsername()
                .flatMap(username -> {
                    String originalName = file.filename();
                    String mimeType = file.headers().getContentType() != null
                            ? file.headers().getContentType().toString()
                            : "application/octet-stream";

                    return DataBufferUtils.join(file.content())
                            .flatMap(dataBuffer -> {
                                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                                dataBuffer.read(bytes);
                                DataBufferUtils.release(dataBuffer);
                                if (bytes.length > MAX_FILE_SIZE) {
                                    return Mono.just(ResponseEntity.badRequest()
                                            .body(Map.of("error", "文件大小超过限制（最大 20MB）")));
                                }
                                return Mono.fromCallable(() ->
                                        documentService.upload(
                                                new ByteArrayInputStream(bytes),
                                                originalName,
                                                (long) bytes.length,
                                                mimeType,
                                                conversationId,
                                                username))
                                        .subscribeOn(Schedulers.boundedElastic());
                            })
                            .map(response -> ResponseEntity.status(201).body((Object) response));
                });
    }

    @GetMapping
    public Mono<ResponseEntity<List<DocumentResponse>>> list(
            @RequestParam(name = "conversationId", required = false) String conversationId) {
        if (documentService == null) {
            return Mono.just(ResponseEntity.ok(List.of()));
        }
        return securityHelper.currentUsername()
                .map(username -> ResponseEntity.ok(
                        documentService.listDocuments(username, conversationId)));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> delete(@PathVariable String id) {
        if (documentService == null) {
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("id", id);
            result.put("deleted", false);
            result.put("error", "文档服务未就绪");
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(result));
        }
        return securityHelper.currentUsername()
                .flatMap(username -> Mono.fromCallable(() -> {
                    documentService.deleteDocument(id, username);
                    Map<String, Object> result = new java.util.LinkedHashMap<>();
                    result.put("id", id);
                    result.put("deleted", true);
                    result.put("timestamp", Instant.now().toString());
                    return ResponseEntity.ok(result);
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    /** 重试处理失败的文档 */
    @PostMapping("/{id}/retry")
    public Mono<ResponseEntity<Map<String, Object>>> retry(@PathVariable String id) {
        if (documentService == null) {
            Map<String, Object> err = new java.util.LinkedHashMap<>();
            err.put("error", "文档服务未就绪");
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(err));
        }
        return securityHelper.currentUsername()
                .flatMap(username -> Mono.fromCallable(() -> {
                    documentService.retryDocument(id, username);
                    Map<String, Object> result = new java.util.LinkedHashMap<>();
                    result.put("id", id);
                    result.put("status", "RETRYING");
                    return ResponseEntity.ok(result);
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    /** RAG 检索测试——查看查询命中了哪些 chunk 和分数 */
    @PostMapping("/test-retrieval")
    public Mono<ResponseEntity<Map<String, Object>>> testRetrieval(@RequestBody Map<String, String> body) {
        if (documentService == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "文档服务未就绪")));
        }
        String query = body.getOrDefault("query", "");
        if (query.isBlank()) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("error", "query 不能为空")));
        }
        return Mono.fromCallable(() -> {
            var result = documentService.retrieveContext(query);
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("query", query);
            response.put("hit", !result.isEmpty());
            response.put("traces", result.traces());
            response.put("context", result.context().isEmpty() ? null
                    : result.context().substring(0, Math.min(500, result.context().length())));
            return ResponseEntity.ok(response);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** RAG 评测数据导出——批量返回 question + context + answer */
    @PostMapping("/eval-dataset")
    public Mono<ResponseEntity<List<Map<String, Object>>>> evalDataset(
            @RequestBody Map<String, Object> body) {
        if (documentService == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(List.of()));
        }
        @SuppressWarnings("unchecked")
        List<String> questions = (List<String>) body.getOrDefault("questions", List.of());
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> results = new ArrayList<>();
            for (String q : questions) {
                var rag = documentService.retrieveContext(q);
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("question", q);
                item.put("contexts", rag.isEmpty() ? List.of()
                        : rag.traces().stream().map(t -> t.get("contentSnippet")).toList());
                results.add(item);
            }
            return ResponseEntity.ok(results);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/download")
    public Mono<ResponseEntity<InputStreamResource>> download(@PathVariable String id) {
        if (documentService == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        return securityHelper.currentUsername()
                .flatMap(username -> Mono.fromCallable(() -> {
                    DocumentEntity entity = documentService.getDocumentEntity(id, username);
                    InputStream stream = documentService.getFileStream(entity.getStoredPath());
                    InputStreamResource resource = new InputStreamResource(stream);
                    String contentType = entity.getMimeType() != null
                            ? entity.getMimeType()
                            : "application/octet-stream";
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=\"" + entity.getOriginalName() + "\"")
                            .contentType(MediaType.parseMediaType(contentType))
                            .body(resource);
                }).subscribeOn(Schedulers.boundedElastic()));
    }
}
