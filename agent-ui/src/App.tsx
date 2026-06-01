/**
 * Dragon Agent 主应用组件。
 *
 * 管理多会话状态、侧边栏交互、自动追底滚动、SSE 流式接收和登录态。
 *
 * @author 陈龙
 * @since 2026-05-31
 */

import { useState, useRef, useLayoutEffect, useEffect, useCallback, Suspense, lazy } from 'react'
import ChatInput from './components/ChatInput'
import MessageBubble from './components/MessageBubble'
import Sidebar from './components/Sidebar'
import QuestionNav from './components/QuestionNav'
import LoginPage from './components/LoginPage'
import { useAuth } from './hooks/useAuth'
import { useConversation } from './hooks/useConversation'
import './App.css'
import 'katex/dist/katex.min.css'

// MarkdownTest 用 React.lazy 按需加载，不进入主 bundle
const MarkdownTest = lazy(() => import('./components/MarkdownTest'))

/** 滚动条距离底部小于 2px 视为贴底 */
function isNearBottom(el: HTMLElement) {
  return el.scrollHeight - el.scrollTop - el.clientHeight < 2
}

export default function App() {
  const { isLoggedIn, username, authLoading, handleLogin, handleLogout: authHandleLogout } = useAuth()
  const {
    messages,
    streaming,
    error,
    activeConversationId,
    conversations,
    handleSend,
    handleNewChat,
    handleSelectConversation,
    handleDeleteConversation,
    handleStop,
    reset: resetConversation,
    setError,
  } = useConversation(isLoggedIn)

  const [testMode, setTestMode] = useState(false)
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)
  const [qnavCollapsed, setQnavCollapsed] = useState(true)
  const [activeQuestionId, setActiveQuestionId] = useState<string | null>(null)

  const areaRef = useRef<HTMLDivElement>(null)
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
  const showWelcome = !testMode && messages.length === 0

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
      if (isNearBottom(el)) pinnedRef.current = true
    }

    el.addEventListener('wheel', onWheel, { passive: true })
    el.addEventListener('scroll', onScroll, { passive: true })
    return () => {
      el.removeEventListener('wheel', onWheel)
      el.removeEventListener('scroll', onScroll)
    }
  }, [showWelcome])

  /** 发送消息并强制追底 */
  const handleSendAndPin = useCallback((msg: string) => {
    pinnedRef.current = true
    handleSend(msg)
  }, [handleSend])

  /** 退出登录 */
  const handleLogout = useCallback(async () => {
    resetConversation()
    authHandleLogout()
  }, [resetConversation, authHandleLogout])

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

  // 会话检查中
  if (authLoading) {
    return (
      <div className="app-layout">
        <div className="app app-loading">
          <div className="welcome">
            <div className="welcome-icon">🐉</div>
            <p className="loading-text">加载中...</p>
          </div>
        </div>
      </div>
    )
  }

  // 未登录
  if (!isLoggedIn) {
    return (
      <div className="app-layout">
        <div className="app">
          <LoginPage onLogin={handleLogin} />
        </div>
      </div>
    )
  }

  return (
    <div className="app-layout">
      <Sidebar
        conversations={conversations}
        activeId={activeConversationId}
        collapsed={sidebarCollapsed}
        username={username}
        onSelect={handleSelectConversation}
        onDelete={handleDeleteConversation}
        onNewChat={handleNewChat}
        onToggle={() => setSidebarCollapsed(v => !v)}
        onLogout={handleLogout}
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
            <div className="chat-area chat-area--test">
              <Suspense fallback={<div className="suspense-fallback">加载中...</div>}>
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
            <ChatInput streaming={streaming} onSend={handleSendAndPin} onStop={handleStop} />
          </div>
        ) : (
          <>
            <div className="chat-scroll" ref={areaRef}>
              <div className="chat-area">
                {messages.map((msg) => (
                  <MessageBubble
                    key={msg.id}
                    message={msg}
                  />
                ))}
              </div>
            </div>
            <ChatInput streaming={streaming} onSend={handleSendAndPin} onStop={handleStop} />
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
