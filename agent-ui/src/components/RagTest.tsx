/**
 * RAG 检索调试面板——单条查询，展示完整检索链路的调试信息。
 *
 * @author 陈龙
 * @since 2026-06-02
 */

import React, { useState, useRef } from 'react'
import './RagTest.css'

interface TraceItem {
  documentName: string
  chunkIndex: number
  score: number
  contentSnippet: string
}

export default function RagTest() {
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(false)
  const [traces, setTraces] = useState<TraceItem[] | null>(null)
  const [context, setContext] = useState<string | null>(null)
  const [rawJson, setRawJson] = useState<string | null>(null)
  const [elapsed, setElapsed] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [expandedChunks, setExpandedChunks] = useState<Set<number>>(new Set())
  const inputRef = useRef<HTMLInputElement>(null)

  const search = async () => {
    const q = query.trim()
    if (!q) return
    setLoading(true)
    setError(null)
    setTraces(null)
    setContext(null)
    setRawJson(null)
    setElapsed(null)

    const start = performance.now()
    try {
      const res = await fetch('/api/documents/test-retrieval', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query: q }),
      })
      const data = await res.json()
      const ms = performance.now() - start

      if (!res.ok) {
        setError(data.error || `HTTP ${res.status}`)
        return
      }

      setTraces(data.traces || [])
      setContext(data.context || null)
      setRawJson(JSON.stringify(data, null, 2))
      setElapsed(ms)
    } catch (e) {
      setError(e instanceof Error ? e.message : '请求失败')
    } finally {
      setLoading(false)
    }
  }

  const toggleChunk = (i: number) => {
    setExpandedChunks(prev => {
      const next = new Set(prev)
      next.has(i) ? next.delete(i) : next.add(i)
      return next
    })
  }

  const maxScore = traces && traces.length > 0 ? Math.max(...traces.map(t => t.score)) : 0

  return (
    <div className="ragtest-page">
      <h2>语义检索调试</h2>

      {/* 搜索栏 */}
      <div className="rt-search-bar">
        <input
          ref={inputRef}
          className="rt-search-input"
          value={query}
          onChange={e => setQuery(e.target.value)}
          onKeyDown={e => e.key === 'Enter' && search()}
          placeholder="输入查询文本，测试 Milvus + BGE-M3 检索效果..."
          autoFocus
        />
        <button className="rt-search-btn" onClick={search} disabled={loading || !query.trim()}>
          {loading ? '检索中...' : '检索'}
        </button>
      </div>

      {error && <div className="rt-error">{error}</div>}

      {/* 结果概览 */}
      {traces !== null && (
        <div className="rt-summary">
          <span className={`rt-badge ${traces.length > 0 ? 'rt-hit' : 'rt-miss'}`}>
            {traces.length > 0 ? `命中 ${traces.length} 个 chunk` : '未命中'}
          </span>
          {elapsed !== null && (
            <span className="rt-elapsed">{elapsed.toFixed(0)}ms</span>
          )}
        </div>
      )}

      {/* 分数分布 */}
      {traces && traces.length > 0 && (
        <div className="rt-score-bar">
          {traces.map((t, i) => (
            <div
              key={i}
              className="rt-score-seg"
              style={{
                flex: t.score,
                opacity: 0.3 + (t.score / maxScore) * 0.7,
              }}
              title={`#${i + 1} ${t.documentName} score=${t.score.toFixed(4)}`}
            />
          ))}
          <span className="rt-score-label">分数分布</span>
        </div>
      )}

      {/* 检索结果列表 */}
      {traces && traces.length > 0 && (
        <section className="rt-section">
          <h3>检索结果 ({traces.length} chunks)</h3>
          <div className="rt-chunks">
            {traces.map((t, i) => (
              <div key={i} className={`rt-chunk ${t.score === maxScore ? 'rt-chunk-top' : ''}`}>
                <div className="rt-chunk-header" onClick={() => toggleChunk(i)}>
                  <span className="rt-chunk-num">#{i + 1}</span>
                  <span className="rt-chunk-doc">{t.documentName}</span>
                  <span className="rt-chunk-idx">chunk {t.chunkIndex}</span>
                  <span className={`rt-score ${t.score > 0.5 ? 'rt-score-hi' : t.score > 0.2 ? 'rt-score-md' : 'rt-score-lo'}`}>
                    {t.score.toFixed(4)}
                  </span>
                  <span className="rt-chunk-toggle">{expandedChunks.has(i) ? '▲' : '▼'}</span>
                </div>
                {expandedChunks.has(i) && (
                  <div className="rt-chunk-body">
                    <div className="rt-chunk-meta">
                      <span>文档: {t.documentName}</span>
                      <span>chunk #{t.chunkIndex}</span>
                      <span>分数: {t.score.toFixed(6)}</span>
                    </div>
                    <pre className="rt-chunk-text">{t.contentSnippet}</pre>
                  </div>
                )}
              </div>
            ))}
          </div>
        </section>
      )}

      {/* LLM 上下文 */}
      {context && (
        <section className="rt-section">
          <h3>发送给 LLM 的上下文</h3>
          <pre className="rt-context">{context}</pre>
        </section>
      )}

      {/* 原始 JSON */}
      {rawJson && (
        <section className="rt-section">
          <details>
            <summary className="rt-raw-toggle">原始 JSON 响应</summary>
            <pre className="rt-raw">{rawJson}</pre>
          </details>
        </section>
      )}
    </div>
  )
}
