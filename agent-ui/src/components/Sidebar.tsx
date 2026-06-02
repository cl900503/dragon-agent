import type { ConversationSummary } from '../api'
import ChevronIcon from './ChevronIcon'
import './Sidebar.css'

interface Props {
  conversations: ConversationSummary[]
  activeId: string
  collapsed: boolean
  username: string | null
  kbActive: boolean
  devActive: boolean
  activeDevTool: string
  onSelect: (id: string) => void
  onDelete: (id: string) => void
  onNewChat: () => void
  onToggle: () => void
  onLogout: () => void
  onDevToolSelect: (tool: string) => void
}

export default function Sidebar({
  conversations, activeId, collapsed, username, kbActive, devActive, activeDevTool,
  onSelect, onDelete, onNewChat, onToggle, onLogout, onDevToolSelect,
}: Props) {
  const title = kbActive ? '知识库' : devActive ? '开发工具' : '对话列表'
  const devTools = [
    { id: 'rag', label: 'RAG 检索调试', icon: '🔍' },
    { id: 'markdown', label: 'Markdown 渲染测试', icon: '📝' },
  ]

  return (
    <>
      {collapsed && (
        <button className="sidebar-expand-btn" onClick={onToggle} title="展开">
          <ChevronIcon direction="right" size={18} />
        </button>
      )}

      <aside className={`sidebar${collapsed ? ' collapsed' : ''}`}>
        <div className="sidebar-header">
          <span className="sidebar-title">{title}</span>
          <button className="sidebar-collapse-btn" onClick={onToggle} title="折叠">
            <ChevronIcon direction="left" size={14} />
          </button>
        </div>

        {!kbActive && !devActive && (
          <button className="new-chat-btn" onClick={onNewChat}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <line x1="12" y1="5" x2="12" y2="19" /><line x1="5" y1="12" x2="19" y2="12" />
            </svg>
            开启新对话
          </button>
        )}

        <div className="conversation-list">
          {!kbActive && !devActive && conversations.length === 0 && (
            <div className="no-conversations">暂无对话记录</div>
          )}
          {!kbActive && !devActive && conversations.map((conv) => (
            <div
              key={conv.id}
              className={`conversation-item${conv.id === activeId ? ' active' : ''}`}
              onClick={() => onSelect(conv.id)}
            >
              <span className="conv-title">{conv.title}</span>
              <button
                className="conv-delete"
                onClick={(e) => { e.stopPropagation(); onDelete(conv.id) }}
                title="删除"
              >
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <polyline points="3 6 5 6 21 6" /><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
                </svg>
              </button>
            </div>
          ))}

          {kbActive && (
            <div className="no-conversations">在右侧上传文档到知识库</div>
          )}

          {devActive && devTools.map(tool => (
            <div
              key={tool.id}
              className={`conversation-item${activeDevTool === tool.id ? ' active' : ''}`}
              onClick={() => onDevToolSelect(tool.id)}
            >
              <span className="conv-icon">{tool.icon}</span>
              <span className="conv-title">{tool.label}</span>
            </div>
          ))}
        </div>

        <div className="sidebar-footer">
          <div className="footer-avatar">{username?.charAt(0)?.toUpperCase() || '?'}</div>
          <span className="footer-name">{username}</span>
          <button className="footer-logout" onClick={onLogout} title="退出">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" /><polyline points="16 17 21 12 16 7" /><line x1="21" y1="12" x2="9" y2="12" />
            </svg>
          </button>
        </div>
      </aside>
    </>
  )
}
