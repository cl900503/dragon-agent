import { useState, useEffect } from 'react'
import type { ConversationSummary } from '../api'
import type { Permissions } from '../hooks/useAuth'
import * as adminApi from '../api/admin'
import ChevronIcon from './ChevronIcon'
import KbList from './KbList'
import { showToast } from './Toast'
import './Sidebar.css'

interface Props {
  conversations: ConversationSummary[]
  activeId: string
  collapsed: boolean
  username: string | null
  perms: Permissions
  kbActive: boolean
  devActive: boolean
  adminActive: boolean
  activeDevTool: string
  activeKbId: string
  onSelect: (id: string) => void
  onDelete: (id: string) => void
  onNewChat: () => void
  onToggle: () => void
  onLogout: () => void
  onDevToolSelect: (tool: string) => void
  onKbSelect: (kbId: string, info?: any) => void
  onAdminSelect?: (deptId: string) => void
  activeAdminDept?: string
  onDeptChange?: () => void
}

function AdminDeptList({ activeId, onSelect, onRefresh, canManage }: { activeId: string; onSelect: (id: string) => void; onRefresh: () => void; canManage: boolean }) {
  const [depts, setDepts] = useState<any[]>([])
  const [editingId, setEditingId] = useState<number | null>(null)
  const [editName, setEditName] = useState('')
  const [confirmDel, setConfirmDel] = useState<{ id: number; name: string; userCount: number } | null>(null)

  const load = () => {
    adminApi.listDepartments().then(d => { setDepts(d); onRefresh() }).catch(() => {})
  }
  useEffect(() => { load() }, [])

  const handleDelete = async () => {
    if (!confirmDel) return
    try {
      await adminApi.deleteDepartment(confirmDel.id)
      setConfirmDel(null); load()
    } catch { setConfirmDel(null) }
  }

  const startEdit = (d: any) => { setEditingId(d.id); setEditName(d.name) }
  const saveEdit = async (id: number) => {
    if (!editName.trim()) return
    try {
      await adminApi.renameDepartment(id, editName.trim())
      setEditingId(null); load()
    } catch { /* */ }
  }

  return (
    <>
      <div className={`conversation-item admin-all${activeId === '' ? ' active' : ''}`} onClick={() => onSelect('')}>
        <span className="conv-title">全部人员</span>
      </div>
      {depts.map((d: any) => (
        editingId === d.id ? (
          <div key={d.id} className="admin-new-dept-inline">
            <input className="admin-new-dept-input" value={editName} onChange={e => setEditName(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && saveEdit(d.id)} autoFocus />
            <button onClick={() => saveEdit(d.id)}>✓</button>
            <button onClick={() => setEditingId(null)}>✕</button>
          </div>
        ) : (
          <div key={d.id} className={`conversation-item admin-dept${activeId === String(d.id) ? ' active' : ''}`}
            onClick={() => onSelect(String(d.id))}>
            <span className="conv-title">● {d.name}</span>
            {canManage && (
              <>
                <button className="conv-edit" onClick={e => { e.stopPropagation(); startEdit(d) }} title="重命名">✎</button>
                <button className="conv-delete" onClick={e => { e.stopPropagation(); setConfirmDel({ id: d.id, name: d.name, userCount: d.userCount || 0 }) }} title="删除部门">
                  <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><polyline points="3 6 5 6 21 6" /><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" /></svg>
                </button>
              </>
            )}
          </div>
        )
      ))}
      {/* 删除确认弹窗 */}
      {confirmDel && (
        <div className="confirm-overlay" onClick={() => setConfirmDel(null)}>
          <div className="confirm-dialog" onClick={e => e.stopPropagation()}>
            <div className="confirm-icon-circle">
              <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#ef4444" strokeWidth="1.8" strokeLinecap="round">
                <circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>
              </svg>
            </div>
            <h3>确认删除部门</h3>
            <p className="confirm-msg">
              确定要删除 <strong>「{confirmDel.name}」</strong> 吗？
            </p>
            {confirmDel.userCount > 0 ? (
              <p className="confirm-warn">
                该部门下有 <strong>{confirmDel.userCount}</strong> 名人员，无法删除。<br />
                请先将人员转移至其他部门后再操作。
              </p>
            ) : (
              <p className="confirm-hint">此操作不可撤销，请谨慎操作。</p>
            )}
            <div className="confirm-actions">
              <button className="admin-btn-cancel" onClick={() => setConfirmDel(null)}>取消</button>
              {confirmDel.userCount === 0 && (
                <button className="admin-btn" onClick={handleDelete} style={{ background: '#ef4444' }}>确认删除</button>
              )}
            </div>
          </div>
        </div>
      )}
    </>
  )
}

export default function Sidebar({
  conversations, activeId, collapsed, username, perms, kbActive, devActive, adminActive, activeDevTool, activeKbId,
  onSelect, onDelete, onNewChat, onToggle, onLogout, onDevToolSelect, onKbSelect, onAdminSelect, activeAdminDept, onDeptChange,
}: Props) {
  const title = kbActive ? '知识库' : devActive ? '开发工具' : adminActive ? '组织架构' : '对话列表'
  const devTools = [
    { id: 'rag', label: '语义检索调试', icon: '🔎' },
    { id: 'dashboard', label: '检索质量分析', icon: '📈' },
    { id: 'markdown', label: '渲染效果预览', icon: '📐' },
  ]
  const [showNewDept, setShowNewDept] = useState(false)
  const [newDeptName, setNewDeptName] = useState('')
  const [deptKey, setDeptKey] = useState(0)
  console.log('[Sidebar] perms:', JSON.stringify(perms), 'canManageDept:', perms.canManageDept, 'adminActive:', adminActive, 'showNewDept:', showNewDept)

  const createDept = async () => {
    if (!newDeptName.trim()) return
    await adminApi.createDepartment(newDeptName.trim())
    setNewDeptName(''); setShowNewDept(false)
    setDeptKey(k => k + 1)
    onDeptChange?.()
  }

  return (
    <>
      {collapsed && (
        <button className="sidebar-expand-btn" onClick={onToggle} title="展开">
          <ChevronIcon direction="right" size={18} />
        </button>
      )}

      <aside className={`sidebar${collapsed ? ' collapsed' : ''}`}>
        <div className="sidebar-header">
          <span className="sidebar-title">{title}</span>
          <button className="sidebar-collapse-btn" onClick={onToggle} title="折叠">
            <ChevronIcon direction="left" size={14} />
          </button>
        </div>

        {!kbActive && !devActive && !adminActive && (
          <button className="new-chat-btn" onClick={onNewChat}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <line x1="12" y1="5" x2="12" y2="19" /><line x1="5" y1="12" x2="19" y2="12" />
            </svg>
            开启新对话
          </button>
        )}

        {adminActive && !showNewDept && perms.canManageDept && (
          <button className="new-chat-btn" onClick={() => setShowNewDept(true)}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <line x1="12" y1="5" x2="12" y2="19" /><line x1="5" y1="12" x2="19" y2="12" />
            </svg>
            新建部门
          </button>
        )}
        {adminActive && showNewDept && (
          <div className="admin-new-dept-inline">
            <input className="admin-new-dept-input" value={newDeptName} onChange={e => setNewDeptName(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && createDept()} placeholder="部门名称" autoFocus />
            <button onClick={createDept}>✓</button>
            <button onClick={() => { setShowNewDept(false); setNewDeptName('') }}>✕</button>
          </div>
        )}

        <div className="conversation-list">
          {!kbActive && !devActive && !adminActive && conversations.length === 0 && (
            <div className="no-conversations">暂无对话记录</div>
          )}
          {!kbActive && !devActive && !adminActive && conversations.map((conv) => (
            <div key={conv.id} className={`conversation-item${conv.id === activeId ? ' active' : ''}`}
              onClick={() => onSelect(conv.id)}>
              <span className="conv-title">{conv.title}</span>
              <button className="conv-delete" onClick={(e) => { e.stopPropagation(); onDelete(conv.id) }} title="删除">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><polyline points="3 6 5 6 21 6" /><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" /></svg>
              </button>
            </div>
          ))}
          {kbActive && (<KbList activeKbId={activeKbId} onSelect={(id, info) => onKbSelect(id, info)} sidebarMode perms={perms} />)}
          {adminActive && onAdminSelect && (
            <AdminDeptList key={deptKey} activeId={activeAdminDept || ''} onSelect={onAdminSelect} onRefresh={() => {}} canManage={perms.canManageDept} />
          )}
          {devActive && devTools.map(tool => (
            <div key={tool.id} className={`conversation-item${activeDevTool === tool.id ? ' active' : ''}`}
              onClick={() => onDevToolSelect(tool.id)}>
              <span className="conv-icon">{tool.icon}</span>
              <span className="conv-title">{tool.label}</span>
            </div>
          ))}
        </div>

        <div className="sidebar-footer">
          <div className="footer-avatar">{username?.charAt(0)?.toUpperCase() || '?'}</div>
          <span className="footer-name">{username}</span>
          <button className="footer-logout" onClick={onLogout} title="退出">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" /><polyline points="16 17 21 12 16 7" /><line x1="21" y1="12" x2="9" y2="12" />
            </svg>
          </button>
        </div>
      </aside>
    </>
  )
}
