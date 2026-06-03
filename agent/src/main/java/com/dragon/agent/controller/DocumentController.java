package com.dragon.agent.controller;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

import com.dragon.agent.dto.DocumentResponse;
import com.dragon.agent.entity.DocumentEntity;
import com.dragon.agent.service.DocumentService;
import com.dragon.agent.support.SecurityHelper;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 文档管理接口——上传、列表、删除、下载、重试。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final long MAX_FILE_SIZE = 20 * 1024 * 1024;

    @Autowired(required = false)
    private DocumentService documentService;

    @Autowired
    private SecurityHelper securityHelper;

    @org.springframework.beans.factory.annotation.Value("${app.upload.allowed-mime-types}")
    private List<String> allowedMimeTypes;

    @PostMapping("/upload")
    public Mono<ResponseEntity<Object>> upload(@RequestPart("file") FilePart file,
            @RequestParam(name = "conversationId", required = false) String conversationId) {
        if (documentService == null)
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "文档服务未就绪")));
        return securityHelper.currentUsername().flatMap(username -> {
            String originalName = file.filename();
            String mimeType = file.headers().getContentType() != null
                    ? file.headers().getContentType().toString()
                    : "application/octet-stream";
            if (!isAllowedMimeType(mimeType))
                return Mono.just(ResponseEntity.badRequest()
                        .body(Map.of("error", "不支持的文件类型: " + mimeType)));
            return DataBufferUtils.join(file.content()).flatMap(dataBuffer -> {
                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                dataBuffer.read(bytes);
                DataBufferUtils.release(dataBuffer);
                if (bytes.length > MAX_FILE_SIZE)
                    return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "文件大小超过限制（最大 20MB）")));
                return Mono
                        .fromCallable(() -> documentService.upload(new ByteArrayInputStream(bytes), originalName,
                                (long) bytes.length, mimeType, conversationId, username))
                        .subscribeOn(Schedulers.boundedElastic()).map(r -> ResponseEntity.status(201).body((Object) r));
            });
        });
    }

    @GetMapping
    public Mono<ResponseEntity<List<DocumentResponse>>> list(
            @RequestParam(name = "conversationId", required = false) String conversationId) {
        if (documentService == null)
            return Mono.just(ResponseEntity.ok(List.of()));
        return securityHelper.currentUsername()
                .map(username -> ResponseEntity.ok(documentService.listDocuments(username, conversationId)));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> delete(@PathVariable String id) {
        if (documentService == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("id", id);
            err.put("deleted", false);
            err.put("error", "文档服务未就绪");
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(err));
        }
        return securityHelper.currentUsername().flatMap(username -> Mono.fromCallable(() -> {
            documentService.deleteDocument(id, username);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("deleted", true);
            result.put("timestamp", Instant.now().toString());
            return ResponseEntity.ok(result);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping("/{id}/retry")
    public Mono<ResponseEntity<Map<String, Object>>> retry(@PathVariable String id) {
        if (documentService == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "文档服务未就绪");
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(err));
        }
        return securityHelper.currentUsername().flatMap(username -> Mono.fromCallable(() -> {
            documentService.retryDocument(id, username);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("status", "RETRYING");
            return ResponseEntity.ok(result);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping("/test-retrieval")
    public Mono<ResponseEntity<Map<String, Object>>> testRetrieval(@RequestBody Map<String, String> body) {
        if (documentService == null)
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "文档服务未就绪")));
        String query = body.getOrDefault("query", "");
        if (query.isBlank())
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "query 不能为空")));
        return securityHelper.currentUsername().flatMap(username -> Mono.fromCallable(() -> {
            Long userId = documentService.getUserId(username);
            var result = documentService.retrieveContext(query, userId);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("query", query);
            response.put("hit", !result.isEmpty());
            response.put("traces", result.traces());
            response.put("context",
                    result.context().isEmpty()
                            ? null
                            : result.context().substring(0, Math.min(500, result.context().length())));
            return ResponseEntity.ok(response);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    /** 检查 MIME 类型是否在允许列表中 */
    private boolean isAllowedMimeType(String mimeType) {
        return allowedMimeTypes.stream().anyMatch(allowed -> {
            if (allowed.endsWith("/*"))
                return mimeType.startsWith(allowed.replace("/*", "/"));
            return allowed.equals(mimeType);
        });
    }

    @GetMapping("/{id}/download")
    public Mono<ResponseEntity<InputStreamResource>> download(@PathVariable String id) {
        if (documentService == null)
            return Mono.just(ResponseEntity.notFound().build());
        return securityHelper.currentUsername().flatMap(username -> Mono.fromCallable(() -> {
            DocumentEntity entity = documentService.getDocumentEntity(id, username);
            InputStream stream = documentService.getFileStream(entity.getStoredPath());
            InputStreamResource resource = new InputStreamResource(stream);
            String contentType = entity.getMimeType() != null ? entity.getMimeType() : "application/octet-stream";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + entity.getOriginalName() + "\"")
                    .contentType(MediaType.parseMediaType(contentType)).body(resource);
        }).subscribeOn(Schedulers.boundedElastic()));
    }
}
