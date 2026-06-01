/**
 * 认证状态管理 hook——封装登录态检查、登录成功处理和退出逻辑。
 *
 * @author 陈龙
 * @since 2026-06-01
 */

import { useState, useEffect, useCallback } from 'react'
import { getCurrentUser, logout as authLogout } from '../auth'

export function useAuth() {
  const [isLoggedIn, setIsLoggedIn] = useState(false)
  const [username, setUsername] = useState<string | null>(null)
  const [authLoading, setAuthLoading] = useState(true)

  // 挂载时检查已有会话
  useEffect(() => {
    getCurrentUser()
      .then(res => {
        if (res.username) {
          setIsLoggedIn(true)
          setUsername(res.username)
        }
      })
      .catch(() => {})
      .finally(() => setAuthLoading(false))
  }, [])

  const handleLogin = useCallback((uname: string) => {
    setIsLoggedIn(true)
    setUsername(uname)
  }, [])

  const handleLogout = useCallback(async () => {
    try { await authLogout() } catch { /* API 失败也清理本地状态 */ }
    setIsLoggedIn(false)
    setUsername(null)
  }, [])

  return { isLoggedIn, username, authLoading, handleLogin, handleLogout }
}
