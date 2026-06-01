/**
 * 代码块组件——使用 shiki 进行语法高亮，支持一键复制。
 *
 * @author 陈龙
 * @since 2026-05-31
 */

import { useEffect, useState, useRef, useCallback } from 'react'
import { codeToHtml } from 'shiki'
import './CodeBlock.css'

interface Props {
  code: string
  lang: string
}

export default function CodeBlock({ code, lang }: Props) {
  const [html, setHtml] = useState('')
  const [copied, setCopied] = useState(false)
  const copyTimer = useRef<ReturnType<typeof setTimeout>>(null)

  // 用 shiki 高亮代码，cancelled 标记防止卸载后的 setState
  useEffect(() => {
    let cancelled = false
    codeToHtml(code, {
      lang: lang || 'plaintext',
      theme: 'github-light-default',
    })
      .then((h) => {
        if (!cancelled) setHtml(h)
      })
      .catch(() => {
        // 高亮失败时静默回退，组件会渲染原始 <pre><code>
      })
    return () => {
      cancelled = true
    }
  }, [code, lang])

  // 复制按钮，2 秒后恢复文案，卸载时清理定时器
  const copy = useCallback(() => {
    navigator.clipboard
      .writeText(code)
      .then(() => {
        setCopied(true)
        if (copyTimer.current) clearTimeout(copyTimer.current)
        copyTimer.current = setTimeout(() => setCopied(false), 2000)
      })
      .catch(() => {
        // 剪贴板写入失败（如非 HTTPS 环境），静默忽略
      })
  }, [code])

  useEffect(() => {
    return () => {
      if (copyTimer.current) clearTimeout(copyTimer.current)
    }
  }, [])

  return (
    <div className="code-block-wrapper">
      <div className="code-block-header">
        <span>{lang || 'text'}</span>
        <button className={`copy-btn${copied ? ' copied' : ''}`} onClick={copy}>
          {copied ? (
            <>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <polyline points="20 6 9 17 4 12" />
              </svg>
              已复制
            </>
          ) : (
            <>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
                <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
              </svg>
              复制
            </>
          )}
        </button>
      </div>
      {html ? (
        <div className="shiki-wrapper" dangerouslySetInnerHTML={{ __html: html }} />
      ) : (
        <div className="shiki-wrapper">
          <pre><code>{code}</code></pre>
        </div>
      )}
    </div>
  )
}
