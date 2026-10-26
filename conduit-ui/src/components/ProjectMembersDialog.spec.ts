import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import ProjectMembersDialog from './ProjectMembersDialog.vue'
import { authorizationApi } from '@/api/authorization'
import { siteApi } from '@/api/organization'
import { settle } from '@/test/viewTestUtils'

vi.mock('@/api/authorization', () => ({ authorizationApi: {
  projectMembers: vi.fn(), projectRoles: vi.fn(), projectBindings: vi.fn(), replaceProjectBindings: vi.fn(),
} }))
vi.mock('@/api/organization', () => ({ siteApi: { list: vi.fn() } }))
type Vm = { ready: boolean; edit(target: string): Promise<void>; save(): Promise<void> }
describe('ProjectMembersDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(authorizationApi.projectMembers).mockResolvedValue([{ username: 'member', display_name: '成员', status: 'ACTIVE' }])
    vi.mocked(authorizationApi.projectRoles).mockResolvedValue([{ role_key: 'VIEWER', name: '只读', scope_mask: 'PROJECT', status: 'ACTIVE', built_in: true, permissions: ['device.read'] }])
    vi.mocked(siteApi.list).mockResolvedValue([])
    vi.mocked(authorizationApi.projectBindings).mockResolvedValue([{ role_key: 'VIEWER', scope_type: 'PROJECT', project_code: 'project-a' }])
    vi.mocked(authorizationApi.replaceProjectBindings).mockResolvedValue({} as Awaited<ReturnType<typeof authorizationApi.replaceProjectBindings>>)
  })
  it('saves only the selected project bindings', async () => {
    const wrapper = mount(ProjectMembersDialog, { props: { modelValue: true, projectCode: 'project-a', projectName: '项目 A' }, global: { plugins: [ElementPlus] }, attachTo: document.body })
    const vm = wrapper.vm as unknown as Vm
    await settle()
    await vm.edit('member')
    expect(vm.ready).toBe(true)
    await vm.save()
    expect(authorizationApi.replaceProjectBindings).toHaveBeenCalledWith('project-a', 'member',
      [{ roleKey: 'VIEWER', scopeType: 'PROJECT', projectCode: 'project-a', siteCode: null }])
    wrapper.unmount()
  })
})
