import type { ConversationSummary } from '../api'
import ChevronIcon from './ChevronIcon'
import './Sidebar.css'

interface Props {
  conversations: ConversationSummary[]
  activeId: string
  collapsed: boolean
  username: string | null
  onSelect: (id: string) => void
  onDelete: (id: string) => void
  onNewChat: () => void
  onToggle: () => void
  onLogout: () => void
}

/**
 * 侧边栏——会话列表管理。
 *
 * 支持新建对话、切换会话、删除会话，
 * 可通过左侧边缘按钮折叠/展开。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
export default function Sidebar({
  conversations,
  activeId,
  collapsed,
  username,
  onSelect,
  onDelete,
  onNewChat,
  onToggle,
  onLogout,
}: Props) {
  return (
    <>
      {/* 折叠态下的展开按钮 */}
      {collapsed && (
        <button className="sidebar-expand-btn" onClick={onToggle} title="展开侧边栏">
          <ChevronIcon direction="right" size={18} />
        </button>
      )}

      <aside className={`sidebar${collapsed ? ' collapsed' : ''}`}>
        <div className="sidebar-header">
          <span className="sidebar-title">会话列表</span>
          <button className="sidebar-collapse-btn" onClick={onToggle} title="折叠侧边栏">
            <ChevronIcon direction="left" size={16} />
          </button>
        </div>

        <button className="new-chat-btn" onClick={onNewChat}>
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
            <line x1="12" y1="5" x2="12" y2="19" />
            <line x1="5" y1="12" x2="19" y2="12" />
          </svg>
          新对话
        </button>

        <div className="conversation-list">
          {conversations.length === 0 && (
            <div className="no-conversations">暂无对话记录</div>
          )}
          {conversations.map((conv) => (
            <div
              key={conv.id}
              className={`conversation-item${conv.id === activeId ? ' active' : ''}`}
              onClick={() => onSelect(conv.id)}
            >
              <span className="conv-title">{conv.title}</span>
              <button
                className="conv-delete"
                onClick={(e) => {
                  e.stopPropagation()
                  onDelete(conv.id)
                }}
                title="删除会话"
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
                  <polyline points="3 6 5 6 21 6" />
                  <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
                </svg>
              </button>
            </div>
          ))}
        </div>

        <div className="sidebar-footer">
          <div className="sidebar-user">
            <span className="user-avatar">👤</span>
            <span className="user-name">{username}</span>
          </div>
          <button className="logout-btn" onClick={onLogout} title="退出登录">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
              <polyline points="16 17 21 12 16 7" />
              <line x1="21" y1="12" x2="9" y2="12" />
            </svg>
            退出登录
          </button>
        </div>
      </aside>
    </>
  )
}
