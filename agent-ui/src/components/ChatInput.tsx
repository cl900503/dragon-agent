/**
 * 聊天输入框组件。
 *
 * 支持 Enter 发送、Shift+Enter 换行、自动调整高度（最大 10 行）。
 *
 * @author 陈龙
 * @since 2026-05-31
 */

import { useRef, useState, type KeyboardEvent } from 'react'
import './ChatInput.css'

interface Props {
  /** 是否正在流式生成（同时控制禁用态和显示停止按钮） */
  streaming: boolean
  onSend: (msg: string) => void
  onStop: () => void
}

export default function ChatInput({ streaming, onSend, onStop }: Props) {
  const [text, setText] = useState('')
  const ref = useRef<HTMLTextAreaElement>(null)

  function send() {
    const msg = text.trim()
    if (!msg || streaming) return
    onSend(msg)
    setText('')
    // 发送后重置 textarea 高度
    requestAnimationFrame(() => {
      if (ref.current) ref.current.style.height = ''
    })
  }

  // Enter 发送，Shift+Enter 换行
  function onKeyDown(e: KeyboardEvent) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      send()
    }
  }

  // 根据内容自动调整高度，最大 10 行
  function autoResize() {
    const el = ref.current
    if (!el) return

    el.style.height = 'auto'
    void el.offsetHeight // 强制回流以获取准确的 scrollHeight

    const style = getComputedStyle(el)
    const lineH = parseFloat(style.lineHeight)
    const padTop = parseFloat(style.paddingTop)
    const padBot = parseFloat(style.paddingBottom)
    const maxH = lineH * 10 + padTop + padBot

    el.style.height = (el.scrollHeight > maxH ? maxH : el.scrollHeight) + 'px'
  }

  return (
    <div className="input-area">
      <div className="input-wrapper">
        <textarea
          ref={ref}
          value={text}
          rows={1}
          placeholder="输入消息... (Enter 发送，Shift+Enter 换行)"
          disabled={streaming}
          onChange={e => { setText(e.target.value); autoResize() }}
          onKeyDown={onKeyDown}
        />
        <div className="input-toolbar">
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
