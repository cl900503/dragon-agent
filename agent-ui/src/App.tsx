/**
 * Dragon Agent 主应用组件。
 *
 * 管理多会话状态、侧边栏交互、自动追底滚动、SSE 流式接收。
 *
 * @author 陈龙
 * @since 2026-05-31
 */

import { useState, useRef, useCallback, useLayoutEffect, useEffect, Suspense, lazy } from 'react'
import ChatInput from './components/ChatInput'
import MessageBubble from './components/MessageBubble'
import Sidebar from './components/Sidebar'
import QuestionNav from './components/QuestionNav'
import { streamChat, fetchConversations, fetchConversationMessages, deleteConversation } from './api'
import type { Message } from './types'
import type { ConversationSummary } from './api'
import mermaid from 'mermaid'
import './App.css'
import 'katex/dist/katex.min.css'

// Mermaid 全局初始化一次
mermaid.initialize({ startOnLoad: false, theme: 'default' })

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
  const [activeConversationId, setActiveConversationId] = useState<string>(() => crypto.randomUUID())
  const [conversations, setConversations] = useState<ConversationSummary[]>([])
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)
  const [qnavCollapsed, setQnavCollapsed] = useState(true)
  const [activeQuestionId, setActiveQuestionId] = useState<string | null>(null)
  const [allMessages, setAllMessages] = useState<Record<string, Message[]>>({})

  const areaRef = useRef<HTMLDivElement>(null)
  const abortRef = useRef<AbortController | null>(null)

  // 用户是否贴底。true = 自动追底，false = 用户上滚阅读历史中
  const pinnedRef = useRef(true)

  // 用 ref 保持最新 conversationId，避免 handleSend 闭包过期
  const conversationIdRef = useRef(activeConversationId)
  useEffect(() => { conversationIdRef.current = activeConversationId }, [activeConversationId])

  // 挂载时加载会话列表
  useEffect(() => {
    fetchConversations()
      .then(setConversations)
      .catch(() => {})
  }, [])

  const refreshConversations = useCallback(() => {
    fetchConversations()
      .then(setConversations)
      .catch(() => {})
  }, [])

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
    pinnedRef.current = true

    const controller = streamChat(msg, conversationIdRef.current, {
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
        refreshConversations()
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
        refreshConversations()
      },
    })
    abortRef.current = controller
  }, [refreshConversations])

  /** 新建会话 */
  const handleNewChat = useCallback(() => {
    abortRef.current?.abort()
    abortRef.current = null
    if (messages.length > 0) {
      setAllMessages(prev => ({ ...prev, [activeConversationId]: messages }))
    }
    setStreaming(false)
    setMessages([])
    setError(null)
    const newId = crypto.randomUUID()
    setActiveConversationId(newId)
  }, [messages, activeConversationId])

  /** 切换会话 */
  const handleSelectConversation = useCallback(async (id: string) => {
    if (id === activeConversationId) return

    abortRef.current?.abort()
    abortRef.current = null
    setStreaming(false)
    setError(null)

    if (messages.length > 0) {
      setAllMessages(prev => ({ ...prev, [activeConversationId]: messages }))
    }

    // 优先使用本地缓存
    if (allMessages[id]) {
      setMessages(allMessages[id])
      setActiveConversationId(id)
      return
    }

    try {
      const data = await fetchConversationMessages(id)
      const mapped: Message[] = data.messages.map(bm => ({
        id: msgId(),
        role: bm.messageType === 'USER' ? 'user' as const : 'assistant' as const,
        content: bm.text,
        reasoning: '',
        thinking: false,
      }))
      setAllMessages(prev => ({ ...prev, [id]: mapped }))
      setMessages(mapped)
      setActiveConversationId(id)
    } catch {
      setError('加载对话失败')
    }
  }, [activeConversationId, messages, allMessages])

  /** 删除会话 */
  const handleDeleteConversation = useCallback(async (id: string) => {
    try {
      await deleteConversation(id)
    } catch {
      // API 失败也继续清理本地状态
    }

    setConversations(prev => prev.filter(c => c.id !== id))
    setAllMessages(prev => {
      const next = { ...prev }
      delete next[id]
      return next
    })

    if (id === activeConversationId) {
      setMessages([])
      setActiveConversationId(crypto.randomUUID())
    }
  }, [activeConversationId])

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

  /** 跳转到指定用户消息 */
  const handleJumpTo = useCallback((messageId: string) => {
    setActiveQuestionId(messageId)
    const el = document.getElementById(`msg-${messageId}`)
    if (el) {
      el.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }
  }, [])

  // 当前会话中所有用户提问
  const userQuestions = messages
    .filter(m => m.role === 'user' && m.content.trim())
    .map(m => ({ id: m.id, text: m.content }))

  const showWelcome = !testMode && messages.length === 0

  return (
    <div className="app-layout">
      <Sidebar
        conversations={conversations}
        activeId={activeConversationId}
        collapsed={sidebarCollapsed}
        onSelect={handleSelectConversation}
        onDelete={handleDeleteConversation}
        onNewChat={handleNewChat}
        onToggle={() => setSidebarCollapsed(v => !v)}
      />
      <div className="app">
        <header className="header">
          <span className="logo">🐉</span>
          <div>
            <h1>Dragon Agent</h1>
          </div>
          {import.meta.env.DEV && (
            <button className="test-toggle" onClick={() => setTestMode(v => !v)}>
              {testMode ? '← 返回对话' : '🧪 Markdown 测试'}
            </button>
          )}
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
        ) : showWelcome ? (
          <div className="welcome-layout">
            <div className="welcome">
              <div className="welcome-icon">🐉</div>
              <h2>Dragon Agent</h2>
              <p>由 DeepSeek 驱动的 AI 助手，输入消息开始对话</p>
            </div>
            <ChatInput streaming={streaming} onSend={handleSend} onStop={handleStop} />
          </div>
        ) : (
          <>
            <div className="chat-scroll" ref={areaRef}>
              <div className="chat-area">
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
      <QuestionNav
        questions={userQuestions}
        collapsed={qnavCollapsed}
        activeId={activeQuestionId ?? undefined}
        onToggle={() => setQnavCollapsed(v => !v)}
        onJumpTo={handleJumpTo}
      />
    </div>
  )
}
