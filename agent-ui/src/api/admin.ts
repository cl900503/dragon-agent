/**
 * 组织架构和人员管理 API。
 *
 * @author 陈龙
 * @since 2026-06-06
 */

import { apiGet, apiPost, apiPut, apiDelete } from './client'

export interface UserItem {
  id: number
  username: string
  displayName: string
  email: string
  role: string
  departmentId: number | null
  status: string
}

export interface DeptItem {
  id: number
  name: string
  parentId: number | null
  path: string
  userCount?: number
}

// ---- 部门管理 ----

export function listDepartments(): Promise<DeptItem[]> {
  return apiGet('/api/admin/departments')
}

export function createDepartment(name: string): Promise<DeptItem> {
  return apiPost('/api/admin/departments', { name })
}

export function renameDepartment(id: number, name: string): Promise<void> {
  return apiPut(`/api/admin/departments/${id}`, { name })
}

export function deleteDepartment(id: number): Promise<void> {
  return apiDelete(`/api/admin/departments/${id}`)
}

// ---- 人员管理 ----

export function listUsers(departmentId?: number): Promise<UserItem[]> {
  const url = departmentId != null
    ? `/api/admin/users?departmentId=${departmentId}`
    : '/api/admin/users'
  return apiGet(url)
}

export function createUser(body: Record<string, unknown>): Promise<UserItem> {
  return apiPost('/api/admin/users', body)
}

export function deleteUser(id: number): Promise<void> {
  return apiDelete(`/api/admin/users/${id}`)
}

export function setUserRole(id: number, role: string): Promise<void> {
  return apiPut(`/api/admin/users/${id}/role`, { role })
}

export function editUserProfile(id: number, body: Record<string, unknown>): Promise<void> {
  return apiPut(`/api/admin/users/${id}/profile`, body)
}
