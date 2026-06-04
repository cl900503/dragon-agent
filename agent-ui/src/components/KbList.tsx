/**
 * 知识库列表组件——侧边栏知识库模式下的内容。
 *
 * @author 陈龙
 * @since 2026-06-03
 */

import { useState, useEffect } from 'react'
import { fetchKnowledgeBases, createKnowledgeBase, deleteKnowledgeBase, type KbInfo } from '../api'
import type { Permissions } from '../hooks/useAuth'
import { showToast } from './Toast'
import './KbList.css'

interface Props {
  activeKbId: string
  onSelect: (id: string, info?: KbInfo) => void
  sidebarMode?: boolean
  perms?: Permissions
}

export default function KbList({ activeKbId, onSelect, perms }: Props) {
  const [kbs, setKbs] = useState<KbInfo[]>([])
  const [showNew, setShowNew] = useState(false)
  const [newName, setNewName] = useState('')
  const [newDesc, setNewDesc] = useState('')
  const [newVisibility, setNewVisibility] = useState('PRIVATE')
  const [newDeptId, setNewDeptId] = useState('')
  const [depts, setDepts] = useState<{ id: number; name: string }[]>([])

  useEffect(() => {
    fetchKnowledgeBases().then(setKbs).catch(() => {})
    fetch('/api/admin/departments').then(r => r.json()).then(setDepts).catch(() => {})
  }, [])

  const openCreate = () => { setShowNew(true) }

  const handleCreate = async () => {
    if (!newName.trim()) { showToast('请输入知识库名称'); return }
    if (newVisibility === 'DEPARTMENT' && perms?.isAdmin && !newDeptId) { showToast('请选择所属部门'); return }
    try {
      const body: any = { name: newName.trim(), visibility: newVisibility, description: newDesc.trim() }
      if (newVisibility === 'DEPARTMENT' && newDeptId) body.departmentId = parseInt(newDeptId)
      await createKnowledgeBase(body)
      // 重新拉取完整列表（含 departmentName 等字段）
      const freshList = await fetchKnowledgeBases()
      setKbs(freshList)
      const created = freshList.find(k => k.name === newName.trim())
      if (created) onSelect(created.id, created)
      setNewName(''); setNewDesc(''); setNewVisibility('PRIVATE'); setNewDeptId(''); setShowNew(false)
      showToast('知识库创建成功', 'success')
    } catch (err) { showToast(err instanceof Error ? err.message : '创建失败') }
  }

  const handleDelete = async (id: string) => {
    try {
      await deleteKnowledgeBase(id)
      setKbs(kbs.filter(k => k.id !== id))
      if (activeKbId === id) onSelect('')
      showToast('知识库已删除', 'success')
    } catch (err) { showToast(err instanceof Error ? err.message : '删除失败') }
  }

  const visLabel = (kb: KbInfo) => kb.visibility === 'COMPANY' ? '全公司'
    : kb.visibility === 'DEPARTMENT' ? (kb.departmentName || '部门')
    : '私有'
  const fmtDate = (iso: string) => iso ? iso.slice(0, 10) : ''

  return (
    <>
      <button className="kb-add-btn" onClick={openCreate}>
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
          <line x1="12" y1="5" x2="12" y2="19" /><line x1="5" y1="12" x2="19" y2="12" />
        </svg>
        新建知识库
      </button>

      {/* 新建知识库弹窗 */}
      {showNew && (
        <div className="kb-modal-overlay" onClick={() => { setShowNew(false); setNewDesc(''); setNewDeptId('') }}>
          <div className="kb-modal kb-modal-create" onClick={e => e.stopPropagation()}>
            <div className="kb-modal-head">
              <h3>新建知识库</h3>
              <button onClick={() => { setShowNew(false); setNewDesc(''); setNewDeptId('') }}>✕</button>
            </div>
            <div className="kb-modal-body">
              <div className="admin-field"><label>名称 <span className="admin-req">*</span></label>
                <input value={newName} onChange={e => setNewName(e.target.value)}
                  onKeyDown={e => e.key === 'Enter' && handleCreate()} placeholder="知识库名称" autoFocus /></div>
              <div className="admin-field"><label>描述</label>
                <input value={newDesc} onChange={e => setNewDesc(e.target.value)} placeholder="可选描述" /></div>
              <div className="admin-row-fields">
                <div className="admin-field"><label>可见性</label>
                  <select value={newVisibility} onChange={e => { setNewVisibility(e.target.value); setNewDeptId('') }}>
                    <option value="PRIVATE">私有</option>
                    <option value="DEPARTMENT">部门</option>
                    {perms?.isAdmin && <option value="COMPANY">全公司</option>}
                  </select></div>
                {perms?.isAdmin && newVisibility === 'DEPARTMENT' && (
                  <div className="admin-field"><label>所属部门 <span className="admin-req">*</span></label>
                    <select value={newDeptId} onChange={e => setNewDeptId(e.target.value)}>
                      <option value="">请选择</option>
                      {depts.map(d => <option key={d.id} value={d.id}>{d.name}</option>)}
                    </select></div>
                )}
              </div>
            </div>
            <div className="kb-modal-foot">
              <button className="admin-btn-cancel" onClick={() => { setShowNew(false); setNewDesc(''); setNewDeptId('') }}>取消</button>
              <button className="admin-btn" onClick={handleCreate}>创建</button>
            </div>
          </div>
        </div>
      )}

      {kbs.length === 0 ? (
        <div className="kb-list-empty">暂无知识库</div>
      ) : (
        kbs.map(kb => (
          <div key={kb.id} className={`kb-list-item${activeKbId === kb.id ? ' active' : ''}`}
            onClick={() => onSelect(kb.id, kb)}>
            <span className="kb-list-icon">📂</span>
            <div className="kb-list-info">
              <div className="kb-list-top">
                <span className="kb-list-name">{kb.name}</span>
                <span className={`kb-list-vis vis-${kb.visibility}`}>{visLabel(kb)}</span>
              </div>
              {kb.description && <span className="kb-list-desc">{kb.description}</span>}
              <span className="kb-list-meta">
                {kb.docCount != null && <span>{kb.docCount} 个文档 · </span>}
                {fmtDate(kb.createdAt)}
                {kb.ownerName && <span> · {kb.ownerName}</span>}
              </span>
            </div>
            <button className="kb-list-del" onClick={e => { e.stopPropagation(); handleDelete(kb.id) }} title="删除">
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><polyline points="3 6 5 6 21 6" /><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" /></svg>
            </button>
          </div>
        ))
      )}
    </>
  )
}
