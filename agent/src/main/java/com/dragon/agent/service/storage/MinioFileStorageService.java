package com.dragon.agent.service.storage;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;

/**
 * MinIO 对象存储实现。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
@Service
public class MinioFileStorageService implements FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioFileStorageService.class);

    @Autowired
    private MinioClient minioClient;

    @Value("${app.minio.bucket}")
    private String bucket;

    @Override
    public String store(String originalName, long fileSize, String contentType, InputStream data) {
        String datePath = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        String uniqueName = UUID.randomUUID().toString().substring(0, 8) + "-"
                + originalName.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", "_");
        String objectKey = "documents/" + datePath + "/" + uniqueName;

        try {
            minioClient.putObject(PutObjectArgs.builder().bucket(bucket).object(objectKey).stream(data, fileSize, -1)
                    .contentType(contentType).build());
            log.info("File stored: {} ({} bytes)", objectKey, fileSize);
            return objectKey;
        } catch (Exception e) {
            throw new RuntimeException("文件存储失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream read(String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            throw new RuntimeException("文件读取失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
            log.info("File deleted: {}", objectKey);
        } catch (Exception e) {
            throw new RuntimeException("文件删除失败: " + e.getMessage(), e);
        }
    }
}
