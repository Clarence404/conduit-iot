/** 预置角色与资源范围绑定接口；保存采用全量替换，禁止加载失败后提交空列表。 */
import { http } from './client'

export interface RoleDefinition { role_key: string; name: string; scope_mask: string; status: string; built_in: boolean; permissions: string[] }
export interface RoleInput { roleKey: string; name: string; scopeMask: string; status: 'ACTIVE' | 'DISABLED'; permissions: string[] }
export interface Binding { roleKey: string; scopeType: string; projectCode?: string | null; siteCode?: string | null }
export interface SavedBinding { role_key: string; scope_type: string; project_code?: string; site_code?: string }
const path = (username: string) => `/api/v1/users/${encodeURIComponent(username)}/bindings`
export const authorizationApi = {
  roles: () => http.get<RoleDefinition[]>('/api/v1/roles').then((r) => r.data),
  permissionCatalog: () => http.get<string[]>('/api/v1/roles/permissions').then((r) => r.data),
  createRole: (body: RoleInput) => http.post('/api/v1/roles', body),
  updateRole: (key: string, body: RoleInput) => http.put(`/api/v1/roles/${encodeURIComponent(key)}`, body),
  deleteRole: (key: string) => http.delete(`/api/v1/roles/${encodeURIComponent(key)}`),
  bindings: (username: string) => http.get<SavedBinding[]>(path(username)).then((r) => r.data),
  replace: (username: string, bindings: Binding[]) => http.put(path(username), bindings),
  projectMembers: (project: string) => http.get<{ username: string; display_name: string; status: string }[]>(`/api/v1/projects/${encodeURIComponent(project)}/members`).then((r) => r.data),
  projectRoles: (project: string) => http.get<RoleDefinition[]>(`/api/v1/projects/${encodeURIComponent(project)}/members/roles`).then((r) => r.data),
  projectBindings: (project: string, username: string) => http.get<SavedBinding[]>(`/api/v1/projects/${encodeURIComponent(project)}/members/${encodeURIComponent(username)}/bindings`).then((r) => r.data),
  replaceProjectBindings: (project: string, username: string, bindings: Binding[]) => http.put(`/api/v1/projects/${encodeURIComponent(project)}/members/${encodeURIComponent(username)}/bindings`, bindings),
}
