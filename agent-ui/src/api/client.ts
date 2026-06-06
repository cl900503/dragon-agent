/**
 * 统一 API 客户端——基础 fetch 封装。
 *
 * 所有后端接口调用通过此模块统一管理错误处理和 JSON 序列化。
 *
 * @author 陈龙
 * @since 2026-06-06
 */

/** 解析后端返回的错误信息 */
export async function parseError(res: Response): Promise<string> {
  try {
    const body = await res.json()
    return body.error || body.message || `HTTP ${res.status}`
  } catch {
    return `HTTP ${res.status}`
  }
}

/** GET 请求 */
export async function apiGet<T>(path: string): Promise<T> {
  const res = await fetch(path)
  if (!res.ok) throw new Error(await parseError(res))
  return res.json()
}

/** POST 请求 */
export async function apiPost<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method: 'POST',
    headers: body != null ? { 'Content-Type': 'application/json' } : undefined,
    body: body != null ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) throw new Error(await parseError(res))
  return res.json()
}

/** PUT 请求 */
export async function apiPut<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method: 'PUT',
    headers: body != null ? { 'Content-Type': 'application/json' } : undefined,
    body: body != null ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) throw new Error(await parseError(res))
  return res.json()
}

/** DELETE 请求 */
export async function apiDelete(path: string): Promise<void> {
  const res = await fetch(path, { method: 'DELETE' })
  if (!res.ok) throw new Error(await parseError(res))
}
