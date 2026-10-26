/**
 * 设备相关 REST 调用：分页查询、详情、创建、改名/启停、重置密钥、删除、最新值。
 * 创建与重置密钥的响应里带有设备密钥明文，服务端只返回这一次，调用方需立即展示给用户。
 * deviceKey 一律经 encodeURIComponent 拼进路径。
 */

import { http } from './client'
import type { DeviceFilterParams } from '@/utils/deviceFilters'
import type { DeviceAssignmentView, DeviceCreated, DeviceStatus, DeviceView, LatestView, PageView, SecretView } from './types'

/**
 * 设备列表查询参数：分页 + 筛选（productKey、keyword、online、status、reported、createdFrom 含、createdTo 不含，均为可选）。
 * 服务端无法获取在线状态时（带 online 筛选）返回 503，由调用方走统一错误提示。
 */
export interface DeviceQuery extends DeviceFilterParams {
  page: number
  size: number
}

const path = (deviceKey: string) => `/api/v1/devices/${encodeURIComponent(deviceKey)}`

export const deviceApi = {
  list: (query: DeviceQuery) =>
    http.get<PageView<DeviceView>>('/api/v1/devices', { params: query }).then((r) => r.data),
  get: (deviceKey: string) => http.get<DeviceView>(path(deviceKey)).then((r) => r.data),
  create: (productKey: string, name: string) =>
    http.post<DeviceCreated>('/api/v1/devices', { productKey, name }).then((r) => r.data),
  update: (deviceKey: string, name: string, status: DeviceStatus) =>
    http.put<DeviceView>(path(deviceKey), { name, status }).then((r) => r.data),
  resetSecret: (deviceKey: string) =>
    http.post<SecretView>(`${path(deviceKey)}/reset-secret`).then((r) => r.data),
  remove: (deviceKey: string) => http.delete(path(deviceKey)).then(() => undefined),
  latest: (deviceKey: string) => http.get<LatestView>(`${path(deviceKey)}/latest`).then((r) => r.data),
  assignment: (deviceKey: string) =>
    http.get<DeviceAssignmentView>(`${path(deviceKey)}/assignment`).then((r) => r.data),
  assign: (deviceKey: string, siteCode: string, assetCode: string | null) =>
    http.put<DeviceAssignmentView>(`${path(deviceKey)}/assignment`, { siteCode, assetCode }).then((r) => r.data),
}
