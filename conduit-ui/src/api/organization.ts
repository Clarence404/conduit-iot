import { http } from './client'
import type { AssetView, CustomerView, OrganizationStatus, ProjectView, SiteView } from './types'

export interface CustomerInput { code: string; name: string; status: OrganizationStatus }
export interface ProjectInput extends CustomerInput { customerCode: string | null }
export interface SiteInput extends CustomerInput { projectCode: string; timezone: string }
export interface AssetInput extends CustomerInput { siteCode: string; parentCode: string | null; type: string | null }

function resourceApi<T, I>(name: string) {
  const base = `/api/v1/${name}`
  const path = (code: string) => `${base}/${encodeURIComponent(code)}`
  return {
    list: () => http.get<T[]>(base).then((r) => r.data),
    create: (input: I) => http.post<T>(base, input).then((r) => r.data),
    update: (code: string, input: I) => http.put<T>(path(code), input).then((r) => r.data),
    remove: (code: string) => http.delete(path(code)).then(() => undefined),
  }
}

export const customerApi = resourceApi<CustomerView, CustomerInput>('customers')
export const projectApi = resourceApi<ProjectView, ProjectInput>('projects')
export const siteApi = resourceApi<SiteView, SiteInput>('sites')
export const assetApi = resourceApi<AssetView, AssetInput>('assets')
