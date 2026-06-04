/**
 * 认证与权限 hook —— 统一管理登录态与角色判定。
 *
 * 所有组件通过此 hook 获取 isAdmin / isDeptAdmin / canManagePersonnel 等，
 * 不再自行散落 role === 'ADMIN' 判断。
 *
 * @author 陈龙
 * @since 2026-06-01
 */

import { useState, useEffect, useCallback, useMemo } from 'react'
import { getCurrentUser, logout as authLogout } from '../auth'

export interface Permissions {
  isAdmin: boolean
  isDeptAdmin: boolean
  isUser: boolean
  /** 能否管理组织架构（新建/编辑/删除部门） */
  canManageDept: boolean
  /** 能否管理人员（新增/删除/改角色） */
  canManagePersonnel: boolean
  /** BACKEND-ENFORCED: canWrite depends on KB ownership; use API to check */
}

export function useAuth() {
  const [isLoggedIn, setIsLoggedIn] = useState(false)
  const [username, setUsername] = useState<string | null>(null)
  const [role, setRole] = useState<string | null>(null)
  const [authLoading, setAuthLoading] = useState(true)

  // 挂载时检查已有会话
  useEffect(() => {
    getCurrentUser()
      .then(async res => {
        console.log('[useAuth] /me response:', JSON.stringify(res))
        if (res.username) {
          setIsLoggedIn(true)
          setUsername(res.username)
          let r = res.role || null
          // 兜底：/me 未返回 role（后端未重启），从 users 列表查
          if (!r) {
            try {
              const ur = await fetch('/api/admin/users')
              if (ur.ok) {
                const users = await ur.json()
                const me = users.find((u: any) => u.username === res.username)
                if (me) r = me.role
              }
            } catch {}
          }
          console.log('[useAuth] setting role:', r)
          setRole(r)
        }
      })
      .catch((e) => { console.error('[useAuth] /me failed:', e) })
      .finally(() => setAuthLoading(false))
  }, [])

  const handleLogin = useCallback((uname: string, userRole?: string) => {
    setIsLoggedIn(true)
    setUsername(uname)
    setRole(userRole || null)
  }, [])

  const handleLogout = useCallback(async () => {
    try { await authLogout() } catch { /* API 失败也清理本地状态 */ }
    setIsLoggedIn(false)
    setUsername(null)
    setRole(null)
  }, [])

  // 统一权限计算 —— 所有组件只依赖这里
  const perms: Permissions = useMemo(() => ({
    isAdmin: role === 'ADMIN',
    isDeptAdmin: role === 'DEPT_ADMIN',
    isUser: role === 'USER' || (!role),  // role 未知时按普通用户处理（安全优先）
    canManageDept: role === 'ADMIN',
    canManagePersonnel: role === 'ADMIN' || role === 'DEPT_ADMIN',
  }), [role])

  return { isLoggedIn, username, role, perms, authLoading, handleLogin, handleLogout }
}
