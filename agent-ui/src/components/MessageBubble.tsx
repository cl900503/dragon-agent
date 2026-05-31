import { useState, useCallback, useEffect, useDeferredValue } from 'react'
import ReactMarkdown from 'react-markdown'
import type { Components } from 'react-markdown'
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import rehypeKatex from 'rehype-katex'
import rehypeRaw from 'rehype-raw'
import rehypeSanitize, { defaultSchema } from 'rehype-sanitize'
import mermaid from 'mermaid'
import CodeBlock from './CodeBlock'
import type { Message } from '../types'
import './MessageBubble.css'

// 模块加载时初始化 mermaid，全局生效一次即可
mermaid.initialize({ startOnLoad: false, theme: 'default' })

interface Props {
  message: Message
  /** 流式输出期间为 true，启用 useDeferredValue 降低渲染频率 */
  isStreaming?: boolean
}

// rehype-sanitize 白名单：额外放行 span[style]，用于 HTML 着色文本
const safeSchema = {
  ...defaultSchema,
  attributes: {
    ...defaultSchema.attributes,
    span: [...(defaultSchema.attributes?.span || []), ['style']],
  },
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const rehypePlugins: any = [rehypeKatex, rehypeRaw, [rehypeSanitize, safeSchema]]

/**
 * Mermaid 图表组件。
 * 异步调用 mermaid.render() 将 DSL 转为 SVG，用 cancelled 标记防止竞态。
 * 渲染失败时回退显示原始代码。
 */
function MermaidBlock({ code }: { code: string }) {
  const [svg, setSvg] = useState('')
  const [renderError, setRenderError] = useState(false)

  useEffect(() => {
    let cancelled = false
    mermaid
      .render(
        `mermaid-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
        code,
      )
      .then(({ svg: s }) => {
        if (!cancelled) setSvg(s)
      })
      .catch(() => {
        if (!cancelled) setRenderError(true)
      })
    return () => {
      cancelled = true
    }
  }, [code])

  if (renderError) {
    return (
      <div className="code-block-wrapper">
        <div className="code-block-header"><span>mermaid</span></div>
        <pre><code>{code}</code></pre>
      </div>
    )
  }

  return (
    <div className="mermaid-wrapper" dangerouslySetInnerHTML={{ __html: svg }} />
  )
}

// react-markdown 自定义渲染：代码块按语言分发，链接统一加 target="_blank"
const mdComponents: Components = {
  code({ className, children, ...props }) {
    const match = /language-(\w+)/.exec(className || '')
    const code = String(children).replace(/\n$/, '')
    if (match) {
      if (match[1] === 'mermaid') return <MermaidBlock code={code} />
      return <CodeBlock code={code} lang={match[1]} />
    }
    return <code className={className} {...props}>{children}</code>
  },
  a({ href, children, ...props }) {
    return <a href={href} target="_blank" rel="noopener noreferrer" {...props}>{children}</a>
  },
}

/**
 * 消息气泡组件——渲染单条对话。
 *
 * 支持：
 * - Markdown 实时渲染（GFM + KaTeX 数学公式 + Mermaid 图表）
 * - DeepSeek 思考过程展开 / 折叠
 * - 流式输出时用 useDeferredValue 降低 ReactMarkdown 的重渲染频率，
 *   避免高频 token 更新卡顿 UI
 *
 * @author 陈龙
 * @since 2026-05-31
 */
export default function MessageBubble({ message, isStreaming = false }: Props) {
  // 流式期间用 deferred 值，React 在后台处理，不阻塞交互
  const deferredContent = useDeferredValue(message.content)
  const deferredReasoning = useDeferredValue(message.reasoning)

  const content = isStreaming ? deferredContent : message.content
  const reasoning = isStreaming ? deferredReasoning : message.reasoning

  const [reasoningOpen, setReasoningOpen] = useState(true)

  const toggle = useCallback(() => {
    if (content) setReasoningOpen(o => !o)
  }, [content])

  const hasReasoning = !!(message.reasoning || message.thinking)

  return (
    <div className={`message-row ${message.role}`}>
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
                <div className="markdown-body reasoning-markdown">
                  <ReactMarkdown
                    remarkPlugins={[remarkGfm, remarkMath]}
                    remarkRehypeOptions={{ allowDangerousHtml: true }}
                    rehypePlugins={rehypePlugins}
                    components={mdComponents}
                  >
                    {reasoning}
                  </ReactMarkdown>
                </div>
              )}
            </div>
          </div>
        )}

        {/* 正文区域 */}
        {message.content && (
          <div className="markdown-body">
            <ReactMarkdown
              remarkPlugins={[remarkGfm, remarkMath]}
              remarkRehypeOptions={{ allowDangerousHtml: true }}
              rehypePlugins={rehypePlugins}
              components={mdComponents}
            >
              {content}
            </ReactMarkdown>
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
