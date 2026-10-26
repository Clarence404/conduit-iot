/** 预置角色与资源范围绑定接口；保存采用全量替换，禁止加载失败后提交空列表。 */
import { http } from './client'

export interface RoleDefinition { role_key: string; name: string; scope_mask: string; status: string; permissions: string[] }
export interface Binding { roleKey: string; scopeType: string; projectCode?: string | null; siteCode?: string | null }
export interface SavedBinding { role_key: string; scope_type: string; project_code?: string; site_code?: string }
const path = (username: string) => `/api/v1/users/${encodeURIComponent(username)}/bindings`
export const authorizationApi = {
  roles: () => http.get<RoleDefinition[]>('/api/v1/roles').then((r) => r.data),
  bindings: (username: string) => http.get<SavedBinding[]>(path(username)).then((r) => r.data),
  replace: (username: string, bindings: Binding[]) => http.put(path(username), bindings),
}
