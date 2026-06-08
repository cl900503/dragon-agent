/**
 * RAG 检索质量仪表盘——展示最近 30 天的检索统计指标和最近检索记录。
 *
 * @author 陈龙
 * @since 2026-06-04
 */

import { useState, useEffect } from 'react'
import './RagTest.css'

export default function RagDashboard() {
  const [stats, setStats] = useState<any>(null)
  const [recent, setRecent] = useState<any[]>([])

  useEffect(() => {
    fetch('/api/rag/stats').then(r => r.json()).then(setStats).catch(() => {})
    fetch('/api/rag/recent').then(r => r.json()).then(setRecent).catch(() => {})
  }, [])

  return (
    <div className="ragtest-page">
      <h2>检索质量分析</h2>

      <section className="rt-section">
        <h3>概览（最近 30 天）</h3>
        {stats ? (
          <div className="rt-stats-grid">
            <div className="rt-stat-card">
              <div className="rt-stat-val">{stats.totalSearches || 0}</div>
              <div className="rt-stat-lbl">总检索次数</div>
            </div>
            <div className="rt-stat-card">
              <div className="rt-stat-val">{stats.avgTopScore ? Number(stats.avgTopScore).toFixed(3) : '-'}</div>
              <div className="rt-stat-lbl">平均最高相似度</div>
            </div>
            <div className="rt-stat-card">
              <div className="rt-stat-val">{stats.avgDurationMs ? Math.round(stats.avgDurationMs) + 'ms' : '-'}</div>
              <div className="rt-stat-lbl">平均检索耗时</div>
            </div>
            <div className="rt-stat-card">
              <div className="rt-stat-val">{stats.feedbackRate || 'N/A'}</div>
              <div className="rt-stat-lbl">用户有用反馈率</div>
            </div>
            <div className="rt-stat-card">
              <div className="rt-stat-val">{stats.missCount || 0}</div>
              <div className="rt-stat-lbl">零结果次数</div>
            </div>
          </div>
        ) : <p className="rt-empty">加载中...</p>}
      </section>

      {recent.length > 0 && (
        <section className="rt-section">
          <h3>最近检索记录</h3>
          <div className="rt-recent-scroll">
            <table className="rt-recent-table">
              <thead><tr><th>查询</th><th>命中</th><th>结果</th><th>最高分</th><th>耗时</th></tr></thead>
              <tbody>
                {recent.map((r: any) => (
                  <tr key={r.id}>
                    <td className="rt-recent-q" title={r.query}>{r.query?.slice(0, 50)}{r.query?.length > 50 ? '...' : ''}</td>
                    <td>{r.hit ? '✅' : '❌'}</td>
                    <td>{r.resultCount}</td>
                    <td>{r.topScore?.toFixed(3)}</td>
                    <td>{r.durationMs}ms</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  )
}
