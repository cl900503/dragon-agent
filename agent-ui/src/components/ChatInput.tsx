/**
 * 聊天输入框组件——Enter 发送、Shift+Enter 换行、RAG 开关。
 *
 * @author 陈龙
 * @since 2026-05-31
 */

import { useRef, useState, type KeyboardEvent } from 'react'
import './ChatInput.css'

interface Props {
  streaming: boolean
  onSend: (msg: string) => void
  onStop: () => void
  /** RAG 知识库开关 */
  ragEnabled: boolean
  onRagToggle: (v: boolean) => void
}

export default function ChatInput({ streaming, onSend, onStop, ragEnabled, onRagToggle }: Props) {
  const [text, setText] = useState('')
  const ref = useRef<HTMLTextAreaElement>(null)

  function send() {
    const msg = text.trim()
    if (!msg || streaming) return
    onSend(msg)
    setText('')
    requestAnimationFrame(() => { if (ref.current) ref.current.style.height = '' })
  }

  function onKeyDown(e: KeyboardEvent) {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() }
  }

  function autoResize() {
    const el = ref.current; if (!el) return
    el.style.height = 'auto'; void el.offsetHeight
    const style = getComputedStyle(el)
    const lineH = parseFloat(style.lineHeight)
    const maxH = lineH * 10 + parseFloat(style.paddingTop) + parseFloat(style.paddingBottom)
    el.style.height = (el.scrollHeight > maxH ? maxH : el.scrollHeight) + 'px'
  }

  return (
    <div className="input-area">
      <div className="input-wrapper">
        <textarea
          ref={ref} value={text} rows={1}
          placeholder="输入消息... (Enter 发送，Shift+Enter 换行)"
          disabled={streaming}
          onChange={e => { setText(e.target.value); autoResize() }}
          onKeyDown={onKeyDown}
        />
        <div className="input-toolbar">
          <label className="rag-toggle" title="启用知识库检索">
            <input type="checkbox" checked={ragEnabled} onChange={e => onRagToggle(e.target.checked)} />
            <span className="rag-toggle-label">📚 知识库</span>
          </label>
          {streaming ? (
            <button className="stop-btn" onClick={onStop} title="停止生成">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
                <rect x="4" y="4" width="16" height="16" rx="2" />
              </svg>
            </button>
          ) : (
            <button className="send-btn" disabled={!text.trim()} onClick={send} title="发送">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <line x1="22" y1="2" x2="11" y2="13" />
                <polygon points="22 2 15 22 11 13 2 9 22 2" />
              </svg>
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
