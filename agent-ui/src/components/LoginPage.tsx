import { useState, type FormEvent } from 'react'
import { login, register } from '../auth'
import './LoginPage.css'

interface Props {
  onLogin: (username: string, role?: string) => void
}

interface FieldErrors {
  username?: string
  password?: string
}

/**
 * 登录页面——账号由管理员统一分配。
 *
 * @author 陈龙
 * @since 2026-06-01
 */
export default function LoginPage({ onLogin }: Props) {
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({})
  const [loading, setLoading] = useState(false)

  function validate(): boolean {
    const errs: FieldErrors = {}
    if (!username.trim()) errs.username = '请输入用户名'
    else if (username.trim().length < 2) errs.username = '用户名至少 2 个字符'
    if (!password) errs.password = '请输入密码'
    else if (password.length < 4) errs.password = '密码至少 4 个字符'
    setFieldErrors(errs)
    return Object.keys(errs).length === 0
  }

  function clearFieldError(field: keyof FieldErrors) {
    setFieldErrors(prev => {
      if (!prev[field]) return prev
      const next = { ...prev }; delete next[field]; return next
    })
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault(); setError(null)
    if (!validate()) return
    setLoading(true)
    try {
      const fn = mode === 'login' ? login : register
      const result = await fn(username.trim(), password)
      if (result.username) {
          onLogin(result.username, result.role || undefined)
        } else {
          const msg = result.message || ''
          setError(msg.includes('管理面板')
            ? '系统已由管理员接管，新账号请联系管理员创建'
            : msg || '操作失败')
        }
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '操作失败')
    } finally { setLoading(false) }
  }

  return (
    <div className="login-page">
      <div className="login-card">
        <div className={`login-error${error ? ' login-error--active' : ''}`}>
          <span className="login-error-icon">⚠</span>
          <span>{error || ' '}</span>
        </div>

        <div className="login-logo">🐉</div>
        <h1>Dragon Agent</h1>

        <form onSubmit={handleSubmit} noValidate>
          <div className="login-field">
            <input
              type="text"
              placeholder="用户名"
              value={username}
              onChange={e => { setUsername(e.target.value); clearFieldError('username') }}
              onBlur={() => { if (username.trim()) clearFieldError('username') }}
              autoComplete="username"
              className={fieldErrors.username ? 'input-error' : ''}
            />
            <span className={`field-hint${fieldErrors.username ? ' field-hint--error' : ''}`}>
              {fieldErrors.username || ' '}
            </span>
          </div>
          <div className="login-field">
            <input
              type="password"
              placeholder="密码"
              value={password}
              onChange={e => { setPassword(e.target.value); clearFieldError('password') }}
              onBlur={() => { if (password) clearFieldError('password') }}
              autoComplete="current-password"
              className={fieldErrors.password ? 'input-error' : ''}
            />
            <span className={`field-hint${fieldErrors.password ? ' field-hint--error' : ''}`}>
              {fieldErrors.password || ' '}
            </span>
          </div>
          <button type="submit" disabled={loading}>
            {loading ? '处理中...' : mode === 'login' ? '登录' : '注册'}
          </button>
        </form>
        <p className="login-switch">
          {mode === 'login' ? '首次使用？' : '已有账号？'}
          <button type="button" onClick={() => { setMode(m => m === 'login' ? 'register' : 'login'); setError(null) }}>
            {mode === 'login' ? '注册管理员' : '去登录'}
          </button>
        </p>
      </div>
    </div>
  )
}
