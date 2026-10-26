import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import OrganizationView from './OrganizationView.vue'
import { assetApi, customerApi, projectApi, siteApi } from '@/api/organization'
import { createTestPinia, createTestRouter } from '@/test/viewTestUtils'

vi.mock('@/api/organization', () => ({
  customerApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
  projectApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
  siteApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
  assetApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
}))

describe('OrganizationView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(customerApi.list).mockResolvedValue([])
    vi.mocked(projectApi.list).mockResolvedValue([{ code: 'default', name: '默认项目', customerCode: null, status: 'ACTIVE', systemDefault: true, createdAt: '' }])
    vi.mocked(siteApi.list).mockResolvedValue([{ code: 'default', name: '默认站点', projectCode: 'default', timezone: 'Asia/Shanghai', status: 'ACTIVE', systemDefault: true, createdAt: '' }])
    vi.mocked(assetApi.list).mockResolvedValue([])
  })

  async function render(role: 'TENANT_ADMIN' | 'TENANT_VIEWER' = 'TENANT_ADMIN') {
    const router = createTestRouter()
    await router.push('/organization')
    const wrapper = mount(OrganizationView, { global: { plugins: [createTestPinia({ role }), router, ElementPlus] } })
    await flushPromises()
    return wrapper
  }

  it('loads all organization levels and shows the default project', async () => {
    const wrapper = await render()
    expect(customerApi.list).toHaveBeenCalledOnce()
    expect(projectApi.list).toHaveBeenCalledOnce()
    expect(siteApi.list).toHaveBeenCalledOnce()
    expect(assetApi.list).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('默认项目')
    expect(wrapper.text()).toContain('默认')
    expect(wrapper.text()).toContain('新建项目')
  })

  it('keeps organization data visible but hides writes from a viewer', async () => {
    const wrapper = await render('TENANT_VIEWER')
    expect(wrapper.text()).toContain('默认项目')
    expect(wrapper.text()).not.toContain('新建项目')
    expect(wrapper.text()).not.toContain('编辑')
  })
})
