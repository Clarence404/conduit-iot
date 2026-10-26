/**
 * DevicesView 组件测试：首屏第 1 页 10 条、筛选栏查询与重置、在线圆点与启用标签、操作列启用/禁用、
 * 翻页/空态/新建默认产品只用已应用的筛选条件、「刷新」保持当前页、首次加载失败显示「加载失败」并可重试。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import DevicesView from './DevicesView.vue'
import { deviceApi } from '@/api/devices'
import { productApi } from '@/api/products'
import type { DeviceView, Role } from '@/api/types'
import { createTestPinia, createTestRouter, settle } from '@/test/viewTestUtils'

const NOW = Date.UTC(2026, 9, 15, 8, 0, 0)

vi.mock('@/api/devices', () => ({
  deviceApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), resetSecret: vi.fn(), remove: vi.fn(), latest: vi.fn() },
}))
vi.mock('@/api/products', () => ({
  productApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn() },
}))

function device(patch: Partial<DeviceView> = {}): DeviceView {
  return {
    deviceKey: 'dk1',
    name: '1号楼配电室 电表01',
    productKey: 'pk1',
    status: 'ENABLED',
    online: true,
    lastReportAt: NOW - 120_000,
    createdAt: '2026-10-01T00:00:00Z',
    ...patch,
  }
}

async function mountView(url = '/devices', role: Role = 'TENANT_ADMIN') {
  const router = createTestRouter()
  await router.push(url)
  await router.isReady()
  const wrapper = mount(DevicesView, {
    global: { plugins: [createTestPinia({ role }), router, ElementPlus] },
    attachTo: document.body,
  })
  await settle()
  return { wrapper, router }
}

describe('DevicesView', () => {
  beforeEach(() => {
    // 只固定 Date：「2 分钟前」依赖当前时间，不能随运行时刻漂移；其余计时器保持真实
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(NOW)
    vi.clearAllMocks()
    vi.mocked(productApi.list).mockResolvedValue({
      items: [
        {
          productKey: 'pk1',
          name: '三相电表（演示）',
          description: null,
          codec: 'standard-json',
          propertyCount: 8,
          deviceCount: 1,
          onlineCount: 1,
          createdAt: '2026-10-01T00:00:00Z',
        },
      ],
      total: 1,
      page: 1,
      size: 100,
    })
    vi.mocked(deviceApi.list).mockResolvedValue({ items: [device()], total: 1, page: 1, size: 10 })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('loads the first page with 10 rows and no filters', async () => {
    const { wrapper } = await mountView()

    expect(deviceApi.list).toHaveBeenCalledWith({ productKey: undefined, keyword: undefined, page: 1, size: 10 })
    wrapper.unmount()
  })
  it('shows write buttons only for the device with a writable grant', async () => {
    vi.mocked(deviceApi.list).mockResolvedValue({items: [device({ permissions: ['device.manage', 'device.secret.rotate'] }), device({ deviceKey: 'dk2', permissions: ['device.read'] })], total: 2, page: 1, size: 10})
    const { wrapper } = await mountView()
    const rows=wrapper.findAll('.el-table__body tbody tr')
    expect(rows[0]!.text()).toContain('重置密钥')
    expect(rows[1]!.text()).not.toContain('重置密钥')
    expect(rows[1]!.text()).not.toContain('删除')
    wrapper.unmount()
  })

  it('searches with the keyword and resets the filters', async () => {
    const { wrapper } = await mountView()

    await wrapper.find('.filter-bar .keyword input').setValue(' 电表01 ')
    await wrapper.find('.filter-bar').trigger('submit')
    await settle()
    expect(deviceApi.list).toHaveBeenLastCalledWith({ productKey: undefined, keyword: '电表01', page: 1, size: 10 })

    await wrapper.findAll('.filter-bar button').find((b) => b.text() === '重置')!.trigger('click')
    await settle()
    expect(deviceApi.list).toHaveBeenLastCalledWith({ productKey: undefined, keyword: undefined, page: 1, size: 10 })
    wrapper.unmount()
  })

  it('shows the online dot, the enabled tag, the product name and the relative report time', async () => {
    const { wrapper } = await mountView()

    const text = wrapper.find('.el-table').text()
    expect(text).toContain('在线')
    expect(text).toContain('启用')
    expect(text).toContain('三相电表（演示）')
    expect(text).toContain('2 分钟前')
    wrapper.unmount()
  })

  it('disables a device from the action column', async () => {
    vi.mocked(deviceApi.update).mockResolvedValue(device({ status: 'DISABLED' }))
    const { wrapper } = await mountView()

    await wrapper.findAll('.el-table button').find((b) => b.text() === '禁用')!.trigger('click')
    await settle()

    expect(deviceApi.update).toHaveBeenCalledWith('dk1', '1号楼配电室 电表01', 'DISABLED')
    expect(wrapper.find('.el-table').text()).toContain('禁用')
    expect(wrapper.findAll('.el-table button').some((b) => b.text() === '启用')).toBe(true)
    wrapper.unmount()
  })
  it('keeps using the applied filters, not unsubmitted input, for paging and the create default', async () => {
    const { wrapper } = await mountView()
    const vm = wrapper.vm as unknown as {
      filters: { productKey: string; keyword: string }
      filtered: boolean
      createForm: { productKey: string }
      openCreate(): void
    }

    // 已应用：电表01；之后又在输入框里改了关键字和产品但没有点查询
    await wrapper.find('.filter-bar .keyword input').setValue('电表01')
    await wrapper.find('.filter-bar').trigger('submit')
    await settle()
    await wrapper.find('.filter-bar .keyword input').setValue('别的')
    vm.filters.productKey = 'pk-other'

    expect(vm.filtered).toBe(true)
    vm.openCreate()
    expect(vm.createForm.productKey).toBe('pk1')

    wrapper.findComponent({ name: 'ElPagination' }).vm.$emit('current-change', 2)
    await settle()
    expect(deviceApi.list).toHaveBeenLastCalledWith({ productKey: undefined, keyword: '电表01', page: 1, size: 10 })
    wrapper.unmount()
  })

  it('does not treat unsubmitted input as a filter', async () => {
    const { wrapper } = await mountView()
    const vm = wrapper.vm as unknown as {
      filters: { productKey: string; keyword: string }
      filtered: boolean
      createForm: { productKey: string }
      openCreate(): void
    }

    await wrapper.find('.filter-bar .keyword input').setValue('还没查询')
    vm.filters.productKey = 'pk-other'

    expect(vm.filtered).toBe(false)
    vm.openCreate()
    expect(vm.createForm.productKey).toBe('pk1')
    wrapper.unmount()
  })

  it('refreshes the current page without going back to page 1', async () => {
    vi.mocked(deviceApi.list).mockResolvedValue({ items: [device()], total: 35, page: 3, size: 10 })
    const { wrapper } = await mountView()
    const vm = wrapper.vm as unknown as { page: number }
    vm.page = 3
    await settle()

    await wrapper.findAll('button').find((b) => b.text() === '刷新')!.trigger('click')
    await settle()

    expect(deviceApi.list).toHaveBeenLastCalledWith({ productKey: undefined, keyword: undefined, page: 3, size: 10 })
    wrapper.unmount()
  })

  it('shows a retryable failure state instead of the create guide when the first load fails', async () => {
    vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: () => {} }) as never)
    vi.mocked(deviceApi.list).mockRejectedValueOnce(new Error('服务不可用'))
    const { wrapper } = await mountView()

    const empty = wrapper.find('.el-empty')
    expect(empty.text()).toContain('加载失败')
    expect(empty.text()).not.toContain('还没有设备')

    await empty.findAll('button').find((b) => b.text() === '重试')!.trigger('click')
    await settle()

    expect(deviceApi.list).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.el-table').text()).toContain('1号楼配电室 电表01')
    wrapper.unmount()
  })

  /** 在 el-select 里选择某一项：打开下拉后点击含该文案的选项 */
  async function pick(wrapper: Awaited<ReturnType<typeof mountView>>['wrapper'], testId: string, label: string) {
    const select = wrapper.find(`[data-test="${testId}"]`)
    await select.find('.el-select__wrapper').trigger('click')
    await settle()
    const opts = Array.from(document.body.querySelectorAll('.el-select-dropdown__item')).filter((el) => el.textContent?.trim() === label)
    const opt = opts[opts.length - 1] as HTMLElement
    opt.click()
    await settle()
  }

  it('sends online and status filters and syncs them to the route query', async () => {
    const { wrapper, router } = await mountView()
    await pick(wrapper, 'filter-online', '在线')
    await pick(wrapper, 'filter-status', '禁用')
    await wrapper.find('.filter-bar').trigger('submit')
    await settle()

    expect(deviceApi.list).toHaveBeenLastCalledWith({ online: true, status: 'DISABLED', page: 1, size: 10 })
    expect(router.currentRoute.value.query).toEqual({ online: 'true', status: 'DISABLED' })
    wrapper.unmount()
  })

  it('reveals the second row with 更多筛选 and sends the last-report filter', async () => {
    const { wrapper, router } = await mountView()
    expect(wrapper.find('[data-test="filter-reported"]').exists()).toBe(false)

    await wrapper.find('[data-test="more-toggle"]').trigger('click')
    await settle()
    await pick(wrapper, 'filter-reported', '从未上报')
    await wrapper.find('.filter-bar').trigger('submit')
    await settle()

    expect(deviceApi.list).toHaveBeenLastCalledWith({ reported: 'NEVER', page: 1, size: 10 })
    expect(router.currentRoute.value.query).toEqual({ reported: 'NEVER' })
    wrapper.unmount()
  })

  it('converts the creation date range to local day boundaries', async () => {
    const { wrapper, router } = await mountView()
    await wrapper.find('[data-test="more-toggle"]').trigger('click')
    await settle()
    const vm = wrapper.vm as unknown as { createdRange: [Date, Date] | null }
    vm.createdRange = [new Date(2026, 9, 1), new Date(2026, 9, 3)]
    await settle()
    await wrapper.find('.filter-bar').trigger('submit')
    await settle()

    const from = new Date(2026, 9, 1).getTime()
    const to = new Date(2026, 9, 4).getTime()
    expect(deviceApi.list).toHaveBeenLastCalledWith({ createdFrom: from, createdTo: to, page: 1, size: 10 })
    expect(router.currentRoute.value.query).toEqual({ createdFrom: String(from), createdTo: String(to) })
    wrapper.unmount()
  })

  it('restores the filters from the route query on load', async () => {
    const from = new Date(2026, 9, 1).getTime()
    const to = new Date(2026, 9, 4).getTime()
    const { wrapper } = await mountView(`/devices?online=true&status=ENABLED&reported=STALE&keyword=%E7%94%B5%E8%A1%A8&createdFrom=${from}&createdTo=${to}`)

    expect(deviceApi.list).toHaveBeenCalledTimes(1)
    expect(deviceApi.list).toHaveBeenCalledWith({
      keyword: '电表',
      online: true,
      status: 'ENABLED',
      reported: 'STALE',
      createdFrom: from,
      createdTo: to,
      page: 1,
      size: 10,
    })
    expect(wrapper.find('.filter-bar .keyword input').element).toHaveProperty('value', '电表')
    expect(wrapper.find('[data-test="filter-reported"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="more-toggle"]').text()).toContain('2')
    wrapper.unmount()
  })

  it('applies /devices?online=true and ignores invalid query values', async () => {
    const { wrapper } = await mountView('/devices?online=true&status=bogus&createdFrom=x')
    expect(deviceApi.list).toHaveBeenCalledWith({ online: true, page: 1, size: 10 })
    expect(wrapper.find('[data-test="more-toggle"]').text()).not.toContain('(')
    wrapper.unmount()
  })

  it('reset clears every filter, the query and the badge, and shows the no-match empty state only when filtered', async () => {
    const { wrapper, router } = await mountView('/devices?online=true&reported=NEVER')
    vi.mocked(deviceApi.list).mockResolvedValue({ items: [], total: 0, page: 1, size: 10 })
    await wrapper.find('.filter-bar').trigger('submit')
    await settle()
    expect(wrapper.find('.el-empty').text()).toContain('没有符合条件的设备')

    await wrapper.findAll('.filter-bar button').find((b) => b.text() === '重置')!.trigger('click')
    await settle()

    expect(deviceApi.list).toHaveBeenLastCalledWith({ page: 1, size: 10 })
    expect(router.currentRoute.value.query).toEqual({})
    expect(wrapper.find('[data-test="more-toggle"]').text()).not.toContain('(')
    expect(wrapper.find('.el-empty').text()).toContain('还没有设备')
    wrapper.unmount()
  })

  it('shows the applied hidden-filter count, not the edited one, and never collapses on edit', async () => {
    const { wrapper } = await mountView()
    const toggle = () => wrapper.find('[data-test="more-toggle"]')
    expect(toggle().text()).toContain('更多筛选')
    expect(toggle().text()).not.toContain('(')

    await toggle().trigger('click')
    await settle()
    const vm = wrapper.vm as unknown as { filters: { reported: string } }
    vm.filters.reported = 'RECENT'
    await settle()
    // 还没点查询：徽标不变，行保持展开
    expect(toggle().text()).not.toContain('(')
    expect(wrapper.find('[data-test="filter-reported"]').exists()).toBe(true)

    await wrapper.find('.filter-bar').trigger('submit')
    await settle()
    expect(toggle().text()).toContain('(1)')
    wrapper.unmount()
  })

  it('collapses with the toggle when no applied hidden filter is active', async () => {
    const { wrapper } = await mountView()
    const toggle = () => wrapper.find('[data-test="more-toggle"]')
    await toggle().trigger('click')
    await settle()
    expect(wrapper.find('[data-test="filter-reported"]').exists()).toBe(true)
    await toggle().trigger('click')
    await settle()
    expect(wrapper.find('[data-test="filter-reported"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('disables the toggle while an applied hidden filter forces the row open, and keeps it open after reset', async () => {
    const { wrapper } = await mountView('/devices?reported=NEVER')
    const toggle = () => wrapper.find('[data-test="more-toggle"]')
    expect(toggle().attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-test="filter-reported"]').exists()).toBe(true)

    await wrapper.findAll('.filter-bar button').find((b) => b.text() === '重置')!.trigger('click')
    await settle()
    expect(toggle().attributes('disabled')).toBeUndefined()
    expect(wrapper.find('[data-test="filter-reported"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('re-applies the filters when the route query changes while the page stays mounted', async () => {
    const { wrapper, router } = await mountView('/devices?online=true&reported=NEVER')
    expect(deviceApi.list).toHaveBeenCalledTimes(1)

    await router.push('/devices')
    await settle()

    expect(deviceApi.list).toHaveBeenCalledTimes(2)
    expect(deviceApi.list).toHaveBeenLastCalledWith({ page: 1, size: 10 })
    expect(wrapper.find('[data-test="more-toggle"]').text()).not.toContain('(')

    await router.push('/devices?status=DISABLED')
    await settle()
    expect(deviceApi.list).toHaveBeenLastCalledWith({ status: 'DISABLED', page: 1, size: 10 })
    wrapper.unmount()
  })

  it('does not load twice or loop when search() writes the query itself', async () => {
    const { wrapper } = await mountView()
    await wrapper.find('.filter-bar .keyword input').setValue('电表')
    await wrapper.find('.filter-bar').trigger('submit')
    await settle()
    expect(deviceApi.list).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('rewrites the URL to drop ignored invalid query values', async () => {
    const { wrapper, router } = await mountView('/devices?online=true&status=bogus&createdFrom=x')
    await settle()
    expect(router.currentRoute.value.query).toEqual({ online: 'true' })
    expect(deviceApi.list).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('shows the failure state, not the previous rows, when a later query fails', async () => {
    vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: () => {} }) as never)
    const { wrapper } = await mountView()
    expect(wrapper.find('.el-table').text()).toContain('1号楼配电室 电表01')

    vi.mocked(deviceApi.list).mockRejectedValueOnce(new Error('在线状态暂不可用'))
    await wrapper.find('.filter-bar').trigger('submit')
    await settle()

    expect(wrapper.find('.el-table').text()).not.toContain('1号楼配电室 电表01')
    expect(wrapper.find('.el-empty').text()).toContain('加载失败')
    wrapper.unmount()
  })

  it('shows the backend error through the existing error path when the online state is unavailable', async () => {
    const spy = vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close: () => {} }) as never)
    vi.mocked(deviceApi.list).mockRejectedValueOnce(new Error('在线状态暂不可用'))
    const { wrapper } = await mountView('/devices?online=true')
    expect(spy).toHaveBeenCalledWith('在线状态暂不可用')
    wrapper.unmount()
  })

  it('hides the write actions from a viewer', async () => {
    const { wrapper } = await mountView('/devices', 'TENANT_VIEWER')

    expect(wrapper.findAll('.el-table button').map((b) => b.text())).toEqual(['详情'])
    const labels = wrapper.findAll('button').map((b) => b.text())
    expect(labels).toContain('刷新')
    expect(labels).not.toContain('新建设备')
    wrapper.unmount()
  })

  it('shows neutral empty-state text without create hints to a viewer, and the guide to an admin', async () => {
    vi.mocked(deviceApi.list).mockResolvedValue({ items: [], total: 0, page: 1, size: 10 })
    const viewer = await mountView('/devices', 'TENANT_VIEWER')
    expect(viewer.wrapper.find('.el-empty').text()).toContain('暂无设备')
    expect(viewer.wrapper.find('.el-empty').text()).not.toContain('新建')
    expect(viewer.wrapper.find('.el-empty').text()).not.toContain('创建')
    viewer.wrapper.unmount()

    const admin = await mountView('/devices', 'TENANT_ADMIN')
    expect(admin.wrapper.find('.el-empty').text()).toContain('还没有设备，先新建一台设备')
    admin.wrapper.unmount()
  })
})
