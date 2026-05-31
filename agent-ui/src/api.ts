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

// ---- 类型定义 ----

export interface StreamCallbacks {
  onToken: (text: string) => void
  onThinking: (text: string) => void
  onDone: () => void
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
  messageType: 'USER' | 'ASSISTANT'
  text: string
}

// ---- SSE 流式对话 ----

export function streamChat(
  msg: string,
  conversationId: string,
  callbacks: StreamCallbacks,
): AbortController {
  const controller = new AbortController()
  const { onToken, onThinking, onDone, onError } = callbacks

  let doneCalled = false

  function safeDone() {
    if (doneCalled) return
    doneCalled = true
    onDone()
  }

  fetch('/api/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ message: msg, conversationId }),
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

