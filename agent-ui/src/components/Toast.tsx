import { useEffect, useState } from 'react'
import './Toast.css'

let toastId = 0
const listeners: Set<(msg: ToastItem) => void> = new Set()

export interface ToastItem { id: number; message: string; type: 'error' | 'success' }

/** 全局 toast 方法 —— 任意组件可直接调用 */
export function showToast(message: string, type: 'error' | 'success' = 'error') {
  const item: ToastItem = { id: ++toastId, message, type }
  listeners.forEach(fn => fn(item))
}

export default function ToastContainer() {
  const [toasts, setToasts] = useState<ToastItem[]>([])

  useEffect(() => {
    const handler = (item: ToastItem) => {
      setToasts(prev => [...prev, item])
      setTimeout(() => setToasts(prev => prev.filter(t => t.id !== item.id)), 4000)
    }
    listeners.add(handler)
    return () => { listeners.delete(handler) }
  }, [])

  if (toasts.length === 0) return null

  return (
    <div className="toast-container">
      {toasts.map(t => (
        <div key={t.id} className={`toast toast-${t.type}`}>
          <span className="toast-icon">{t.type === 'error' ? '⚠' : '✓'}</span>
          <span className="toast-msg">{t.message}</span>
        </div>
      ))}
    </div>
  )
}
