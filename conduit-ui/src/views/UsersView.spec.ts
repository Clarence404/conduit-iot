/**
 * UsersView 组件测试：首屏第 1 页 10 条、角色/状态/待改密码标签、自己那一行没有禁用与删除（代入时没有「自己」）、
 * 筛选（关键字、角色、状态）、启用与删除、经对话框重置密码后刷新、打开新建对话框。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import UsersView from './UsersView.vue'
import { userApi } from '@/api/users'
import type { TenantUserView, UserView } from '@/api/types'
import { createTestPinia, createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/users', () => ({
  userApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), disable: vi.fn(), enable: vi.fn(), resetPassword: vi.fn(), remove: vi.fn() },
}))
vi.mock('@/api/auth', () => ({ authApi: { login: vi.fn(), refresh: vi.fn(), logout: vi.fn(), exitTenant: vi.fn() } }))
vi.mock('@/api/me', () => ({ meApi: { update: vi.fn(), changePassword: vi.fn() } }))
vi.mock('@/api/platform', () => ({ platformApi: { enter: vi.fn() } }))
// 确认框直接确认
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return { ...actual, ElMessageBox: { ...actual.ElMessageBox, confirm: vi.fn().mockResolvedValue('confirm') } }
})

function user(patch: Partial<TenantUserView> = {}): TenantUserView {
  return {
    username: 'east-admin',
    displayName: '华东管理员',
    role: 'TENANT_ADMIN',
    status: 'ACTIVE',
    mustChangePassword: false,
    createdAt: '2026-10-16T00:00:00Z',
    ...patch,
  }
}

type Vm = {
  filters: { keyword: string; role: string; status: string }
  formVisible: boolean
  editing: TenantUserView | null
  search(): void
  openReset(row: TenantUserView): void
  savePassword(password: string): Promise<void>
}

async function mountView(session: Partial<UserView> = {}) {
  const pinia = createTestPinia({ username: 'east-admin', tenantCode: 'east', tenantName: '验收租户（华东）', ...session })
  const router = createTestRouter()
  await router.push('/users')
  await router.isReady()
  const wrapper = mount(UsersView, { global: { plugins: [pinia, router, ElementPlus] }, attachTo: document.body })
  await settle()
  return { wrapper, vm: wrapper.vm as unknown as Vm }
}

function rowButtons(wrapper: Awaited<ReturnType<typeof mountView>>['wrapper'], index: number) {
  return wrapper.findAll('.el-table__body tr')[index].findAll('button').map((b) => b.text())
}

describe('UsersView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(userApi.list).mockResolvedValue({
      items: [
        user(),
        user({ username: 'east-viewer', displayName: null, role: 'TENANT_VIEWER', status: 'DISABLED', mustChangePassword: true }),
      ],
      total: 2,
      page: 1,
      size: 10,
    })
  })

  it('requests the first page with 10 rows', async () => {
    const { wrapper } = await mountView()

    expect(userApi.list).toHaveBeenCalledWith({ keyword: undefined, role: undefined, status: undefined, page: 1, size: 10 })
    wrapper.unmount()
  })

  it('shows role, status and password tags', async () => {
    const { wrapper } = await mountView()

    const text = wrapper.find('.el-table').text()
    expect(text).toContain('租户管理员')
    expect(text).toContain('只读用户')
    expect(text).toContain('禁用')
    expect(text).toContain('待改密码')
    wrapper.unmount()
  })

  it('hides disable and delete on the own row', async () => {
    const { wrapper } = await mountView()

    expect(rowButtons(wrapper, 0)).toEqual(['资源权限', '编辑', '重置密码'])
    expect(rowButtons(wrapper, 1)).toEqual(['资源权限', '编辑', '重置密码', '启用', '删除'])
    wrapper.unmount()
  })

  it('has no own row while impersonating', async () => {
    const { wrapper } = await mountView({ username: 'sysadmin', impersonatedBy: 'sysadmin' })

    expect(rowButtons(wrapper, 0)).toEqual(['资源权限', '编辑', '重置密码', '禁用', '删除'])
    wrapper.unmount()
  })

  it('filters by keyword, role and status', async () => {
    const { wrapper, vm } = await mountView()

    vm.filters.keyword = ' 张工 '
    vm.filters.role = 'TENANT_VIEWER'
    vm.filters.status = 'DISABLED'
    vm.search()
    await settle()

    expect(userApi.list).toHaveBeenLastCalledWith({ keyword: '张工', role: 'TENANT_VIEWER', status: 'DISABLED', page: 1, size: 10 })
    wrapper.unmount()
  })

  it('enables and deletes users', async () => {
    vi.mocked(userApi.enable).mockResolvedValue(user({ username: 'east-viewer', role: 'TENANT_VIEWER' }))
    vi.mocked(userApi.remove).mockResolvedValue(undefined)
    const { wrapper } = await mountView()
    const row = () => wrapper.findAll('.el-table__body tr')[1]

    await row().findAll('button').find((b) => b.text() === '启用')!.trigger('click')
    await settle()
    expect(userApi.enable).toHaveBeenCalledWith('east-viewer')

    await row().findAll('button').find((b) => b.text() === '删除')!.trigger('click')
    await settle()
    expect(userApi.remove).toHaveBeenCalledWith('east-viewer')
    expect(userApi.list).toHaveBeenCalledTimes(3)
    wrapper.unmount()
  })

  it('resets a password and reloads', async () => {
    vi.mocked(userApi.resetPassword).mockResolvedValue(undefined)
    const { wrapper, vm } = await mountView()

    vm.openReset(user({ username: 'east-viewer' }))
    await vm.savePassword('Reset-pass-9')

    expect(userApi.resetPassword).toHaveBeenCalledWith('east-viewer', 'Reset-pass-9')
    expect(userApi.list).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('opens the create dialog from the page header', async () => {
    const { wrapper, vm } = await mountView()

    await wrapper.findAll('button').find((b) => b.text() === '新建用户')!.trigger('click')

    expect(vm.formVisible).toBe(true)
    expect(vm.editing).toBeNull()
    wrapper.unmount()
  })
})
