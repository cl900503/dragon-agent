package com.dragon.agent.entity;

/**
 * 文档处理状态枚举。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
public enum DocumentStatus {
    /** 文件已上传至 MinIO，等待处理 */
    UPLOADING,
    /** Tika 解析中 */
    PARSING,
    /** 向量化并写入 Milvus 中 */
    INDEXING,
    /** 处理完成，可用于 RAG 检索 */
    READY,
    /** 处理失败 */
    FAILED
}
