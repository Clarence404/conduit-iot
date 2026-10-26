/** menuGroupsFor 单元测试：平台管理员、租户管理员（含代入）、只读用户各自的菜单分组与菜单项 */
import { describe, expect, it } from 'vitest'
import { menuGroupsFor } from './menu'
import type { Permissions } from '@/utils/permissions'

function layout(p: Permissions) {
  return menuGroupsFor(p).map((g) => `${g.title}:${g.items.map((i) => i.path).join(',')}`)
}

describe('menuGroupsFor', () => {
  it('gives the platform admin the overview and the tenant list', () => {
    expect(layout({ isPlatformAdmin: true, isImpersonating: false, canWrite: false, canManageUsers: false })).toEqual([
      '概览:/home',
      '系统管理:/tenants,/telemetry-replay',
    ])
  })

  it('gives tenant admins devices and users', () => {
    expect(layout({ isPlatformAdmin: false, isImpersonating: true, canWrite: true, canManageUsers: true })).toEqual([
      '概览:/home',
      '组织管理:/organization',
      '设备接入:/products,/devices',
      '系统管理:/users',
    ])
  })

  it('gives viewers only the overview and devices', () => {
    expect(layout({ isPlatformAdmin: false, isImpersonating: false, canWrite: false, canManageUsers: false })).toEqual([
      '概览:/home',
      '组织管理:/organization',
      '设备接入:/products,/devices',
    ])
  })
})
