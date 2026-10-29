/** PlatformHomeView 组件测试：4 个指标卡的数值与副文、实时吞吐一行（速率未知与真实零的区分）、在线相关为 null 时显示「—」、点击租户卡与租户行进入租户管理（带筛选）、每 30 秒刷新 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import PlatformHomeView from './PlatformHomeView.vue'
import { platformApi } from '@/api/platform'
import type { PlatformOverview } from '@/api/types'
import { createTestPinia, createTestRouter, settle } from '@/test/viewTestUtils'

vi.mock('@/api/platform', () => ({ platformApi: { overview: vi.fn() } }))

function overview(patch: Partial<PlatformOverview> = {}): PlatformOverview {
  return {
    tenantCount: 3,
    activeTenantCount: 2,
    disabledTenantCount: 1,
    userCount: 12,
    productCount: 4,
    deviceCount: 200,
    onlineCount: 100,
    topTenants: [
      { code: 'east', name: '验收租户（华东）', deviceCount: 150, onlineCount: 80 },
      { code: 'default', name: 'Default', deviceCount: 50, onlineCount: 20 },
    ],
    throughput: { ingestTps: 1234.5, peakTps: 2780, ingestTotal: 1234567, deadTotal: 0, windowSeconds: 60 },
    ...patch,
  }
}

async function mountView() {
  const pinia = createTestPinia({ username: 'sysadmin', role: 'PLATFORM_ADMIN', tenantCode: null, tenantName: null })
  const router = createTestRouter()
  await router.push('/home')
  await router.isReady()
  const wrapper = mount(PlatformHomeView, { global: { plugins: [pinia, router, ElementPlus] }, attachTo: document.body })
  await settle()
  return { wrapper, router }
}

const tile = (wrapper: Awaited<ReturnType<typeof mountView>>['wrapper'], name: string) =>
  wrapper.find(`[data-test="tile-${name}"]`).text()

describe('PlatformHomeView', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false })
    vi.clearAllMocks()
    vi.mocked(platformApi.overview).mockResolvedValue(overview())
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders the four tiles', async () => {
    const { wrapper } = await mountView()

    expect(tile(wrapper, 'tenants')).toContain('3')
    expect(tile(wrapper, 'tenants')).toContain('启用 2 · 停用 1')
    expect(tile(wrapper, 'users')).toContain('12')
    expect(tile(wrapper, 'devices')).toContain('200')
    expect(tile(wrapper, 'devices')).toContain('共 4 个产品')
    expect(tile(wrapper, 'online')).toContain('50%')
    expect(tile(wrapper, 'online')).toContain('在线 100 / 设备 200')
    expect(wrapper.find('.top-tenants').text()).toContain('80 / 150')
    wrapper.unmount()
  })

  it('shows — when the online state is unknown', async () => {
    vi.mocked(platformApi.overview).mockResolvedValue(
      overview({ onlineCount: null, topTenants: [{ code: 'east', name: '验收租户（华东）', deviceCount: 150, onlineCount: null }] }),
    )
    const { wrapper } = await mountView()

    expect(tile(wrapper, 'online')).toContain('—')
    expect(tile(wrapper, 'online')).toContain('在线状态暂不可用')
    expect(wrapper.find('.top-tenants').text()).toContain('— / 150')
    wrapper.unmount()
  })

  it('opens the tenant list, filtered when a top tenant is clicked', async () => {
    const { wrapper, router } = await mountView()

    await wrapper.find('.top-tenants .row').trigger('click')
    await settle()
    expect(router.currentRoute.value.name).toBe('tenants')
    expect(router.currentRoute.value.query).toEqual({ keyword: 'east' })

    await router.push('/home')
    await wrapper.find('[data-test="tile-tenants"]').trigger('click')
    await settle()
    expect(router.currentRoute.value.fullPath).toBe('/tenants')
    wrapper.unmount()
  })

  it('refreshes every 30 seconds and stops on unmount', async () => {
    const { wrapper } = await mountView()
    expect(platformApi.overview).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(30_000)
    expect(platformApi.overview).toHaveBeenCalledTimes(2)

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(90_000)
    expect(platformApi.overview).toHaveBeenCalledTimes(2)
  })

  it('shows a retryable failure state instead of the skeleton when the first load fails', async () => {
    vi.mocked(platformApi.overview).mockRejectedValueOnce(new Error('服务暂不可用'))
    const { wrapper } = await mountView()

    expect(wrapper.find('.el-skeleton').exists()).toBe(false)
    const empty = wrapper.find('.el-empty')
    expect(empty.text()).toContain('加载失败，点击重试')

    await empty.findAll('button').find((b) => b.text() === '重试')!.trigger('click')
    await settle()

    expect(platformApi.overview).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.el-empty').exists()).toBe(false)
    expect(tile(wrapper, 'tenants')).toContain('3')
    wrapper.unmount()
  })

  it('keeps list semantics: each tenant row is a button inside a li, not a li with role=button', async () => {
    const { wrapper, router } = await mountView()

    expect(wrapper.findAll('.top-tenants li[role]')).toHaveLength(0)
    const rows = wrapper.findAll('.top-tenants li > button.row')
    expect(rows).toHaveLength(2)
    expect(rows[0].attributes('type')).toBe('button')

    await rows[1].trigger('click')
    await settle()
    expect(router.currentRoute.value.query).toEqual({ keyword: 'default' })
    wrapper.unmount()
  })

  it('opens the tenant list from the tenant tile with Enter and Space', async () => {
    const { wrapper, router } = await mountView()
    const tenants = wrapper.find('[data-test="tile-tenants"]')
    expect(tenants.attributes('role')).toBe('button')

    await tenants.trigger('keydown', { key: ' ' })
    await settle()
    expect(router.currentRoute.value.name).toBe('tenants')

    await router.push('/home')
    await tenants.trigger('keydown', { key: 'Enter' })
    await settle()
    expect(router.currentRoute.value.name).toBe('tenants')
    wrapper.unmount()
  })

  it('renders the throughput row', async () => {
    const { wrapper } = await mountView()

    expect(tile(wrapper, 'tps')).toContain('1,234.5')
    expect(tile(wrapper, 'tps')).toContain('近 60 秒平均')
    expect(tile(wrapper, 'peak')).toContain('2,780')
    expect(tile(wrapper, 'ingested')).toContain('1,234,567')
    expect(tile(wrapper, 'dead')).toContain('0')
  })

  it('tells "sampling" apart from a real zero rate', async () => {
    // 采样不足：速率为 null，显示「—」并说明还取不到速率，绝不能显示成 0
    vi.mocked(platformApi.overview).mockResolvedValue(
      overview({ throughput: { ingestTps: null, peakTps: null, ingestTotal: 0, deadTotal: 0, windowSeconds: 60 } }),
    )
    const { wrapper } = await mountView()

    expect(tile(wrapper, 'tps')).toContain('—')
    expect(tile(wrapper, 'tps')).toContain('采样中')
    expect(tile(wrapper, 'tps')).not.toContain('近 60 秒平均')
  })

  it('shows a real zero rate as 0', async () => {
    vi.mocked(platformApi.overview).mockResolvedValue(
      overview({ throughput: { ingestTps: 0, peakTps: 120, ingestTotal: 900, deadTotal: 0, windowSeconds: 60 } }),
    )
    const { wrapper } = await mountView()

    expect(tile(wrapper, 'tps')).toContain('0')
    expect(tile(wrapper, 'tps')).toContain('近 60 秒平均')
  })
})
