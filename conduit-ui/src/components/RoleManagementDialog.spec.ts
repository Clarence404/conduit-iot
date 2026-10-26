import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import RoleManagementDialog from './RoleManagementDialog.vue'
import { authorizationApi } from '@/api/authorization'
import { settle } from '@/test/viewTestUtils'

vi.mock('@/api/authorization', () => ({ authorizationApi: {
  roles: vi.fn(), permissionCatalog: vi.fn(), createRole: vi.fn(), updateRole: vi.fn(), deleteRole: vi.fn(),
} }))
type Vm = { ready: boolean; form: { roleKey: string; name: string; scopeMask: string; status: string; permissions: string[] }; create(): void; save(): Promise<void> }
describe('RoleManagementDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(authorizationApi.roles).mockResolvedValue([])
    vi.mocked(authorizationApi.permissionCatalog).mockResolvedValue(['device.read'])
    vi.mocked(authorizationApi.createRole).mockResolvedValue({} as Awaited<ReturnType<typeof authorizationApi.createRole>>)
  })
  it('creates a custom role only after loading the permission catalog', async () => {
    const wrapper = mount(RoleManagementDialog, { props: { modelValue: true }, global: { plugins: [ElementPlus] }, attachTo: document.body })
    const vm = wrapper.vm as unknown as Vm
    await settle()
    expect(vm.ready).toBe(true)
    vm.create()
    vm.form = { roleKey: 'CUSTOM_INSPECTOR', name: '巡检', scopeMask: 'PROJECT', status: 'ACTIVE', permissions: ['device.read'] }
    await vm.save()
    expect(authorizationApi.createRole).toHaveBeenCalledWith(vm.form)
    wrapper.unmount()
  })
})
