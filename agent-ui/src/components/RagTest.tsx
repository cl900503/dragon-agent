/**
 * RAG 管线调试面板——逐步展示 5 步检索管线的中间结果。
 *
 * @author 陈龙
 * @since 2026-06-02
 */

import React, { useState, useRef } from 'react'
import './RagTest.css'

interface DebugStep {
  step: number
  name: string
  icon: string
  durationMs: number
  status: string        // "success" | "warning" | "error" | "empty" | "info"
  summary: string
  detail: Record<string, unknown>
}

interface TraceItem {
  documentName: string
  chunkIndex: number
  score: number
  contentSnippet: string
}

const KEY_LABELS: Record<string, string> = {
  intent: '意图分类',
  intentDesc: '分类说明',
  rewritten: '已改写',
  rewriteTimedOut: '改写超时',
  llmCalled: '已调用LLM',
  variants: '改写变体',
  note: '备注',
  filterExpr: '过滤表达式',
  denseVectorDim: '向量维度',
  sparseAvailable: '稀疏向量可用',
  fusionMethod: '融合方式',
  candidatesAfterFusion: '融合后候选数',
  topKCandidates: 'TopK候选',
  topScoreAfterFusion: '融合后最高分',
  candidatesBeforeRerank: '重排前候选数',
  crossEncoderModel: 'Cross-Encoder模型',
  afterCrossEncoder: '重排后数量',
  mmrEnabled: 'MMR去重',
  mmrLambda: 'MMR多样性参数',
  topRerankScore: '重排最高分',
  threshold: '相似度阈值',
  beforeFilter: '过滤前数量',
  afterFilter: '过滤后数量',
  removedCount: '过滤移除数',
  fallback: '兜底策略',
  lostInMiddle: 'Lost-in-Middle重排',
  contentDedupApplied: '内容去重',
  uniqueDocuments: '去重后文档数',
  contextLength: '上下文字符数',
}

const STATUS_TAG: Record<string, { label: string; cls: string }> = {
  success:  { label: '成功', cls: 'rt-tag-ok' },
  filtered: { label: '成功', cls: 'rt-tag-ok' },
  warning:  { label: '超时', cls: 'rt-tag-warn' },
  error:    { label: '失败', cls: 'rt-tag-err' },
  empty:    { label: '无结果', cls: 'rt-tag-empty' },
  info:     { label: '跳过', cls: 'rt-tag-info' },
}

export default function RagTest() {
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(false)
  const [steps, setSteps] = useState<DebugStep[]>([])
  const [finalTraces, setFinalTraces] = useState<TraceItem[]>([])
  const [finalContext, setFinalContext] = useState('')
  const [finalCount, setFinalCount] = useState(0)
  const [totalMs, setTotalMs] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [expanded, setExpanded] = useState<Set<number>>(new Set())
  const [expandedChunks, setExpandedChunks] = useState<Set<number>>(new Set())
  const inputRef = useRef<HTMLInputElement>(null)

  const search = () => {
    const q = query.trim()
    if (!q) return
    setLoading(true)
    setError(null)
    setSteps([])
    setFinalTraces([])
    setFinalContext('')
    setFinalCount(0)
    setTotalMs(0)

    fetch('/api/rag/debug/start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ query: q }),
    }).then(async (res) => {
      const startData = await res.json()
      if (!res.ok) { setError(startData.error); setLoading(false); return }
      const sid = startData.sessionId

      let seen = 0
      const poll = async () => {
        const r = await fetch(`/api/rag/debug/poll?sid=${encodeURIComponent(sid)}`)
        const d = await r.json()
        if (d.error) { setError(d.error); setLoading(false); return }
        const ns = (d.steps || []) as DebugStep[]
        if (ns.length > seen) {
          for (let i = seen; i < ns.length; i++) setSteps(prev => [...prev, ns[i]])
          seen = ns.length
        }
        if (d.done) {
          setFinalTraces(d.finalTraces || [])
          setFinalContext(d.finalContext || '')
          setFinalCount(d.finalCount || 0)
          setTotalMs(d.totalMs || 0)
          setLoading(false)
        } else {
          setTimeout(poll, 150)
        }
      }
      poll()
    }).catch(e => {
      setError(e instanceof Error ? e.message : '请求失败')
      setLoading(false)
    })
  }

  const toggleStep = (i: number) => {
    setExpanded(prev => {
      const next = new Set(prev)
      next.has(i) ? next.delete(i) : next.add(i)
      return next
    })
  }

  const toggleChunk = (i: number) => {
    setExpandedChunks(prev => {
      const next = new Set(prev)
      next.has(i) ? next.delete(i) : next.add(i)
      return next
    })
  }

  const maxScore = finalTraces.length > 0 ? Math.max(...finalTraces.map(t => t.score)) : 0
  const hasResult = steps.length > 0 || finalTraces.length > 0 || loading

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
          placeholder="输入查询文本，查看完整 RAG 管线执行过程..."
          autoFocus
        />
        <button className="rt-search-btn" onClick={search} disabled={loading || !query.trim()}>
          {loading ? '检索中...' : '检索'}
        </button>
      </div>

      {error && <div className="rt-error">{error}</div>}

      {/* 管线可视化 */}
      {hasResult && (
        <div className="rt-pipeline">
          {/* 总耗时 */}
          <div className="rt-total-bar">
            <span className="rt-total-label">查询: "{query.trim()}"</span>
            <span className="rt-total-ms">{totalMs > 0 ? `总耗时 ${totalMs}ms` : '执行中...'}</span>
          </div>

          {/* 步骤列表 */}
          <div className="rt-steps">
            {steps.map((s, i) => (
              <div key={i} className="rt-step-wrap">
                {i < steps.length - 1 && (
                  <div className={`rt-connector ${s.status === 'error' || s.status === 'empty' ? 'rt-conn-dim' : ''}`} />
                )}
                <div
                  className={`rt-step-card ${s.status === 'error' ? 'rt-step-err' : s.status === 'warning' ? 'rt-step-warn' : s.status === 'empty' ? 'rt-step-empty' : ''}`}
                  onClick={() => toggleStep(i)}
                >
                  <div className="rt-step-header">
                    <span className="rt-step-num">{s.step}</span>
                    <span className="rt-step-icon">{s.icon}</span>
                    <span className="rt-step-name">{s.name}</span>
                    <span className={`rt-step-status-tag ${STATUS_TAG[s.status]?.cls || ''}`}>
                      {STATUS_TAG[s.status]?.label || s.status}
                    </span>
                    <span className="rt-step-summary">{s.summary}</span>
                    <span className="rt-step-ms">{s.durationMs}ms</span>
                    <span className="rt-step-toggle">{expanded.has(i) ? '▲' : '▼'}</span>
                  </div>
                  {expanded.has(i) && (
                    <div className="rt-step-detail">
                      {Object.entries(s.detail).map(([k, v]) => (
                        <div key={k} className="rt-detail-row">
                          <span className="rt-detail-key">{KEY_LABELS[k] || k}</span>
                          <span className="rt-detail-val">
                            {Array.isArray(v) ? v.join(', ') : typeof v === 'boolean' ? v ? '是' : '否' : String(v)}
                          </span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            ))}
            {/* 加载指示器 */}
            {loading && steps.length < 5 && (
              <div className="rt-step-wrap">
                {steps.length > 0 && <div className="rt-connector" />}
                <div className="rt-step-card rt-step-running">
                  <div className="rt-step-loader">
                    <span className="rt-step-icon">⏳</span>
                    <span>正在执行第 {steps.length + 1} 步</span>
                    <span className="rt-loader-dot" />
                    <span className="rt-loader-dot" />
                    <span className="rt-loader-dot" />
                  </div>
                </div>
              </div>
            )}
          </div>

          {/* 管线输出分隔 */}
          {(finalTraces.length > 0 || finalContext) && (
            <div className="rt-output-zone">
              <div className="rt-output-divider">
                <span className="rt-output-dline" />
                <span className="rt-output-dlabel">输出</span>
                <span className="rt-output-dline" />
              </div>
            </div>
          )}

          {/* 检索结果 */}
          {finalTraces.length > 0 && (
            <div className="rt-output-card">
              <div className="rt-output-head">
                <span className="rt-output-icon">📋</span>
                <span className="rt-output-title">检索结果</span>
                <span className="rt-output-badge">{finalCount} 个片段</span>
              </div>
              <div className="rt-chunks">
                {finalTraces.map((t, i) => (
                  <div key={i} className={`rt-chunk ${t.score === maxScore ? 'rt-chunk-top' : ''}`}>
                    <div className="rt-chunk-header" onClick={() => toggleChunk(i)}>
                      <span className="rt-chunk-num">#{i + 1}</span>
                      <span className="rt-chunk-doc">{t.documentName}</span>
                      <span className="rt-chunk-idx">chunk {t.chunkIndex}</span>
                      <span className={`rt-score ${t.score > 0.5 ? 'rt-score-hi' : t.score > 0.2 ? 'rt-score-md' : 'rt-score-lo'}`}>
                        {(t.score * 100).toFixed(1)}%
                      </span>
                      <span className="rt-chunk-toggle">{expandedChunks.has(i) ? '▲' : '▼'}</span>
                    </div>
                    {expandedChunks.has(i) && (
                      <div className="rt-chunk-body"><pre className="rt-content">{t.contentSnippet}</pre></div>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* LLM 上下文 */}
          {finalContext && (
            <div className="rt-output-card">
              <div className="rt-output-head">
                <span className="rt-output-icon">📄</span>
                <span className="rt-output-title">发送给 LLM 的上下文</span>
                <span className="rt-output-badge">{finalContext.length} 字符</span>
              </div>
              <pre className="rt-content">{finalContext}</pre>
            </div>
          )}
        </div>
      )}

      {/* 空状态 */}
      {!hasResult && !error && (
        <div className="rt-empty-state">
          <div className="rt-empty-icon">🔍</div>
          <p>输入查询文本，查看 RAG 检索管线的完整执行过程</p>
          <div className="rt-preview-pipeline">
            {[
              { icon: '🔄', name: '查询改写', desc: '意图分类 + LLM 改写变体' },
              { icon: '🔎', name: '多路检索', desc: 'Dense + Sparse + BM25 → RRF 融合' },
              { icon: '📊', name: '重排序', desc: 'Cross-Encoder + MMR 多样性去重' },
              { icon: '✂️', name: '阈值过滤', desc: '相似度阈值过滤低分文档' },
              { icon: '📝', name: '上下文构建', desc: 'Lost-in-Middle 重排 + 结构化引用' },
            ].map((s, i) => (
              <div key={i} className="rt-preview-step">
                <span className="rt-preview-icon">{s.icon}</span>
                <span className="rt-preview-name">{s.name}</span>
                <span className="rt-preview-desc">{s.desc}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
