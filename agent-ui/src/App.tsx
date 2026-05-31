/**
 * Dragon Agent 主应用组件。
 *
 * 管理对话状态、自动追底滚动、SSE 流式接收。
 *
 * @author 陈龙
 * @since 2026-05-31
 */

import { useState, useRef, useCallback, useLayoutEffect, useEffect, Suspense, lazy } from 'react'
import ChatInput from './components/ChatInput'
import MessageBubble from './components/MessageBubble'
import { streamChat } from './api'
import type { Message } from './types'
import './App.css'
import 'katex/dist/katex.min.css'

// MarkdownTest 用 React.lazy 按需加载，不进入主 bundle
const MarkdownTest = lazy(() => import('./components/MarkdownTest'))

const msgId = () => crypto.randomUUID()

/** 滚动条距离底部小于 2px 视为贴底 */
function isNearBottom(el: HTMLElement) {
  return el.scrollHeight - el.scrollTop - el.clientHeight < 2
}

export default function App() {
  const [messages, setMessages] = useState<Message[]>([])
  const [streaming, setStreaming] = useState(false)
  const [testMode, setTestMode] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const areaRef = useRef<HTMLDivElement>(null)
  const abortRef = useRef<AbortController | null>(null)

  // 用户是否贴底。true = 自动追底，false = 用户上滚阅读历史中
  const pinnedRef = useRef(true)

  /**
   * 自动追底 —— 每次 messages 变化（新 token 到达），React 提交 DOM 后同步滚动。
   *
   * 为什么用 useLayoutEffect 而不是直接在回调里调 requestAnimationFrame：
   *   setMessages 只是排队更新，此时 DOM 还没变。
   *   RAF 可能在 React commit 之前执行，读到旧的 scrollHeight，
   *   导致 scrollTop = 旧高度，看起来没动。
   *   useLayoutEffect 保证在 React commit DOM 之后、绘制之前执行，
   *   此时 scrollHeight 已包含最新 token。
   */
  useLayoutEffect(() => {
    if (!areaRef.current || !pinnedRef.current) return
    areaRef.current.scrollTop = areaRef.current.scrollHeight
  }, [messages])

  /**
   * 监听用户滚动意图。
   *
   * 关键：不能只用 scroll 事件判断"用户是否上滚"。
   * 因为滚动条出现 / 消失、DOM 尺寸变化也会触发 scroll 事件，
   * 误判 pinnedRef = false 会导致自动追底停止。
   *
   * 这里用 wheel 事件（deltaY < 0 = 用户明确上滚）来禁用追底，
   * scroll 事件只用于恢复追底（检测到贴底时重新启用）。
   */
  useEffect(() => {
    const el = areaRef.current
    if (!el) return

    const onWheel = (e: WheelEvent) => {
      if (e.deltaY < 0) {
        pinnedRef.current = false
      } else if (isNearBottom(el)) {
        pinnedRef.current = true
      }
    }

    const onScroll = () => {
      // 只恢复，不禁用——禁用的决定权交给 wheel 事件
      if (isNearBottom(el)) pinnedRef.current = true
    }

    el.addEventListener('wheel', onWheel, { passive: true })
    el.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      el.removeEventListener('wheel', onWheel)
      el.removeEventListener('scroll', onScroll)
    }
  }, [])

  /** 发送消息，开始流式接收 */
  const handleSend = useCallback((msg: string) => {
    const userMsg: Message = {
      id: msgId(), role: 'user', content: msg, reasoning: '', thinking: false,
    }
    const aiMsg: Message = {
      id: msgId(), role: 'assistant', content: '', reasoning: '', thinking: true,
    }
    setMessages(prev => [...prev, userMsg, aiMsg])
    setStreaming(true)
    setError(null)
    pinnedRef.current = true // 新消息始终追底

    const controller = streamChat(msg, {
      onToken(text) {
        setMessages(prev => {
          const updated = [...prev]
          const last = updated[updated.length - 1]
          if (last.role !== 'assistant') return prev
          updated[updated.length - 1] = {
            ...last,
            content: last.content + text,
            thinking: false,
          }
          return updated
        })
      },
      onThinking(text) {
        setMessages(prev => {
          const updated = [...prev]
          const last = updated[updated.length - 1]
          if (last.role !== 'assistant') return prev
          updated[updated.length - 1] = {
            ...last,
            reasoning: last.reasoning + text,
          }
          return updated
        })
      },
      onDone() {
        setStreaming(false)
        abortRef.current = null
        setMessages(prev => {
          const updated = [...prev]
          const last = updated[updated.length - 1]
          if (last.role === 'assistant') {
            updated[updated.length - 1] = { ...last, thinking: false }
          }
          return updated
        })
      },
      onError(err) {
        setStreaming(false)
        abortRef.current = null
        setError(err.message)
        setMessages(prev => {
          const updated = [...prev]
          const last = updated[updated.length - 1]
          if (last.role === 'assistant') {
            updated[updated.length - 1] = {
              ...last,
              thinking: false,
              content: last.content || `请求失败：${err.message}`,
            }
          }
          return updated
        })
      },
    })
    abortRef.current = controller
  }, [])

  /** 停止当前生成 */
  const handleStop = useCallback(() => {
    abortRef.current?.abort()
    abortRef.current = null
    setStreaming(false)
    setMessages(prev => {
      const updated = [...prev]
      const last = updated[updated.length - 1]
      if (last.role === 'assistant') {
        updated[updated.length - 1] = { ...last, thinking: false }
      }
      return updated
    })
  }, [])

  return (
    <div className="app">
      <header className="header">
        <span className="logo">🐉</span>
        <div>
          <h1>Dragon Agent</h1>
        </div>
        <button className="test-toggle" onClick={() => setTestMode(v => !v)}>
          {testMode ? '← 返回对话' : '🧪 Markdown 测试'}
        </button>
      </header>

      {error && (
        <div className="error-banner">
          <span>{error}</span>
          <button onClick={() => setError(null)}>✕</button>
        </div>
      )}

      {testMode ? (
        <div className="chat-scroll">
          <div className="chat-area" style={{ padding: 24 }}>
            <Suspense fallback={<div style={{ textAlign: 'center', padding: 40, color: 'var(--text-secondary)' }}>加载中...</div>}>
              <MarkdownTest />
            </Suspense>
          </div>
        </div>
      ) : (
        <>
          <div className="chat-scroll" ref={areaRef}>
            <div className="chat-area">
              {messages.length === 0 && (
                <div className="welcome">
                  <div className="welcome-icon">🐉</div>
                  <h2>Dragon Agent</h2>
                  <p>由 DeepSeek 驱动的 AI 助手，输入消息开始对话</p>
                </div>
              )}
              {messages.map((msg) => (
                <MessageBubble
                  key={msg.id}
                  message={msg}
                  isStreaming={streaming && msg.role === 'assistant' && msg === messages[messages.length - 1]}
                />
              ))}
            </div>
          </div>
          <ChatInput streaming={streaming} onSend={handleSend} onStop={handleStop} />
        </>
      )}
    </div>
  )
}
