/**
 * 右侧问题导航——当前会话中所有用户提问的快速跳转列表。
 *
 * @author 陈龙
 * @since 2026-05-31
 */

import ChevronIcon from './ChevronIcon'
import './QuestionNav.css'

interface QuestionItem {
  id: string
  text: string
}

interface Props {
  questions: QuestionItem[]
  collapsed: boolean
  activeId?: string
  onToggle: () => void
  onJumpTo: (id: string) => void
}

export default function QuestionNav({ questions, collapsed, activeId, onToggle, onJumpTo }: Props) {
  return (
    <>
      {collapsed && questions.length > 0 && (
        <button className="qnav-expand-btn" onClick={onToggle} title="展开问题导航">
          <ChevronIcon direction="left" size={18} />
        </button>
      )}

      <aside className={`question-nav${collapsed ? ' collapsed' : ''}`}>
        <div className="qnav-header">
          <span className="qnav-title">问题导航</span>
          <button className="qnav-collapse-btn" onClick={onToggle} title="折叠导航">
            <ChevronIcon direction="right" size={16} />
          </button>
        </div>

        <div className="qnav-list">
          {questions.length === 0 && (
            <div className="qnav-empty">暂无提问</div>
          )}
          {questions.map((q) => (
            <div
              key={q.id}
              className={`qnav-item${q.id === activeId ? ' active' : ''}`}
              onClick={() => onJumpTo(q.id)}
              title={q.text}
            >
              <span className="qnav-text">{q.text}</span>
            </div>
          ))}
        </div>
      </aside>
    </>
  )
}
