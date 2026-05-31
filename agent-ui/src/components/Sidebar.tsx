import type { ConversationSummary } from '../api'
import './Sidebar.css'

interface Props {
  conversations: ConversationSummary[]
  activeId: string
  collapsed: boolean
  onSelect: (id: string) => void
  onDelete: (id: string) => void
  onNewChat: () => void
  onToggle: () => void
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
  onSelect,
  onDelete,
  onNewChat,
  onToggle,
}: Props) {
  return (
    <>
      {/* 折叠态下的展开按钮 */}
      {collapsed && (
        <button className="sidebar-expand-btn" onClick={onToggle} title="展开侧边栏">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <polyline points="9 18 15 12 9 6" />
          </svg>
        </button>
      )}

      <aside className={`sidebar${collapsed ? ' collapsed' : ''}`}>
        <div className="sidebar-header">
          <span className="sidebar-title">会话列表</span>
          <button className="sidebar-collapse-btn" onClick={onToggle} title="折叠侧边栏">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
              <polyline points="15 18 9 12 15 6" />
            </svg>
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
      </aside>
    </>
  )
}
