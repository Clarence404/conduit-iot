/**
 * 权限与身份显示：由当前用户算出 isPlatformAdmin（未代入的平台管理员）、isImpersonating（平台管理员代入租户中）、
 * canWrite / canManageUsers（租户管理员，含代入的平台管理员），以及角色、状态与用户名称的中文显示。
 * 前端只用这些结果隐藏入口，真正的权限判断在后端。
 */
import type { AccountStatus, Role, UserView } from '@/api/types'

/** 当前用户的权限 */
export interface Permissions {
  /** 未代入的平台管理员（代入时有效角色是 TENANT_ADMIN，此项为 false） */
  isPlatformAdmin: boolean
  /** 平台管理员正在代入某个租户 */
  isImpersonating: boolean
  /** 可以写租户数据（新建、编辑、删除、导入、启用/禁用、重置密钥） */
  canWrite: boolean
  /** 可以管理本租户的用户 */
  canManageUsers: boolean
}

/** 由当前用户算出权限；未登录时全部为 false */
export function permissionsOf(user: UserView | null): Permissions {
  const role = user?.role
  return {
    isPlatformAdmin: role === 'PLATFORM_ADMIN',
    isImpersonating: Boolean(user?.impersonatedBy),
    canWrite: user?.authorizationEnforced ? Boolean(user.permissions?.some((p) => p.endsWith('.manage'))) : role === 'TENANT_ADMIN',
    canManageUsers: user?.authorizationEnforced ? Boolean(user.tenantPermissions?.includes('member.manage')) : role === 'TENANT_ADMIN',
  }
}

/** 服务端启用细粒度授权时按权限码判断；兼容窗口仍用旧角色。 */
export function hasPermission(user: UserView | null, permission: string, tenant = false): boolean {
  if (!user?.authorizationEnforced) return user?.role === 'TENANT_ADMIN'
  return Boolean((tenant ? user.tenantPermissions : user.permissions)?.includes(permission))
}

const ROLE_LABELS: Record<Role, string> = {
  PLATFORM_ADMIN: '平台管理员',
  TENANT_ADMIN: '租户管理员',
  TENANT_VIEWER: '只读用户',
}

/** 角色的中文名称 */
export function roleLabel(role: Role): string {
  return ROLE_LABELS[role] ?? role
}

/**
 * 状态的中文名称：ACTIVE 为「启用」；DISABLED 对用户是「禁用」、对租户是「停用」。
 * @param kind 状态的主体，默认用户
 */
export function statusLabel(status: AccountStatus, kind: 'user' | 'tenant' = 'user'): string {
  if (status === 'ACTIVE') {
    return '启用'
  }
  return kind === 'tenant' ? '停用' : '禁用'
}

/** 显示用的名称：有显示名称用显示名称，否则用用户名；没有用户时为空串 */
export function displayNameOf(user: { username: string; displayName: string | null } | null): string {
  return user ? user.displayName || user.username : ''
}
