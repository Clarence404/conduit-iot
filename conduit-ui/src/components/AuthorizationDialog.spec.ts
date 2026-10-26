import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import AuthorizationDialog from './AuthorizationDialog.vue'
import { authorizationApi } from '@/api/authorization'
import { projectApi, siteApi } from '@/api/organization'
import { settle } from '@/test/viewTestUtils'

vi.mock('@/api/authorization', () => ({ authorizationApi: { roles: vi.fn(), bindings: vi.fn(), replace: vi.fn() } }))
vi.mock('@/api/organization', () => ({ projectApi: { list: vi.fn() }, siteApi: { list: vi.fn() } }))
type Vm = { save(): Promise<void>; ready: boolean; error: string }
function mountDialog() {
  const wrapper = mount(AuthorizationDialog, { props: { modelValue: true, username: 'member' }, global: { plugins: [ElementPlus] }, attachTo: document.body })
  return {wrapper, vm: wrapper.vm as unknown as Vm}
}
describe('AuthorizationDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(authorizationApi.roles).mockResolvedValue([{role_key:'VIEWER',name:'只读成员',scope_mask:'TENANT,PROJECT,SITE',status:'ACTIVE',permissions:['device.read']}])
    vi.mocked(projectApi.list).mockResolvedValue([])
    vi.mocked(siteApi.list).mockResolvedValue([])
    vi.mocked(authorizationApi.bindings).mockResolvedValue([{role_key:'VIEWER',scope_type:'PROJECT',project_code:'project-a'}])
    vi.mocked(authorizationApi.replace).mockResolvedValue({} as Awaited<ReturnType<typeof authorizationApi.replace>>)
  })
  it('preserves loaded project binding when saving', async () => {
    const {wrapper,vm}=mountDialog()
    await settle()
    await vm.save()
    expect(authorizationApi.replace).toHaveBeenCalledWith('member',[{roleKey:'VIEWER',scopeType:'PROJECT',projectCode:'project-a',siteCode:null}])
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
    wrapper.unmount()
  })
  it('cannot overwrite permissions with an empty list after a failed load', async () => {
    vi.mocked(authorizationApi.bindings).mockRejectedValue(new Error('offline'))
    const {wrapper,vm}=mountDialog()
    await settle()
    expect(vm.ready).toBe(false)
    await vm.save()
    expect(authorizationApi.replace).not.toHaveBeenCalled()
    expect(vm.error).toBeTruthy()
    wrapper.unmount()
  })
})
