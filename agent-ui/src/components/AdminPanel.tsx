/**
 * 组织与人员管理——表格展示 + 弹窗编辑 + 分页。
 *
 * @author 陈龙
 * @since 2026-06-03
 */

import { useState, useEffect, useMemo } from 'react'
import type { Permissions } from '../hooks/useAuth'
import { showToast } from './Toast'
import * as adminApi from '../api/admin'
import type { UserItem, DeptItem } from '../api/admin'
import './AdminPanel.css'

interface Props {
  activeDept: string
  perms: Permissions
  currentUsername: string
  refreshKey?: number
}

const PAGE_SIZE = 15

function roleLabel(role: string) {
  const map: Record<string, string> = { ADMIN: '系统管理员', DEPT_ADMIN: '部门管理员', USER: '普通用户' }
  return map[role] || role
}

export default function AdminPanel({ activeDept, perms, currentUsername, refreshKey }: Props) {
  const [depts, setDepts] = useState<DeptItem[]>([])
  const [users, setUsers] = useState<UserItem[]>([])
  const [page, setPage] = useState(0)
  const [confirmDel, setConfirmDel] = useState<UserItem | null>(null)
  const [modal, setModal] = useState<{ mode: 'create' | 'edit'; user?: UserItem } | null>(null)
  const [form, setForm] = useState({ username: '', password: '', displayName: '', email: '', role: 'USER', departmentId: '' })

  const { isAdmin, isUser, canManagePersonnel: canManage } = perms

  const loadData = async () => {
    try {
      const [deps, usrs] = await Promise.all([adminApi.listDepartments(), adminApi.listUsers()])
      setDepts(deps)
      setUsers(usrs)
    } catch { /* */ }
  }
  useEffect(() => { loadData() }, [refreshKey])

  const filtered = useMemo(() => {
    return activeDept ? users.filter(u => String(u.departmentId) === activeDept) : users
  }, [users, activeDept])

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE))
  const safePage = Math.min(page, totalPages - 1)
  const paged = filtered.slice(safePage * PAGE_SIZE, (safePage + 1) * PAGE_SIZE)

  const deptName = (id: number | null) => depts.find(d => d.id === id)?.name || ''
  const activeDeptName = activeDept ? deptName(parseInt(activeDept)) : '全部人员'

  // ===== 弹窗 =====
  const openCreate = () => {
    setForm({ username: '', password: '', displayName: '', email: '', role: isAdmin ? 'USER' : 'USER', departmentId: isAdmin ? (activeDept || '') : '' })
    setModal({ mode: 'create' })
  }
  const openEdit = (u: UserItem) => {
    setForm({ username: u.username, password: '', displayName: u.displayName || '', email: u.email || '', role: u.role, departmentId: String(u.departmentId || '') })
    setModal({ mode: 'edit', user: u })
  }

  const submitModal = async () => {
    if (modal?.mode === 'create') {
      if (!form.username || !form.password) { showToast('用户名和密码必填'); return }
      if (isAdmin && !form.departmentId) { showToast('创建人员必须选择部门'); return }
      try {
        const body: any = { username: form.username, password: form.password, displayName: form.displayName || form.username, email: form.email }
        if (isAdmin) { body.role = form.role; if (form.departmentId) body.departmentId = parseInt(form.departmentId) }
        if (!body.departmentId && activeDept) body.departmentId = parseInt(activeDept)
        await adminApi.createUser(body)
        setModal(null); loadData(); showToast('创建成功', 'success')
      } catch (e: any) { showToast(e.message) }
    } else if (modal?.mode === 'edit' && modal.user) {
      try {
        if (canManage && form.role !== modal.user.role) {
          const body: any = { role: form.role }
          if (isAdmin && form.departmentId) body.departmentId = parseInt(form.departmentId)
          await adminApi.setUserRole(modal.user.id, form.role)
        }
        if (form.displayName !== (modal.user.displayName || '') || form.email !== (modal.user.email || '')) {
          await adminApi.editUserProfile(modal.user.id, { displayName: form.displayName, email: form.email })
        }
        setModal(null); loadData(); showToast('保存成功', 'success')
      } catch (e: any) { showToast(e.message) }
    }
  }

  const doDeleteUser = async () => {
    if (!confirmDel) return
    try {
      await adminApi.deleteUser(confirmDel.id)
      setConfirmDel(null); loadData(); showToast('已删除', 'success')
    } catch (e: any) { showToast(e.message); setConfirmDel(null) }
  }

  // ===== 渲染 =====
  return (
    <div className="admin-page">
      <div className="admin-head">
        <h2>{activeDeptName}</h2>
        <span className="admin-count">{filtered.length} 人</span>
        {canManage && (
          <button className="admin-btn" onClick={openCreate} style={{ marginLeft: 'auto' }}>+ 新增人员</button>
        )}
      </div>

      {/* 表头 */}
      <div className="admin-table-head">
        <span className="admin-col-user">用户名</span>
        <span className="admin-col-name">姓名</span>
        <span className="admin-col-email">邮箱</span>
        <span className="admin-col-role">角色</span>
        <span className="admin-col-dept">部门</span>
        <span className="admin-col-status">状态</span>
        <span className="admin-col-act">操作</span>
      </div>

      {/* 列表 */}
      {paged.length === 0 ? (
        <p className="admin-empty">暂无人员</p>
      ) : (
        <div className="admin-table-body">
          {paged.map(u => {
            const name = u.displayName || u.username
            const isSelf = u.username === currentUsername
            return (
              <div key={u.id} className="admin-table-row">
                <span className="admin-col-user">
                  {u.username}
                  {isSelf && <span className="admin-self-tag">我</span>}
                </span>
                <span className="admin-col-name">{u.displayName || '-'}</span>
                <span className="admin-col-email">{u.email || '-'}</span>
                <span className="admin-col-role">
                  <span className={`admin-role-badge role-${u.role}`}>{roleLabel(u.role)}</span>
                </span>
                <span className="admin-col-dept">{deptName(u.departmentId) || '-'}</span>
                <span className="admin-col-status">
                  {u.status === 'ACTIVE' ? <span className="admin-status-ok">正常</span> : <span className="admin-status-off">已禁用</span>}
                </span>
                <span className="admin-col-act">
                  {(canManage || isSelf) && (
                    <button className="admin-act-btn" onClick={() => openEdit(u)} title="编辑">✎</button>
                  )}
                  {canManage && u.role !== 'ADMIN' && (
                    <button className="admin-act-btn admin-act-del" onClick={() => setConfirmDel(u)} title="删除">✕</button>
                  )}
                </span>
              </div>
            )
          })}
        </div>
      )}

      {/* 分页 */}
      {totalPages > 1 && (
        <div className="admin-pager">
          <button disabled={safePage === 0} onClick={() => setPage(p => Math.max(0, p - 1))}>‹ 上一页</button>
          <span>{safePage + 1} / {totalPages}</span>
          <button disabled={safePage >= totalPages - 1} onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}>下一页 ›</button>
        </div>
      )}

      {/* 弹窗 */}
      {modal && (
        <div className="admin-modal-overlay" onClick={() => setModal(null)}>
          <div className="admin-modal" onClick={e => e.stopPropagation()}>
            <div className="admin-modal-head">
              <h3>{modal.mode === 'create' ? `新增人员${activeDeptName !== '全部人员' ? ` — ${activeDeptName}` : ''}` : `编辑 ${modal.user?.displayName || modal.user?.username}`}</h3>
              <button onClick={() => setModal(null)}>✕</button>
            </div>
            <div className="admin-modal-body">
              <div className="admin-field"><label>用户名 {modal.mode === 'create' && <span className="admin-req">*</span>}</label>
                <input value={form.username} onChange={e => setForm({ ...form, username: e.target.value })}
                  placeholder="登录账号" disabled={modal.mode === 'edit'} /></div>
              {modal.mode === 'create' && (
                <div className="admin-field"><label>初始密码 <span className="admin-req">*</span></label>
                  <input type="password" value={form.password} onChange={e => setForm({ ...form, password: e.target.value })} placeholder="至少4位" /></div>
              )}
              <div className="admin-field"><label>姓名</label>
                <input value={form.displayName} onChange={e => setForm({ ...form, displayName: e.target.value })} placeholder="真实姓名" /></div>
              <div className="admin-field"><label>邮箱</label>
                <input value={form.email} onChange={e => setForm({ ...form, email: e.target.value })} placeholder="user@company.com" /></div>
              {canManage && (
                <div className="admin-row-fields">
                  <div className="admin-field"><label>角色</label>
                    <select value={form.role} onChange={e => setForm({ ...form, role: e.target.value })}
                      disabled={modal.mode === 'edit' && modal.user?.role === 'ADMIN'}>
                      <option value="USER">普通用户</option>
                      <option value="DEPT_ADMIN">部门管理员</option>
                      {isAdmin && <option value="ADMIN">系统管理员</option>}
                    </select></div>
                  {isAdmin && (
                    <div className="admin-field"><label>部门 <span className="admin-req">*</span></label>
                      <select value={form.departmentId || activeDept} onChange={e => setForm({ ...form, departmentId: e.target.value })}
                        disabled={modal.mode === 'edit' && modal.user?.role === 'ADMIN'}>
                        <option value="">无部门</option>
                        {depts.map(d => <option key={d.id} value={d.id}>{d.name}</option>)}
                      </select></div>
                  )}
                </div>
              )}
            </div>
            <div className="admin-modal-foot">
              <button className="admin-btn-cancel" onClick={() => setModal(null)}>取消</button>
              <button className="admin-btn" onClick={submitModal}>
                {modal.mode === 'create' ? '创建人员' : '保存'}
              </button>
            </div>
          </div>
        </div>
      )}
      {/* 删除确认弹窗 */}
      {confirmDel && (
        <div className="confirm-overlay" onClick={() => setConfirmDel(null)}>
          <div className="confirm-dialog" onClick={e => e.stopPropagation()}>
            <div className="confirm-icon-circle">
              <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="#ef4444" strokeWidth="1.8" strokeLinecap="round">
                <circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/>
              </svg>
            </div>
            <h3>确认删除</h3>
            <p className="confirm-msg">确定要删除 <strong>「{confirmDel.displayName || confirmDel.username}」</strong> 吗？</p>
            <p className="confirm-hint">此操作不可撤销</p>
            <div className="confirm-actions">
              <button className="admin-btn-cancel" onClick={() => setConfirmDel(null)}>取消</button>
              <button className="admin-btn" onClick={doDeleteUser} style={{ background: '#ef4444' }}>确认删除</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
