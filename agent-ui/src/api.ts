/**
 * SSE 流式对话客户端。
 *
 * 封装了 Server-Sent Events 协议的解析细节，调用方只需传入回调函数。
 *
 * 三种标准 SSE 事件类型（与后端 StreamController 对齐）：
 *   event:thinking —— 推理/思考过程 token（仅推理模型产生）
 *   event:content  —— 正文回复 token（所有模型产生）
 *   event:done     —— 流结束信号（后端主动发送）
 *
 * done 检测采用双重机制，兼容新旧后端：
 *   1. 收到 event:done → 立即触发 onDone
 *   2. ReadableStream 关闭 → 兜底触发 onDone（旧版后端不发送 done 事件时）
 *   两者互斥：done 事件触发后忽略后续流关闭，避免 onDone 重复调用。
 *
 * 多模型兼容：未知事件类型（无 event: 字段或未识别的事件名）一律视为 content，
 * 确保未来新增模型或事件类型时前端无需改动。
 *
 * 用法：
 *   const controller = streamChat(msg, {
 *     onToken:    (t) => append(t),
 *     onThinking: (t) => appendReasoning(t),
 *     onDone:     () => setStreaming(false),
 *     onError:    (e) => showError(e.message),
 *   });
 *   // 中途取消：controller.abort()
 *
 * @author 陈龙
 * @since 2026-05-31
 */
export interface StreamCallbacks {
  onToken: (text: string) => void
  onThinking: (text: string) => void
  onDone: () => void
  onError: (error: Error) => void
}

export function streamChat(
  msg: string,
  callbacks: StreamCallbacks,
): AbortController {
  const controller = new AbortController()
  const { onToken, onThinking, onDone, onError } = callbacks

  // 防止 done 重复触发（event:done 和 ReadableStream 关闭可能先后到达）
  let doneCalled = false

  function safeDone() {
    if (doneCalled) return
    doneCalled = true
    onDone()
  }

  fetch('/api/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ msg }),
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

      // SSE 行协议解析：按 \n 分割，空行表示一个事件帧结束
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
            // 空行 = 一个事件帧结束，触发回调
            if (pendingData.length > 0 || pendingEvent) {
              flushEvent(pendingEvent, pendingData)
              pendingEvent = ''
              pendingData = []
            }
          } else if (pendingData.length > 0) {
            // 多行 data 的续行（如代码块内的换行）
            pendingData[pendingData.length - 1] += '\n' + line
          }
        }
      }

      function flushEvent(type: string, dLines: string[]) {
        const data = dLines.join('\n')
        if (type === 'thinking') {
          // 推理模型思考过程
          if (data) onThinking(data)
        } else if (type === 'done') {
          // 后端主动发送的流结束信号
          safeDone()
        } else {
          // event:content、默认事件（无 event: 字段）、
          // 以及未来可能新增的事件类型 → 一律视为正文
          onToken(data || '\n')
        }
      }

      function read() {
        reader
          .read()
          .then(({ done, value }) => {
            if (done) {
              // ReadableStream 关闭，消费缓冲区残余数据（兜底 done 检测）
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
