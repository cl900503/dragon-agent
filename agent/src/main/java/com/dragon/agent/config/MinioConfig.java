package com.dragon.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;

/**
 * MinIO 对象存储客户端配置。
 *
 * 启动时自动检查并创建 Bucket。 若 MinIO 未就绪，仅记录警告，不阻止应用启动（首次使用时再报错）。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
@Configuration
public class MinioConfig {

    private static final Logger log = LoggerFactory.getLogger(MinioConfig.class);

    @Value("${app.minio.endpoint}")
    private String endpoint;

    @Value("${app.minio.access-key}")
    private String accessKey;

    @Value("${app.minio.secret-key}")
    private String secretKey;

    @Value("${app.minio.bucket}")
    private String bucket;

    @Bean
    public MinioClient minioClient() {
        MinioClient client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();

        // 启动时尝试创建 Bucket——失败不阻止启动，仅记录警告
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO bucket [{}] created at {}", bucket, endpoint);
            } else {
                log.info("MinIO bucket [{}] already exists at {}", bucket, endpoint);
            }
        } catch (Exception e) {
            log.warn(
                    "MinIO not reachable at {}: {}. The application will start, but file upload will fail until MinIO is running.",
                    endpoint, e.getMessage());
        }

        return client;
    }
}
