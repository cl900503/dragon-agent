/**
 * 统一类型定义。
 *
 * @author 陈龙
 * @since 2026-05-31
 */

/** 单条聊天消息 */
export interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  reasoning: string
  thinking: boolean
  /** RAG 检索追溯（仅 USER 消息有值） */
  retrievalTraces?: RetrievalTraceItem[]
}

export interface RetrievalTraceItem {
  documentName: string
  chunkIndex: number
  score?: number
  contentSnippet: string
}

/** 上传文档状态 */
export interface UploadedDocument {
  id: string
  originalName: string
  fileSize: number
  mimeType: string
  status: 'UPLOADING' | 'PARSING' | 'INDEXING' | 'READY' | 'FAILED'
  chunkCount: number
  createdAt: string
}

