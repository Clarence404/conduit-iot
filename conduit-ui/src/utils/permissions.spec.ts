/** permissions 单元测试：各身份的权限（平台管理员、代入、租户管理员、只读用户、未登录）与角色、状态、名称的显示 */
import { describe, expect, it } from 'vitest'
import type { UserView } from '@/api/types'
import { displayNameOf, permissionsOf, hasPermission, roleLabel, statusLabel } from './permissions'

function user(patch: Partial<UserView>): UserView {
  return {
    username: 'u',
    displayName: null,
    role: 'TENANT_ADMIN',
    tenantCode: 'east',
    tenantName: '验收租户（华东）',
    mustChangePassword: false,
    impersonatedBy: null,
    ...patch,
  }
}

describe('permissionsOf', () => {
  it('uses scoped permissions instead of the legacy JWT role in enforce mode', () => {
    const scoped=user({ role: 'TENANT_VIEWER', authorizationEnforced: true, permissions: ['device.read', 'device.manage'], tenantPermissions: [] })
    expect(permissionsOf(scoped)).toMatchObject({canWrite: true, canManageUsers: false})
    expect(hasPermission(scoped, 'device.manage')).toBe(true)
    expect(hasPermission(scoped, 'product.manage', true)).toBe(false)
    expect(permissionsOf(user({authorizationEnforced: true, permissions: [], tenantPermissions: []}))).toMatchObject({canWrite: false, canManageUsers: false})
  })
  it('gives the platform admin only the platform', () => {
    expect(permissionsOf(user({ role: 'PLATFORM_ADMIN', tenantCode: null, tenantName: null }))).toEqual({
      isPlatformAdmin: true,
      isImpersonating: false,
      canWrite: false,
      canManageUsers: false,
    })
  })

  it('treats an impersonating platform admin as a tenant admin', () => {
    expect(permissionsOf(user({ impersonatedBy: 'sysadmin' }))).toEqual({
      isPlatformAdmin: false,
      isImpersonating: true,
      canWrite: true,
      canManageUsers: true,
    })
  })

  it('lets tenant admins write and manage users, viewers only read', () => {
    expect(permissionsOf(user({}))).toMatchObject({ canWrite: true, canManageUsers: true, isPlatformAdmin: false })
    expect(permissionsOf(user({ role: 'TENANT_VIEWER' }))).toMatchObject({ canWrite: false, canManageUsers: false })
    expect(permissionsOf(null)).toEqual({ isPlatformAdmin: false, isImpersonating: false, canWrite: false, canManageUsers: false })
  })
})

describe('labels', () => {
  it('names roles, statuses and users in Chinese', () => {
    expect(roleLabel('PLATFORM_ADMIN')).toBe('平台管理员')
    expect(roleLabel('TENANT_ADMIN')).toBe('租户管理员')
    expect(roleLabel('TENANT_VIEWER')).toBe('只读用户')
    expect(statusLabel('ACTIVE')).toBe('启用')
    expect(statusLabel('DISABLED')).toBe('禁用')
    expect(statusLabel('DISABLED', 'tenant')).toBe('停用')
    expect(displayNameOf({ username: 'east-admin', displayName: '华东管理员' })).toBe('华东管理员')
    expect(displayNameOf({ username: 'east-admin', displayName: null })).toBe('east-admin')
    expect(displayNameOf(null)).toBe('')
  })
})
