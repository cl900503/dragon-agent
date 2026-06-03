/**
 * 知识库管理——上传、搜索、分页、排序、批量管理文档。
 *
 * @author 陈龙
 * @since 2026-06-01
 */

import { useState, useRef, useCallback, useMemo, type DragEvent, type ChangeEvent } from 'react'
import { uploadFile, deleteDocument, retryDocument, getDocumentDownloadUrl } from '../api'
import type { UploadedDocument } from '../types'
import './KnowledgeBase.css'

interface Props {
  documents: UploadedDocument[]
  onDocumentsChange: (docs: UploadedDocument[]) => void
}

const PAGE_SIZE = 15
const TYPE_FILTERS = [
  { key: '', label: '全部' },
  { key: 'pdf', label: 'PDF' },
  { key: 'word', label: 'Word' },
  { key: 'sheet', label: 'Excel' },
  { key: 'presentation', label: 'PPT' },
  { key: 'text', label: '文本' },
]

function formatSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}
function fmtDate(iso: string): string {
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function statusText(s: string) {
  const m: Record<string, string> = { READY: '就绪', FAILED: '失败', INDEXING: '索引中', PARSING: '解析中', UPLOADING: '上传中' }
  return m[s] || s
}
function typeMatch(mime: string, filter: string): boolean {
  if (!filter) return true
  return mime.toLowerCase().includes(filter.toLowerCase())
}

export default function KnowledgeBase({ documents, onDocumentsChange }: Props) {
  const [dragOver, setDragOver] = useState(false)
  const [uploading, setUploading] = useState<{ name: string; done: boolean; error?: string }[]>([])
  const [error, setError] = useState<string | null>(null)
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [typeFilter, setTypeFilter] = useState('')
  const [sort, setSort] = useState<'name' | 'date' | 'size'>('date')
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const fileInputRef = useRef<HTMLInputElement>(null)

  const handleFiles = useCallback(async (files: FileList) => {
    const tasks = Array.from(files).map(f => ({ name: f.name, done: false }))
    setUploading(tasks)
    const newDocs: UploadedDocument[] = []
    await Promise.all(Array.from(files).map(async (file, i) => {
      try {
        const d = await uploadFile(file)
        newDocs.push(d)
        setUploading(p => p.map((t, j) => j === i ? { ...t, done: true } : t))
      } catch (err) {
        setUploading(p => p.map((t, j) => j === i
          ? { ...t, done: true, error: err instanceof Error ? err.message : '失败' } : t))
      }
    }))
    if (newDocs.length > 0) onDocumentsChange([...newDocs, ...documents])
    setTimeout(() => setUploading([]), 3000)
  }, [documents, onDocumentsChange])

  const onDragOver = (e: DragEvent) => { e.preventDefault(); setDragOver(true) }
  const onDragLeave = (e: DragEvent) => { e.preventDefault(); setDragOver(false) }
  const onDrop = (e: DragEvent) => { e.preventDefault(); setDragOver(false); handleFiles(e.dataTransfer.files) }

  const handleDelete = async (id: string) => {
    onDocumentsChange(documents.filter(d => d.id !== id))
    setSelected(prev => { const n = new Set(prev); n.delete(id); return n })
    try { await deleteDocument(id) } catch { /* ok */ }
  }
  const batchDelete = async () => {
    if (selected.size === 0) return
    onDocumentsChange(documents.filter(d => !selected.has(d.id)))
    for (const id of selected) { try { await deleteDocument(id) } catch { /* ok */ } }
    setSelected(new Set())
  }
  const handleRetry = async (id: string) => {
    try { await retryDocument(id); onDocumentsChange(documents.map(d => d.id === id ? { ...d, status: 'INDEXING' as const } : d))
      setTimeout(() => { import('../api').then(a => a.fetchDocuments().then(onDocumentsChange).catch(() => {})) }, 3000)
    } catch (err) { setError(err instanceof Error ? err.message : '重试失败') }
  }
  const toggleSelect = (id: string) => setSelected(prev => { const n = new Set(prev); n.has(id) ? n.delete(id) : n.add(id); return n })
  const toggleAll = () => {
    if (selected.size === paged.length) setSelected(new Set())
    else setSelected(new Set(paged.map(d => d.id)))
  }

  // 筛选 + 排序 + 分页
  const filtered = useMemo(() => {
    let list = [...documents]
    if (search.trim()) {
      const q = search.trim().toLowerCase()
      list = list.filter(d => d.originalName.toLowerCase().includes(q))
    }
    if (typeFilter) list = list.filter(d => typeMatch(d.mimeType, typeFilter))
    list.sort((a, b) => {
      if (sort === 'name') return a.originalName.localeCompare(b.originalName)
      if (sort === 'size') return b.fileSize - a.fileSize
      return new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
    })
    return list
  }, [documents, search, typeFilter, sort])

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE))
  const safePage = Math.min(page, totalPages - 1)
  const paged = filtered.slice(safePage * PAGE_SIZE, (safePage + 1) * PAGE_SIZE)

  const ready = documents.filter(d => d.status === 'READY').length
  const totalSize = documents.reduce((s, d) => s + d.fileSize, 0)

  return (
    <div className="kb-page">
      <div className="kb-top">
        <h2>知识库</h2>
        {documents.length > 0 && <span className="kb-summary">{ready}/{documents.length} 就绪 · {formatSize(totalSize)}</span>}
      </div>

      {error && <div className="kb-error"><span>{error}</span><button onClick={() => setError(null)}>✕</button></div>}

      {/* 上传区 */}
      <div className={`kb-drop${dragOver ? ' kb-drop-over' : ''}`}
        onDragOver={onDragOver} onDragLeave={onDragLeave} onDrop={onDrop} onClick={() => fileInputRef.current?.click()}>
        <span className="kb-drop-icon">📤</span>
        <span className="kb-drop-text">拖拽文件到此处或点击上传</span>
        <span className="kb-drop-hint">支持 PDF、Word、Excel、PPT、TXT 等格式</span>
        <input ref={fileInputRef} type="file" hidden multiple
          accept=".pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.txt,.md,.csv,.json,.java,.py,.c,.cpp,.js,.ts,.html,.css,.xml,.yaml,.yml,.log"
          onChange={e => { if (e.target.files) { handleFiles(e.target.files); e.target.value = '' } }} />
      </div>

      {/* 上传进度 — 紧跟上传区 */}
      <div className="kb-uploading">
        {uploading.map((t, i) => (
          <div key={i} className={`kb-upload-item${t.done ? (t.error ? ' kb-upload-err' : '') : ''}`}>
            {t.done ? (t.error ? '✕' : '✓') : <span className="kb-spinner" />}
            <span className="kb-upload-name">{t.name}</span>
            {t.error && <span className="kb-upload-msg">{t.error}</span>}
          </div>
        ))}
      </div>

      {documents.length === 0 ? (
        <div className="kb-empty"><span className="kb-empty-icon">📭</span><p>知识库为空，上传文档后 AI 可在对话中检索</p></div>
      ) : (
        <>
          {/* 工具栏 */}
          <div className="kb-toolbar">
            <input className="kb-search" value={search} onChange={e => { setSearch(e.target.value); setPage(0) }}
              placeholder={`搜索 ${documents.length} 个文档...`} />
            <div className="kb-filters">
              {TYPE_FILTERS.map(f => (
                <button key={f.key} className={`kb-filter${typeFilter === f.key ? ' active' : ''}`}
                  onClick={() => { setTypeFilter(f.key); setPage(0) }}>{f.label}</button>
              ))}
              <select className="kb-sort" value={sort} onChange={e => { setSort(e.target.value as any); setPage(0) }}>
                <option value="date">按时间</option>
                <option value="name">按名称</option>
                <option value="size">按大小</option>
              </select>
              {selected.size > 0 && (
                <button className="kb-batch-del" onClick={batchDelete}>删除选中 ({selected.size})</button>
              )}
            </div>
          </div>

          {/* 列表头 */}
          <div className="kb-list-head">
            <label className="kb-check"><input type="checkbox" checked={selected.size === paged.length && paged.length > 0}
              onChange={toggleAll} /></label>
            <span className="kb-col-name">文件名</span>
            <span className="kb-col-size">大小</span>
            <span className="kb-col-time">上传时间</span>
            <span className="kb-col-status">状态</span>
            <span className="kb-col-act">操作</span>
          </div>

          {/* 列表 */}
          <div className="kb-list">
            {paged.map(doc => (
              <div key={doc.id} className={`kb-row${selected.has(doc.id) ? ' kb-row-sel' : ''}`}>
                <label className="kb-check"><input type="checkbox" checked={selected.has(doc.id)}
                  onChange={() => toggleSelect(doc.id)} /></label>
                <span className="kb-col-name">
                  <span className="kb-row-name" title={doc.originalName}>{doc.originalName}</span>
                  {doc.chunkCount > 0 && <span className="kb-row-chunks">{doc.chunkCount} 块</span>}
                </span>
                <span className="kb-col-size">{formatSize(doc.fileSize)}</span>
                <span className="kb-col-time">{fmtDate(doc.createdAt)}</span>
                <span className="kb-col-status">
                  <span className={`kb-badge ${doc.status === 'READY' ? 'kb-badge-ok' : doc.status === 'FAILED' ? 'kb-badge-err' : 'kb-badge-busy'}`}>
                    {statusText(doc.status)}
                  </span>
                </span>
                <span className="kb-col-act">
                  {doc.status === 'READY' && (
                    <>
                      <button className="kb-act" onClick={() => setExpandedId(expandedId === doc.id ? null : doc.id)}
                        title="查看分块">{expandedId === doc.id ? '▲' : '▽'}</button>
                      <a href={getDocumentDownloadUrl(doc.id)} download className="kb-act" title="下载">↓</a>
                    </>
                  )}
                  {doc.status === 'FAILED' && <button className="kb-act kb-retry" onClick={() => handleRetry(doc.id)} title="重试">↻</button>}
                  <button className="kb-act kb-del" onClick={() => handleDelete(doc.id)} title="删除">✕</button>
                </span>
                {expandedId === doc.id && doc.chunkCount > 0 && (
                  <div className="kb-chunks">
                    <span className="kb-chunks-title">已分为 {doc.chunkCount} 块（每块 ≤512 tokens）</span>
                    <div className="kb-chunks-grid">
                      {Array.from({ length: doc.chunkCount }, (_, i) => (
                        <span key={i} className="kb-chunk-num">#{i + 1}</span>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            ))}
          </div>

          {/* 分页 */}
          {totalPages > 1 && (
            <div className="kb-pager">
              <button disabled={safePage === 0} onClick={() => setPage(p => Math.max(0, p - 1))}>‹ 上一页</button>
              <span>{safePage + 1} / {totalPages}</span>
              <button disabled={safePage >= totalPages - 1} onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}>下一页 ›</button>
            </div>
          )}
        </>
      )}
    </div>
  )
}
