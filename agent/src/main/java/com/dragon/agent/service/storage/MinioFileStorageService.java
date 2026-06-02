package com.dragon.agent.service.storage;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;

/**
 * MinIO 对象存储实现——文件存取。
 *
 * 文件按日期分层组织：documents/yyyy/MM/dd/uuid-originalName
 *
 * @author 陈龙
 * @since 2026-06-01
 */
@Service
public class MinioFileStorageService implements FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioFileStorageService.class);

    private final MinioClient minioClient;

    @Value("${app.minio.bucket}")
    private String bucket;

    public MinioFileStorageService(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    @Override
    public String store(String originalName, long fileSize, String contentType, InputStream data) {
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String uniqueName = UUID.randomUUID().toString().substring(0, 8) + "-" + sanitizeFileName(originalName);
        String objectKey = "documents/" + datePath + "/" + uniqueName;

        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(data, fileSize, -1)
                    .contentType(contentType)
                    .build());
            log.info("File stored in MinIO: {} ({} bytes)", objectKey, fileSize);
            return objectKey;
        } catch (Exception e) {
            log.error("Failed to store file in MinIO: {}", objectKey, e);
            throw new RuntimeException("文件存储失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream read(String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            log.error("Failed to read file from MinIO: {}", objectKey, e);
            throw new RuntimeException("文件读取失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            log.info("File deleted from MinIO: {}", objectKey);
        } catch (Exception e) {
            log.error("Failed to delete file from MinIO: {}", objectKey, e);
            throw new RuntimeException("文件删除失败: " + e.getMessage(), e);
        }
    }

    /** 清理文件名中的特殊字符 */
    private String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "_");
    }
}
