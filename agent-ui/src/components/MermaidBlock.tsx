/**
 * Mermaid 图表渲染组件——异步调用 mermaid.render() 将 DSL 转为 SVG。
 *
 * 使用 cancelled 标记防止组件卸载后的竞态更新。
 * 渲染失败时回退显示原始代码。
 *
 * @author 陈龙
 * @since 2026-05-31
 */

import { useEffect, useState } from 'react'
import mermaid from 'mermaid'
import './MermaidBlock.css'

interface Props {
  code: string
  /** 可选 ID 前缀，避免多实例间的 SVG ID 冲突 */
  idPrefix?: string
}

export default function MermaidBlock({ code, idPrefix = 'mermaid' }: Props) {
  const [svg, setSvg] = useState('')
  const [renderError, setRenderError] = useState(false)

  useEffect(() => {
    let cancelled = false
    const id = `${idPrefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    mermaid
      .render(id, code)
      .then(({ svg: s }) => {
        if (!cancelled) setSvg(s)
      })
      .catch(() => {
        if (!cancelled) setRenderError(true)
      })
    return () => {
      cancelled = true
    }
  }, [code, idPrefix])

  if (renderError) {
    return (
      <div className="mermaid-error">
        <code>{code}</code>
      </div>
    )
  }

  return (
    <div className="mermaid-wrapper" dangerouslySetInnerHTML={{ __html: svg }} />
  )
}
