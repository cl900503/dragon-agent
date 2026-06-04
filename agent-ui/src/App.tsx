import { useState, useRef, useLayoutEffect, useEffect, useCallback, lazy, Suspense } from 'react'
import ChatInput from './components/ChatInput'
import MessageBubble from './components/MessageBubble'
import Sidebar from './components/Sidebar'
import ActivityBar, { type ActivityView } from './components/ActivityBar'
import QuestionNav from './components/QuestionNav'
import LoginPage from './components/LoginPage'
import KnowledgeBase from './components/KnowledgeBase'
import RagTest from './components/RagTest'
import AdminPanel from './components/AdminPanel'
import RagDashboard from './components/RagDashboard'
import ToastContainer from './components/Toast'
import { useAuth } from './hooks/useAuth'
import { useConversation } from './hooks/useConversation'
import { fetchDocuments } from './api'
import type { UploadedDocument } from './types'
import './App.css'
import 'katex/dist/katex.min.css'

const MarkdownTest = lazy(() => import('./components/MarkdownTest'))

function isNearBottom(el: HTMLElement) {
  return el.scrollHeight - el.scrollTop - el.clientHeight < 2
}

export default function App() {
  const { isLoggedIn, username, role, perms, authLoading, handleLogin, handleLogout: authHandleLogout } = useAuth()
  const {
    messages, streaming, error, activeConversationId, conversations,
    handleSend, handleNewChat, handleSelectConversation,
    handleDeleteConversation, handleStop,
    reset: resetConversation, setError,
  } = useConversation(isLoggedIn)

  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)
  const [qnavCollapsed, setQnavCollapsed] = useState(true)
  const [activeQuestionId, setActiveQuestionId] = useState<string | null>(null)
  const [documents, setDocuments] = useState<UploadedDocument[]>([])
  const [activity, setActivity] = useState<ActivityView>('chat')
  const [ragEnabled, setRagEnabled] = useState(true)
  const [activeDevTool, setActiveDevTool] = useState('rag')
  const [activeKbId, setActiveKbId] = useState('')
  const [selectedKbInfo, setSelectedKbInfo] = useState<import('./api').KbInfo | null>(null)
  const [activeAdminDept, setActiveAdminDept] = useState('')
  const [adminRefreshKey, setAdminRefreshKey] = useState(0)

  const areaRef = useRef<HTMLDivElement>(null)
  const pinnedRef = useRef(true)

  useLayoutEffect(() => {
    if (!areaRef.current || !pinnedRef.current) return
    areaRef.current.scrollTop = areaRef.current.scrollHeight
  }, [messages])

  useEffect(() => {
    if (isLoggedIn) {
      fetchDocuments(activeKbId || undefined).then(setDocuments).catch(() => {})
    } else { setDocuments([]) }
  }, [isLoggedIn, activeKbId])

  const devView = activity === 'devtools'
  const kbView = activity === 'kb'
  const adminView = activity === 'admin'
  const showWelcome = !kbView && !devView && !adminView && messages.length === 0

  useEffect(() => {
    const el = areaRef.current; if (!el) return
    const onWheel = (e: WheelEvent) => {
      if (e.deltaY < 0) pinnedRef.current = false
      else if (isNearBottom(el)) pinnedRef.current = true
    }
    const onScroll = () => { if (isNearBottom(el)) pinnedRef.current = true }
    el.addEventListener('wheel', onWheel, { passive: true })
    el.addEventListener('scroll', onScroll, { passive: true })
    return () => { el.removeEventListener('wheel', onWheel); el.removeEventListener('scroll', onScroll) }
  }, [showWelcome])

  const handleSendAndPin = useCallback((msg: string) => {
    pinnedRef.current = true; handleSend(msg, ragEnabled)
  }, [handleSend, ragEnabled])

  const handleLogout = useCallback(async () => {
    resetConversation(); setDocuments([]); authHandleLogout()
  }, [resetConversation, authHandleLogout])

  const handleJumpTo = useCallback((messageId: string) => {
    setActiveQuestionId(messageId)
    document.getElementById(`msg-${messageId}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }, [])

  // 切换 Activity 时联动
  const handleActivity = useCallback((v: ActivityView) => {
    setActivity(v)
    if (v === 'devtools') setActiveDevTool('rag')
  }, [])

  const handleSelectAndSwitch = useCallback((id: string) => {
    setActivity('chat'); handleSelectConversation(id)
  }, [handleSelectConversation])

  const handleNewChatAndSwitch = useCallback(() => {
    setActivity('chat'); handleNewChat()
  }, [handleNewChat])

  const userQuestions = messages
    .filter(m => m.role === 'user' && m.content.trim())
    .map(m => ({ id: m.id, text: m.content }))

  if (authLoading) {
    return (
      <div className="app-layout">
        <div className="app app-loading">
          <div className="welcome"><div className="welcome-icon">🐉</div><p className="loading-text">加载中...</p></div>
        </div>
      </div>
    )
  }

  if (!isLoggedIn) {
    return (
      <div className="app-layout">
        <div className="app"><LoginPage onLogin={handleLogin} /></div>
      </div>
    )
  }

  return (
    <div className="app-layout">
      <ToastContainer />
      <ActivityBar active={activity} onChange={handleActivity} />

      <Sidebar
        conversations={conversations}
        activeId={activeConversationId}
        collapsed={sidebarCollapsed}
        username={username}
        perms={perms}
        kbActive={kbView}
        devActive={devView}
        adminActive={adminView}
        activeDevTool={activeDevTool}
        activeKbId={activeKbId}
        onSelect={handleSelectAndSwitch}
        onDelete={handleDeleteConversation}
        onNewChat={handleNewChatAndSwitch}
        onToggle={() => setSidebarCollapsed(v => !v)}
        onLogout={handleLogout}
        onDevToolSelect={setActiveDevTool}
        onKbSelect={(id, info) => { setActiveKbId(id); setSelectedKbInfo(info || null) }}
        onAdminSelect={setActiveAdminDept}
        activeAdminDept={activeAdminDept}
        onDeptChange={() => setAdminRefreshKey(k => k + 1)}
      />

      <div className="app">
        <header className="header">
          <span className="logo">🐉</span>
          <div><h1>Dragon Agent</h1></div>
        </header>

        {error && (
          <div className="error-banner"><span>{error}</span><button onClick={() => setError(null)}>✕</button></div>
        )}

        {kbView ? (
          <KnowledgeBase documents={documents} onDocumentsChange={setDocuments} activeKbId={activeKbId} currentUsername={username || ''} canUpload={selectedKbInfo?.canUpload} />
        ) : adminView ? (
          <AdminPanel activeDept={activeAdminDept} perms={perms} currentUsername={username || ''} refreshKey={adminRefreshKey} />
        ) : devView ? (
          activeDevTool === 'markdown' ? (
            <div className="chat-scroll"><div className="chat-area chat-area--test">
              <Suspense fallback={<div className="devtools-loading">加载中...</div>}><MarkdownTest /></Suspense>
            </div></div>
          ) : activeDevTool === 'dashboard' ? (
            <RagDashboard />
          ) : (
            <RagTest />
          )
        ) : showWelcome ? (
          <div className="welcome-layout">
            <div className="welcome">
              <div className="welcome-icon">🐉</div>
              <h2>Dragon Agent</h2>
              <p>由 DeepSeek 驱动的 AI 助手，输入消息开始对话</p>
              <p className="welcome-upload-hint">左侧导航切换到「知识库」上传文档，AI 即可基于文档内容作答</p>
            </div>
            <ChatInput streaming={streaming} onSend={handleSendAndPin} onStop={handleStop} ragEnabled={ragEnabled} onRagToggle={setRagEnabled} />
          </div>
        ) : (
          <>
            <div className="chat-scroll" ref={areaRef}>
              <div className="chat-area">{messages.map(msg => <MessageBubble key={msg.id} message={msg} />)}</div>
            </div>
            <ChatInput streaming={streaming} onSend={handleSendAndPin} onStop={handleStop} ragEnabled={ragEnabled} onRagToggle={setRagEnabled} />
          </>
        )}
      </div>

      <QuestionNav
        questions={userQuestions} collapsed={qnavCollapsed}
        activeId={activeQuestionId ?? undefined}
        onToggle={() => setQnavCollapsed(v => !v)} onJumpTo={handleJumpTo}
      />
    </div>
  )
}
