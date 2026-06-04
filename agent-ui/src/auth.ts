/**
 * 认证 API 客户端——登录、注册、登出、会话检查。
 *
 * Vite 代理下所有 /api 请求同源，cookie 自动携带。
 *
 * @author 陈龙
 * @since 2026-06-01
 */

export interface AuthResponse {
  username: string | null
  role: string | null
  message: string
}

export async function login(username: string, password: string): Promise<AuthResponse> {
  const res = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  const data: AuthResponse = await res.json()
  if (!res.ok) throw new Error(data.message || '登录失败')
  return data
}

export async function register(username: string, password: string): Promise<AuthResponse> {
  const res = await fetch('/api/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  const data: AuthResponse = await res.json()
  if (!res.ok) throw new Error(data.message || '注册失败')
  return data
}

export async function logout(): Promise<void> {
  const res = await fetch('/api/auth/logout', { method: 'POST' })
  if (!res.ok) throw new Error('退出登录失败')
}

export async function getCurrentUser(): Promise<AuthResponse> {
  const res = await fetch('/api/auth/me')
  const data: AuthResponse = await res.json()
  if (!res.ok) throw new Error(data.message || '获取用户信息失败')
  return data
}
