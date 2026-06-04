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
 * DeepSeek 思考过程展开 / 折叠、RAG 检索来源详情展示。
 *
 * @author 陈龙
 * @since 2026-05-31
 */
// 会话级反馈记忆——避免切换页面后重复反馈
const feedbackCache = new Map<string, string>()

export default function MessageBubble({ message }: Props) {
  const [reasoningOpen, setReasoningOpen] = useState(true)
  const [traceOpen, setTraceOpen] = useState(false)
  const [feedback, setFeedback] = useState<string | null>(() => feedbackCache.get(message.id) || null)

  const submitFeedback = async (rating: string) => {
    try {
      const r = await fetch('/api/rag/feedback', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ messageId: message.id, rating })
      })
      if (r.ok) { feedbackCache.set(message.id, rating); setFeedback(rating) }
      else if (r.status === 409) { feedbackCache.set(message.id, 'done'); setFeedback('done') }
    } catch { /* */ }
  }

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

        {/* RAG 检索来源 —— 文档名列表 + 可展开查看片段和分数 */}
        {message.role === 'assistant'
          && message.retrievalTraces
          && message.retrievalTraces.length > 0
          && (() => {
            const traces = message.retrievalTraces!
            // 按文档名去重并分组
            const byDoc = new Map<string, typeof traces>()
            traces.forEach(t => {
              if (!byDoc.has(t.documentName)) byDoc.set(t.documentName, [])
              byDoc.get(t.documentName)!.push(t)
            })
            const docNames = [...byDoc.keys()]
            if (docNames.length === 0) return null
            return (
              <div className="citation-section">
                <div className="citation-header" onClick={() => setTraceOpen(o => !o)}>
                  <span className="citation-label">📚 本地知识库 · {docNames.length} 篇文档 · {traces.length} 个片段</span>
                  <span className="citation-toggle">{traceOpen ? '▲' : '▼'}</span>
                </div>
                {traceOpen ? (
                  <div className="citation-detail">
                    {docNames.map(name => {
                      const chunks = byDoc.get(name)!
                      return (
                        <div key={name} className="citation-doc-card">
                          <div className="citation-doc-name">
                            <span className="citation-doc-icon">📄</span>
                            <span>{name}</span>
                            <span className="citation-doc-count">{chunks.length} 个片段</span>
                          </div>
                          {chunks.map((t, i) => (
                            <div key={i} className="citation-chunk">
                              <div className="citation-chunk-meta">
                                <span className="citation-chunk-idx">片段 #{t.chunkIndex}</span>
                                {t.score != null && (
                                  <span className="citation-chunk-score">相似度 {(t.score * 100).toFixed(1)}%</span>
                                )}
                              </div>
                              <div className="citation-chunk-text">{t.contentSnippet}</div>
                            </div>
                          ))}
                        </div>
                      )
                    })}
                  </div>
                ) : (
                  <span className="citation-summary">
                    {docNames.map((name, i) => (
                      <span key={name}>
                        <span className="citation-doc">{name}</span>
                        {i < docNames.length - 1 && <span className="citation-sep">、</span>}
                      </span>
                    ))}
                  </span>
                )}
              </div>
            )
          })()}

        {/* 检索反馈 */}
        {message.role === 'assistant' && message.content && message.retrievalTraces && message.retrievalTraces.length > 0 && (
          <div className="feedback-row">
            <span className="feedback-label">检索质量如何？</span>
            {feedback ? (
              <span className="feedback-done">{feedback === 'USEFUL' ? '👍 感谢反馈' : '👎 感谢反馈'}</span>
            ) : (
              <>
                <button className="feedback-btn" onClick={() => submitFeedback('USEFUL')} title="有用">👍 有用</button>
                <button className="feedback-btn" onClick={() => submitFeedback('USELESS')} title="无用">👎 无用</button>
              </>
            )}
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
