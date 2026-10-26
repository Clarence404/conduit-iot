/**
 * AppLayout 组件测试：按身份给出的菜单（租户管理员、只读用户、平台管理员）与按路由归属高亮、折叠并保存偏好（点击与键盘 Enter/空格）、
 * 侧栏风格类名、面包屑、租户名称、用户菜单的显示名称与角色、头像文字、退出登录、代入提示条、会话身份变化时离开不匹配的页面、
 * 折叠按钮与用户菜单的无障碍名称、窄屏强制折叠且不改动已保存的折叠偏好、一屏高外壳。
 */
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import AppLayout from './AppLayout.vue'
import { authApi } from '@/api/auth'
import type { UserView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { UI_STORAGE_KEY, useUiStore } from '@/stores/ui'
import { createTestPinia, createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/auth', () => ({ authApi: { login: vi.fn(), refresh: vi.fn(), logout: vi.fn(), exitTenant: vi.fn() } }))
vi.mock('@/api/me', () => ({ meApi: { update: vi.fn(), changePassword: vi.fn() } }))
vi.mock('@/api/platform', () => ({ platformApi: { enter: vi.fn() } }))

const PLATFORM: Partial<UserView> = { username: 'sysadmin', role: 'PLATFORM_ADMIN', tenantCode: null, tenantName: null }

async function mountAt(path: string, user: Partial<UserView> = {}) {
  const pinia = createTestPinia(user)
  const router = createTestRouter()
  await router.push(path)
  await router.isReady()
  const wrapper = mount(AppLayout, { global: { plugins: [pinia, router, ElementPlus] }, attachTo: document.body })
  await settle()
  return { wrapper, router }
}

function menuLabels(wrapper: VueWrapper) {
  return wrapper.findAll('.el-menu-item').map((i) => i.text())
}

describe('AppLayout', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    vi.mocked(authApi.logout).mockResolvedValue(undefined)
  })

  it('gives a tenant admin devices and users, and highlights 设备 on a device detail page', async () => {
    const { wrapper } = await mountAt('/devices/dk1')

    expect(wrapper.text()).toContain('设备接入')
    expect(wrapper.text()).toContain('系统管理')
    expect(menuLabels(wrapper)).toEqual(['首页', '组织', '产品', '设备', '用户'])
    expect(wrapper.find('.el-menu-item.is-active').text()).toBe('设备')
    wrapper.unmount()
  })

  it('highlights 产品 on a product detail page', async () => {
    const { wrapper } = await mountAt('/products/pk1')

    expect(wrapper.find('.el-menu-item.is-active').text()).toBe('产品')
    wrapper.unmount()
  })

  it('gives a viewer only the overview and devices', async () => {
    const { wrapper } = await mountAt('/devices', { role: 'TENANT_VIEWER' })

    expect(menuLabels(wrapper)).toEqual(['首页', '组织', '产品', '设备'])
    expect(wrapper.text()).not.toContain('系统管理')
    wrapper.unmount()
  })

  it('gives the platform admin the overview, the tenant list and the data replay page', async () => {
    const { wrapper } = await mountAt('/tenants', PLATFORM)

    expect(menuLabels(wrapper)).toEqual(['首页', '租户', '数据重放'])
    expect(wrapper.find('.el-menu-item.is-active').text()).toBe('租户')
    expect(wrapper.text()).not.toContain('设备接入')
    expect(wrapper.find('.tenant').exists()).toBe(false)
    wrapper.unmount()
  })

  it('collapses from the toggle and remembers it', async () => {
    const { wrapper } = await mountAt('/devices')

    expect(wrapper.find('.el-menu').classes()).not.toContain('el-menu--collapse')
    await wrapper.find('.collapse-toggle').trigger('click')
    await settle()

    expect(wrapper.find('.el-menu').classes()).toContain('el-menu--collapse')
    expect(JSON.parse(localStorage.getItem(UI_STORAGE_KEY)!).sidebarCollapsed).toBe(true)
    wrapper.unmount()
  })

  it('toggles from the keyboard with Enter and Space, and labels the toggle and the user menu', async () => {
    const { wrapper } = await mountAt('/devices')
    const toggle = wrapper.find('.collapse-toggle')

    expect(toggle.attributes('aria-label')).toBe('收起菜单')
    await toggle.trigger('keydown', { key: 'Enter' })
    expect(useUiStore().sidebarCollapsed).toBe(true)
    expect(wrapper.find('.collapse-toggle').attributes('aria-label')).toBe('展开菜单')

    const space = new KeyboardEvent('keydown', { key: ' ', cancelable: true, bubbles: true })
    wrapper.find('.collapse-toggle').element.dispatchEvent(space)
    expect(useUiStore().sidebarCollapsed).toBe(false)
    expect(space.defaultPrevented).toBe(true)

    expect(wrapper.find('.user').attributes('aria-label')).toBe('用户菜单')
    wrapper.unmount()
  })

  it('uses the dark sidebar by default and follows the sidebar style preference', async () => {
    const { wrapper } = await mountAt('/devices')

    expect(wrapper.find('.sidebar').classes()).toContain('cd-sidebar--dark')
    useUiStore().setSidebarStyle('light')
    await nextTick()

    expect(wrapper.find('.sidebar').classes()).toContain('cd-sidebar--light')
    wrapper.unmount()
  })

  it('shows the breadcrumb from the route meta, the tenant name and the avatar initial', async () => {
    const { wrapper } = await mountAt('/devices/dk1')

    const crumbs = wrapper.findAll('.breadcrumb .el-breadcrumb__inner').map((c) => c.text())
    expect(crumbs).toEqual(['设备接入', '设备'])
    expect(wrapper.find('.tenant').text()).toBe('默认租户')
    expect(wrapper.find('.el-avatar').text()).toBe('A')
    wrapper.unmount()
  })

  it('names the user by display name and role', async () => {
    const { wrapper } = await mountAt('/devices', { displayName: '华东运维 张工', role: 'TENANT_VIEWER' })
    const vm = wrapper.vm as unknown as { userName: string; roleText: string }

    expect(vm.userName).toBe('华东运维 张工')
    expect(vm.roleText).toBe('只读用户')
    expect(wrapper.find('.el-avatar').text()).toBe('华')
    wrapper.unmount()
  })

  it('logs out from the user menu and returns to the login page', async () => {
    const { wrapper, router } = await mountAt('/devices')

    ;(wrapper.vm as unknown as { onCommand(command: string): void }).onCommand('logout')
    await settle()

    expect(authApi.logout).toHaveBeenCalledOnce()
    expect(useAuthStore().user).toBeNull()
    expect(router.currentRoute.value.name).toBe('login')
    wrapper.unmount()
  })

  it('opens personal settings from the user menu', async () => {
    const { wrapper } = await mountAt('/devices')
    const vm = wrapper.vm as unknown as { onCommand(command: string): void; profileVisible: boolean }

    vm.onCommand('profile')
    await nextTick()

    expect(vm.profileVisible).toBe(true)
    wrapper.unmount()
  })

  it('shows the 概览 group with 首页 highlighted on the home page', async () => {
    const { wrapper } = await mountAt('/home')

    expect(wrapper.text()).toContain('概览')
    expect(wrapper.find('.el-menu-item.is-active').text()).toBe('首页')
    expect(wrapper.findAll('.breadcrumb .el-breadcrumb__inner').map((c) => c.text())).toEqual(['概览', '首页'])
    wrapper.unmount()
  })

  it('shows the impersonation bar only while impersonating', async () => {
    const plain = await mountAt('/devices')
    expect(plain.wrapper.find('.impersonation-bar').exists()).toBe(false)
    plain.wrapper.unmount()

    const { wrapper } = await mountAt('/devices', { username: 'sysadmin', impersonatedBy: 'sysadmin', tenantName: '验收租户（华东）' })
    expect(wrapper.find('.main > .impersonation-bar').text()).toContain('验收租户（华东）')
    wrapper.unmount()
  })

  it('leaves a tenant page when the session turns into the platform admin', async () => {
    const { wrapper, router } = await mountAt('/products', { username: 'sysadmin', impersonatedBy: 'sysadmin' })

    useAuthStore().user = {
      username: 'sysadmin',
      displayName: null,
      role: 'PLATFORM_ADMIN',
      tenantCode: null,
      tenantName: null,
      mustChangePassword: false,
      impersonatedBy: null,
    }
    await settle()

    expect(router.currentRoute.value.name).toBe('tenants')
    wrapper.unmount()
  })

  describe('on a narrow screen', () => {
    const originalMatchMedia = window.matchMedia

    beforeEach(() => {
      window.matchMedia = ((query: string) => ({
        matches: query === '(max-width: 819px)',
        media: query,
        addEventListener: () => {},
        removeEventListener: () => {},
      })) as unknown as typeof window.matchMedia
    })

    afterEach(() => {
      window.matchMedia = originalMatchMedia
    })

    it('forces the sidebar collapsed, hides the toggle and leaves the saved preference unchanged', async () => {
      localStorage.setItem(UI_STORAGE_KEY, JSON.stringify({ sidebarCollapsed: false }))
      const { wrapper } = await mountAt('/devices')

      expect(wrapper.find('.el-menu').classes()).toContain('el-menu--collapse')
      expect(wrapper.find('.collapse-toggle').exists()).toBe(false)
      expect(useUiStore().sidebarCollapsed).toBe(false)
      expect(JSON.parse(localStorage.getItem(UI_STORAGE_KEY)!).sidebarCollapsed).toBe(false)
      wrapper.unmount()
    })
  })

  it('renders the logo in the sidebar, with the dark-bg tone on the dark sidebar and also when collapsed', async () => {
    const { wrapper } = await mountAt('/devices')

    expect(wrapper.find('.brand svg.conduit-logo').exists()).toBe(true)
    expect(wrapper.find('.brand svg.conduit-logo').attributes('data-tone')).toBe(useUiStore().sidebarStyle === 'dark' ? 'dark-bg' : 'auto')
    expect(wrapper.find('.brand-name').text()).toBe('Conduit')
    useUiStore().toggleSidebar()
    await nextTick()
    expect(wrapper.find('.brand svg.conduit-logo').exists()).toBe(true)
    expect(wrapper.find('.brand-name').exists()).toBe(false)
    wrapper.unmount()
  })

  it('keeps the shell one screen tall: sidebar with the toggle inside it, header fixed, only el-main scrolls', async () => {
    const { wrapper } = await mountAt('/devices')

    const layout = wrapper.find('.layout')
    expect(layout.classes()).toContain('el-container')
    expect(wrapper.find('.sidebar .collapse-toggle').exists()).toBe(true)
    expect(wrapper.find('.main > .header').exists()).toBe(true)
    expect(wrapper.find('.main > .el-main.content').exists()).toBe(true)
    // 页面里只有一个 el-main，它就是唯一的滚动区
    expect(wrapper.findAll('.el-main')).toHaveLength(1)
    wrapper.unmount()
  })

  it('declares the one-screen and main-scroll styles in the component source', () => {
    const source = readFileSync(resolve(__dirname, 'AppLayout.vue'), 'utf-8')
    expect(source).toMatch(/\.layout\s*\{[^}]*height:\s*100vh;[^}]*height:\s*100dvh;[^}]*overflow:\s*hidden/)
    expect(source).toMatch(/\.content\s*\{[^}]*overflow:\s*auto/)
    expect(source).toMatch(/\.menu\s*\{[^}]*min-height:\s*0/)
  })
})
