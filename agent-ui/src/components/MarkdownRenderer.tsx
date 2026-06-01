/**
 * Markdown 渲染组件——封装 ReactMarkdown 的公共配置。
 *
 * MessageBubble 和 MarkdownTest 共享相同的 remark/rehype 插件链，
 * 仅在 sanitize 白名单和 Mermaid ID 前缀上有差异。
 *
 * 注意：本组件不添加任何包裹 div，由调用方自行管理容器样式。
 *
 * @author 陈龙
 * @since 2026-06-01
 */

import { useMemo } from 'react'
import ReactMarkdown from 'react-markdown'
import type { Components } from 'react-markdown'
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import rehypeKatex from 'rehype-katex'
import rehypeRaw from 'rehype-raw'
import rehypeSanitize, { defaultSchema } from 'rehype-sanitize'
import CodeBlock from './CodeBlock'
import MermaidBlock from './MermaidBlock'

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Schema = any

interface Props {
  children: string
  sanitizeSchema?: Schema
  mermaidIdPrefix?: string
}

/** 默认白名单：放行 span[style]，用于 HTML 着色文本 */
export const defaultSafeSchema = {
  ...defaultSchema,
  attributes: {
    ...defaultSchema.attributes,
    span: [...(defaultSchema.attributes?.span || []), ['style']],
  },
}

export default function MarkdownRenderer({
  children,
  sanitizeSchema,
  mermaidIdPrefix,
}: Props) {
  const schema = sanitizeSchema || defaultSafeSchema

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const rehypePlugins: any[] = useMemo(
    () => [rehypeKatex, rehypeRaw, [rehypeSanitize, schema]],
    [schema],
  )

  const components: Components = useMemo(
    () => ({
      code({ className, children: codeText, ...props }) {
        const match = /language-(\w+)/.exec(className || '')
        const code = String(codeText).replace(/\n$/, '')
        if (match) {
          if (match[1] === 'mermaid') {
            return <MermaidBlock code={code} idPrefix={mermaidIdPrefix} />
          }
          return <CodeBlock code={code} lang={match[1]} />
        }
        return <code className={className} {...props}>{codeText}</code>
      },
      a({ href, children: linkText, ...props }) {
        return (
          <a href={href} target="_blank" rel="noopener noreferrer" {...props}>
            {linkText}
          </a>
        )
      },
    }),
    [mermaidIdPrefix],
  )

  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm, remarkMath]}
      remarkRehypeOptions={{ allowDangerousHtml: true }}
      rehypePlugins={rehypePlugins}
      components={components}
    >
      {children}
    </ReactMarkdown>
  )
}
