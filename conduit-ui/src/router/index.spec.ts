/**
 * 真实路由与守卫（router/index.ts 的 beforeEach）测试：未登录先尝试用 refresh Cookie 恢复、失败带 redirect 回登录页；
 * 身份与页面不匹配时按 accessRedirect 改道；代入租户的平台管理员带「需改密码」标记时仍可访问租户页面（由强制改密码对话框接管）。
 * 页面组件与布局全部用空壳替换，只验证守卫的跳转结果。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import router from './index'
import { useAuthStore } from '@/stores/auth'
import { authApi } from '@/api/auth'
import type { TokenResponse, UserView } from '@/api/types'

vi.mock('@/api/auth', () => ({ authApi: { login: vi.fn(), refresh: vi.fn(), logout: vi.fn(), exitTenant: vi.fn() } }))
vi.mock('@/api/me', () => ({ meApi: { update: vi.fn(), changePassword: vi.fn() } }))
vi.mock('@/api/platform', () => ({ platformApi: { enter: vi.fn() } }))
vi.mock('@/layouts/AppLayout.vue', () => ({ default: { template: '<div><router-view /></div>' } }))
for (const view of ['LoginView', 'HomeEntry', 'ProductsView', 'OrganizationView', 'TenantsView', 'UsersView', 'NotFoundView']) {
  vi.doMock(`@/views/${view}.vue`, () => ({ default: { template: '<div />' } }))
}

function user(patch: Partial<UserView> = {}): UserView {
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

function session(patch: Partial<UserView> = {}): TokenResponse {
  return { accessToken: 'tok', expiresIn: 900, user: user(patch) }
}

/** 以给定身份登录后访问 path，返回最终的路径 */
async function visit(path: string, patch: Partial<UserView> | null): Promise<string> {
  const auth = useAuthStore()
  if (patch) {
    auth.setSession(session(patch))
  }
  await router.push(path)
  return router.currentRoute.value.fullPath
}

describe('router guard (real router)', () => {
  beforeEach(async () => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    await router.push('/login')
  })

  it('lets the public login page through without a session', async () => {
    expect(await visit('/login', null)).toBe('/login')
    expect(authApi.refresh).not.toHaveBeenCalled()
  })

  it('redirects to login with the target as redirect when the session cannot be restored', async () => {
    vi.mocked(authApi.refresh).mockRejectedValue(new Error('401'))

    await visit('/products', null)

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/products')
  })

  it('restores the session from the refresh cookie and continues', async () => {
    vi.mocked(authApi.refresh).mockResolvedValue(session())

    expect(await visit('/products', null)).toBe('/products')
    expect(useAuthStore().token).toBe('tok')
  })

  it('sends an unimpersonated platform admin from tenant pages to the tenant list', async () => {
    expect(await visit('/products', { role: 'PLATFORM_ADMIN', tenantCode: null, tenantName: null })).toBe('/tenants')
  })

  it('sends tenant identities away from platform pages, and viewers away from user management', async () => {
    expect(await visit('/tenants', { role: 'TENANT_VIEWER' })).toBe('/home')
    await router.push('/login')
    expect(await visit('/users', { role: 'TENANT_VIEWER' })).toBe('/home')
  })

  it('keeps an impersonating platform admin on tenant pages even when a password change is required', async () => {
    const path = await visit('/users', { impersonatedBy: 'sysadmin', mustChangePassword: true })

    expect(path).toBe('/users')
    // 改密码由强制改密码对话框接管，守卫不拦截页面
    expect(useAuthStore().needsPasswordChange).toBe(true)
  })
})
