/**
 * RAG 检索质量和反馈 API。
 *
 * @author 陈龙
 * @since 2026-06-06
 */

import { apiGet, apiPost } from './client'

/** 提交检索质量反馈 */
export function submitFeedback(messageId: string, rating: string): Promise<{ status: string }> {
  return apiPost('/api/rag/feedback', { messageId, rating })
}

/** 批量查询反馈状态 */
export function batchFeedback(ids: string[]): Promise<Record<string, string | null>> {
  return apiGet(`/api/rag/feedback/batch?ids=${ids.join(',')}`)
}

/** 检索质量统计（最近 30 天） */
export function getRagStats(): Promise<Record<string, unknown>> {
  return apiGet('/api/rag/stats')
}

/** 最近检索记录 */
export function getRecentSearches(): Promise<Array<Record<string, unknown>>> {
  return apiGet('/api/rag/recent')
}
