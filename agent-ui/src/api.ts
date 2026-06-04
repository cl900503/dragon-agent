/**
 * SSE 流式对话客户端及会话管理 API。
 *
 * 封装了 Server-Sent Events 协议的解析细节，并提供会话列表/详情/删除接口。
 *
 * 三种标准 SSE 事件类型（与后端 StreamController 对齐）：
 *   event:thinking —— 推理/思考过程 token（仅推理模型产生）
 *   event:content  —— 正文回复 token（所有模型产生）
 *   event:done     —— 流结束信号（后端主动发送）
 *
 * done 检测采用双重机制，兼容新旧后端：
 *   1. 收到 event:done → 立即触发 onDone
 *   2. ReadableStream 关闭 → 兜底触发 onDone
 *
 * @author 陈龙
 * @since 2026-05-31
 */

import type { UploadedDocument } from './types'

// ---- 类型定义 ----

export interface StreamCallbacks {
  onToken: (text: string) => void
  onThinking: (text: string) => void
  onDone: (retrievedDocs: string[]) => void
  onError: (error: Error) => void
}

export interface ConversationSummary {
  id: string
  title: string
}

export interface ConversationMessages {
  conversationId: string
  messages: BackendMessage[]
  count: number
}

/** 后端返回的原始消息结构 */
export interface BackendMessage {
  id?: string
  messageType: 'USER' | 'ASSISTANT'
  text: string
  reasoning?: string
  retrievalTraces?: RetrievalTrace[]
}

export interface RetrievalTrace {
  documentName: string
  chunkIndex: number
  score?: number
  contentSnippet: string
}

// ---- SSE 流式对话 ----

export function streamChat(
  msg: string,
  conversationId: string,
  userMsgId: string,
  aiMsgId: string,
  enableRag: boolean,
  callbacks: StreamCallbacks,
): AbortController {
  const controller = new AbortController()
  const { onToken, onThinking, onDone, onError } = callbacks

  let doneCalled = false
  let doneDocs: string[] = []

  function safeDone() {
    if (doneCalled) return
    doneCalled = true
    onDone(doneDocs)
  }

  fetch('/api/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message: msg, conversationId, userMsgId, aiMsgId, enableRag }),
    signal: controller.signal,
  })
    .then(async (response) => {
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`)
      }
      if (!response.body) {
        throw new Error('浏览器不支持 ReadableStream')
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''
      let pendingEvent = ''
      let pendingData: string[] = []

      function processLines() {
        const raw = buffer.replace(/\r/g, '')
        const lines = raw.split('\n')
        buffer = lines.pop() || ''

        for (const line of lines) {
          const evMatch = line.match(/^event:(.*)/)
          const dataMatch = line.match(/^data:(.*)/)

          if (evMatch) {
            pendingEvent = evMatch[1].trim()
          } else if (dataMatch) {
            pendingData.push(dataMatch[1])
          } else if (line === '') {
            if (pendingData.length > 0 || pendingEvent) {
              flushEvent(pendingEvent, pendingData)
              pendingEvent = ''
              pendingData = []
            }
          } else if (pendingData.length > 0) {
            pendingData[pendingData.length - 1] += '\n' + line
          }
        }
      }

      function flushEvent(type: string, dLines: string[]) {
        const data = dLines.join('\n')
        if (type === 'thinking') {
          if (data) onThinking(data)
        } else if (type === 'done') {
          // 解析 done 事件：每行 "文件名|片段"
          if (data && data.trim()) {
            doneDocs = data.split('\n').filter(Boolean)
          }
          safeDone()
        } else {
          onToken(data || '\n')
        }
      }

      function read() {
        reader
          .read()
          .then(({ done, value }) => {
            if (done) {
              buffer += '\n'
              processLines()
              if (pendingData.length > 0 || pendingEvent) {
                flushEvent(pendingEvent, pendingData)
              }
              safeDone()
              return
            }
            buffer += decoder.decode(value, { stream: true })
            processLines()
            read()
          })
          .catch((err) => {
            if (err instanceof DOMException && err.name === 'AbortError') {
              safeDone()
            } else {
              onError(err instanceof Error ? err : new Error(String(err)))
            }
          })
      }
      read()
    })
    .catch((err) => {
      if (err instanceof DOMException && err.name === 'AbortError') {
        safeDone()
      } else {
        onError(err instanceof Error ? err : new Error(String(err)))
      }
    })

  return controller
}

// ---- 会话管理 API ----

export async function fetchConversations(): Promise<ConversationSummary[]> {
  const res = await fetch('/api/conversations')
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

export async function fetchConversationMessages(id: string): Promise<ConversationMessages> {
  const res = await fetch(`/api/conversations/${encodeURIComponent(id)}`)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

export async function deleteConversation(id: string): Promise<void> {
  const res = await fetch(`/api/conversations/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
}

// ---- 文档管理 API ----

/** 上传文件到资料库 */
export async function uploadFile(file: File): Promise<UploadedDocument> {
  const formData = new FormData()
  formData.append('file', file)
  const res = await fetch('/api/documents/upload', { method: 'POST', body: formData })
  if (!res.ok) throw new Error(await errMsg(res))
  return res.json()
}

/** 获取文档（可指定知识库） */
export async function fetchDocuments(kbId?: string): Promise<UploadedDocument[]> {
  const url = kbId ? `/api/documents?kbId=${encodeURIComponent(kbId)}` : '/api/documents'
  const res = await fetch(url)
  if (!res.ok) throw new Error(await errMsg(res))
  return res.json()
}

/** 删除指定文档 */
export async function deleteDocument(id: string): Promise<void> {
  const res = await fetch(`/api/documents/${encodeURIComponent(id)}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(await errMsg(res))
}

/** 重试处理失败文档 */
export async function retryDocument(id: string): Promise<void> {
  const res = await fetch(`/api/documents/${encodeURIComponent(id)}/retry`, { method: 'POST' })
  if (!res.ok) throw new Error(await errMsg(res))
}

/** 上传文件到资料库（可指定知识库） */
export async function uploadFileToKb(file: File, kbId?: string): Promise<UploadedDocument> {
  const formData = new FormData()
  formData.append('file', file)
  const params = kbId ? `?kbId=${encodeURIComponent(kbId)}` : ''
  const res = await fetch('/api/documents/upload' + params, { method: 'POST', body: formData })
  if (!res.ok) throw new Error(await errMsg(res))
  return res.json()
}

/** 获取文档下载 URL */
export function getDocumentDownloadUrl(id: string): string {
  return `/api/documents/${encodeURIComponent(id)}/download`
}

// ---- 通用错误解析 ----
async function errMsg(res: Response): Promise<string> {
  try {
    const body = await res.json()
    return body.error || body.message || `HTTP ${res.status}`
  } catch {
    return `HTTP ${res.status}`
  }
}

// ---- 知识库管理 API ----

export interface KbInfo {
  id: string; name: string; description: string; visibility: string; ownerId: number; ownerName?: string; departmentId: number | null; departmentName?: string; docCount?: number; createdAt: string
}

export async function fetchKnowledgeBases(): Promise<KbInfo[]> {
  const res = await fetch('/api/kb')
  if (!res.ok) throw new Error(await errMsg(res))
  return res.json()
}

export async function createKnowledgeBase(body: Record<string, unknown>): Promise<KbInfo> {
  const res = await fetch('/api/kb', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) throw new Error(await errMsg(res))
  return res.json() as any
}

export async function deleteKnowledgeBase(id: string): Promise<void> {
  const res = await fetch(`/api/kb/${encodeURIComponent(id)}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(await errMsg(res))
}

