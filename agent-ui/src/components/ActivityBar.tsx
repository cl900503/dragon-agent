/**
 * 左侧导航栏——图标 + 文字，直观切换功能模块。
 *
 * @author 陈龙
 * @since 2026-06-02
 */

import './ActivityBar.css'

export type ActivityView = 'chat' | 'kb' | 'devtools'

interface Props {
  active: ActivityView
  onChange: (view: ActivityView) => void
}

export default function ActivityBar({ active, onChange }: Props) {
  return (
    <nav className="activity-bar">
      <button className={`activity-btn${active === 'chat' ? ' active' : ''}`} onClick={() => onChange('chat')}>
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
        </svg>
        <span className="activity-label">对话</span>
      </button>

      <button className={`activity-btn${active === 'kb' ? ' active' : ''}`} onClick={() => onChange('kb')}>
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
          <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z" />
        </svg>
        <span className="activity-label">知识库</span>
      </button>

      <div className="activity-spacer" />

      <button className={`activity-btn${active === 'devtools' ? ' active' : ''}`} onClick={() => onChange('devtools')}>
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
          <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" />
        </svg>
        <span className="activity-label">工具</span>
      </button>
    </nav>
  )
}
