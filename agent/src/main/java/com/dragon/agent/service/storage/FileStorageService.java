package com.dragon.agent.service.storage;

import java.io.InputStream;

/**
 * 文件存储服务接口——抽象底层存储实现。
 *
 * 当前实现：{@link MinioFileStorageService}（MinIO 对象存储）。
 * 后续可扩展 S3、阿里云 OSS 等实现。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
public interface FileStorageService {

    /**
     * 存储文件并返回对象的 key（路径）。
     *
     * @param originalName 原始文件名
     * @param fileSize     文件大小（字节）
     * @param contentType  MIME 类型
     * @param data         文件数据流
     * @return 对象存储 key
     */
    String store(String originalName, long fileSize, String contentType, InputStream data);

    /**
     * 读取文件内容。
     *
     * @param objectKey 对象存储 key
     * @return 文件数据流
     */
    InputStream read(String objectKey);

    /**
     * 删除文件。
     *
     * @param objectKey 对象存储 key
     */
    void delete(String objectKey);
}
