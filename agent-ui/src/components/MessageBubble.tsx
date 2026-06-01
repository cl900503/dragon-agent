import { useState, useCallback } from 'react'
import MarkdownRenderer from './MarkdownRenderer'
import type { Message } from '../types'
import './MessageBubble.css'

interface Props {
  message: Message
}

/**
 * 消息气泡组件——渲染单条对话。
 *
 * 支持 Markdown 实时渲染（GFM + KaTeX 数学公式 + Mermaid 图表）、
 * DeepSeek 思考过程展开 / 折叠。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
export default function MessageBubble({ message }: Props) {
  const [reasoningOpen, setReasoningOpen] = useState(true)

  const toggle = useCallback(() => {
    if (message.content) setReasoningOpen(o => !o)
  }, [message.content])

  const hasReasoning = !!(message.reasoning || message.thinking)

  return (
    <div className={`message-row ${message.role}`} id={`msg-${message.id}`}>
      <div className="avatar">
        {message.role === 'user' ? '👤' : '🐉'}
      </div>

      <div className="message-body">
        {/* 思考过程区域 */}
        {hasReasoning && (
          <div className={`reasoning${!reasoningOpen && message.content ? ' collapsed' : ''}`}>
            <div className="reasoning-header" onClick={toggle}>
              {message.thinking && !message.reasoning && <span className="dot-pulse" />}
              <svg className="reasoning-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M12 2a7 7 0 0 1 7 7c0 2.4-1.2 4.5-3 5.7V17a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2v-2.3c-1.8-1.2-3-3.3-3-5.7a7 7 0 0 1 7-7z" />
                <path d="M9 21h6" />
              </svg>
              <span>{message.thinking && !message.reasoning ? '思考中...' : '思考过程'}</span>
              {message.content && <span className="toggle">▼</span>}
            </div>
            <div className="reasoning-content">
              {message.reasoning && (
                <div className="reasoning-markdown">
                  <MarkdownRenderer>
                    {message.reasoning}
                  </MarkdownRenderer>
                </div>
              )}
            </div>
          </div>
        )}

        {/* 正文区域 */}
        {message.content && (
          <div className="markdown-body">
            <MarkdownRenderer>
              {message.content}
            </MarkdownRenderer>
          </div>
        )}

        {/* 尚未收到任何 token 的纯等待状态 */}
        {message.thinking && !message.reasoning && !message.content && (
          <span className="dot-pulse" />
        )}
      </div>
    </div>
  )
}
