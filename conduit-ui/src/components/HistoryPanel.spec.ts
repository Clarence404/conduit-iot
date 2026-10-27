/** HistoryPanel 组件测试：默认查询、自动刷新、框选缩放重新查询并暂停刷新、重置缩放、乱序响应丢弃、空数据与聚合文案 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import HistoryPanel from './HistoryPanel.vue'
import { historyApi } from '@/api/history'
import { thingModelApi } from '@/api/thingModel'
import { deviceApi } from '@/api/devices'
import { echarts } from '@/utils/echarts'
import { applyTheme } from '@/composables/useTheme'
import type { DataType, HistoryPoint, HistoryView, PointView, PropertyView, SeriesView } from '@/api/types'
import { settle } from '@/test/viewTestUtils'

/** 代替 ECharts 实例的假对象：记录 setOption，保存事件处理器以便测试触发 datazoom */
interface FakeChart {
  group: string
  handlers: Record<string, (params: unknown) => void>
  setOption: ReturnType<typeof vi.fn>
  dispatchAction: ReturnType<typeof vi.fn>
  dispose: ReturnType<typeof vi.fn>
  resize: ReturnType<typeof vi.fn>
  getDom: () => HTMLElement
  on: (name: string, handler: (params: unknown) => void) => void
}

const fake = vi.hoisted(() => ({ charts: [] as FakeChart[] }))

vi.mock('@/utils/echarts', () => ({
  echarts: {
    init: vi.fn((el: HTMLElement, _theme?: string) => {
      const chart: FakeChart = {
        group: '',
        handlers: {},
        setOption: vi.fn(),
        dispatchAction: vi.fn(),
        dispose: vi.fn(),
        resize: vi.fn(),
        getDom: () => el,
        on: (name, handler) => {
          chart.handlers[name] = handler
        },
      }
      fake.charts.push(chart)
      return chart
    }),
    connect: vi.fn(),
    disconnect: vi.fn(),
  },
}))
vi.mock('@/api/history', () => ({ historyApi: { query: vi.fn() } }))
vi.mock('@/api/thingModel', () => ({
  thingModelApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), remove: vi.fn(), exportModel: vi.fn(), importModel: vi.fn() },
}))
vi.mock('@/api/devices', () => ({
  deviceApi: { list: vi.fn(), get: vi.fn(), create: vi.fn(), update: vi.fn(), resetSecret: vi.fn(), remove: vi.fn(), latest: vi.fn() },
}))

const NOW = Date.UTC(2026, 9, 15, 8, 0, 0)
const HOUR = 3_600_000
const DEFAULT_IDS = ['Ua', 'Ub', 'Zz']

function prop(identifier: string, dataType: DataType, name: string): PropertyView {
  return {
    identifier,
    name,
    dataType,
    unit: null,
    minValue: null,
    maxValue: null,
    decimals: null,
    enumItems: null,
    description: null,
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
  }
}

function point(identifier: string, value: number): PointView {
  return { identifier, name: null, value, ts: NOW, unit: null, decimals: null, enumLabel: null }
}

function ua(points: HistoryPoint[] = [[NOW - 1000, 220.1]], mode: SeriesView['mode'] = 'RAW'): SeriesView {
  return { identifier: 'Ua', name: 'A 相电压', dataType: 'DOUBLE', unit: 'V', decimals: 1, enumItems: null, mode, points }
}

function view(patch: Partial<HistoryView> = {}): HistoryView {
  return { deviceKey: 'dk1', from: NOW - HOUR, to: NOW, bucketMs: null, series: [ua()], ...patch }
}

async function mountPanel() {
  const wrapper = mount(HistoryPanel, {
    props: { deviceKey: 'dk1', productKey: 'pk1' },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
  await settle()
  return wrapper
}

function button(wrapper: Awaited<ReturnType<typeof mountPanel>>, text: string) {
  const found = wrapper.findAll('button').find((b) => b.text() === text)
  if (!found) {
    throw new Error(`button ${text} not found`)
  }
  return found
}

describe('HistoryPanel', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'] })
    vi.setSystemTime(NOW)
    Object.defineProperty(document, 'hidden', { configurable: true, get: () => false })
    fake.charts.length = 0
    vi.clearAllMocks()
    vi.mocked(thingModelApi.list).mockResolvedValue([
      prop('Ua', 'DOUBLE', 'A 相电压'),
      prop('Ub', 'DOUBLE', 'B 相电压'),
      prop('st', 'ENUM', '运行状态'),
      prop('note', 'TEXT', '备注'),
    ])
    vi.mocked(deviceApi.latest).mockResolvedValue({ deviceKey: 'dk1', values: [point('Zz', 3)] })
    vi.mocked(historyApi.query).mockResolvedValue(view())
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('loads the last hour of the first numeric properties and draws linked charts', async () => {
    const wrapper = await mountPanel()

    expect(historyApi.query).toHaveBeenCalledTimes(1)
    expect(historyApi.query).toHaveBeenCalledWith('dk1', DEFAULT_IDS, NOW - HOUR, NOW)
    expect(wrapper.text()).toContain('原始数据')
    expect(wrapper.text()).toContain('A 相电压（Ua）')
    expect(fake.charts).toHaveLength(1)
    expect(fake.charts[0].setOption).toHaveBeenCalled()
    expect(fake.charts[0].group).toBe('history-dk1')
    expect(echarts.connect).toHaveBeenCalledWith('history-dk1')
    wrapper.unmount()
  })

  it('refreshes a quick range every 30 seconds', async () => {
    const wrapper = await mountPanel()

    await vi.advanceTimersByTimeAsync(30_000)

    expect(historyApi.query).toHaveBeenCalledTimes(2)
    expect(historyApi.query).toHaveBeenLastCalledWith('dk1', DEFAULT_IDS, NOW + 30_000 - HOUR, NOW + 30_000)
    wrapper.unmount()
  })

  it('re-queries the selected range on zoom and pauses auto refresh', async () => {
    const wrapper = await mountPanel()

    fake.charts[0].handlers.datazoom({ batch: [{ startValue: NOW - 600_000, endValue: NOW - 300_000 }] })
    await settle()

    expect(historyApi.query).toHaveBeenLastCalledWith('dk1', DEFAULT_IDS, NOW - 600_000, NOW - 300_000)
    const calls = vi.mocked(historyApi.query).mock.calls.length
    await vi.advanceTimersByTimeAsync(90_000)
    expect(historyApi.query).toHaveBeenCalledTimes(calls)
    expect(wrapper.text()).toContain('已缩放，自动刷新已暂停')
    wrapper.unmount()
  })

  it('ignores repeated zoom events from connected charts', async () => {
    const wrapper = await mountPanel()
    const event = { batch: [{ startValue: NOW - 600_000, endValue: NOW - 300_000 }] }

    fake.charts[0].handlers.datazoom(event)
    fake.charts[0].handlers.datazoom(event)
    await settle()

    expect(historyApi.query).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('widens a zoom shorter than one minute', async () => {
    const wrapper = await mountPanel()

    fake.charts[0].handlers.datazoom({ batch: [{ startValue: NOW - 100_000, endValue: NOW - 90_000 }] })
    await settle()

    expect(historyApi.query).toHaveBeenLastCalledWith('dk1', DEFAULT_IDS, NOW - 100_000, NOW - 40_000)
    wrapper.unmount()
  })

  it('reset zoom restores the quick range and resumes auto refresh', async () => {
    const wrapper = await mountPanel()
    fake.charts[0].handlers.datazoom({ batch: [{ startValue: NOW - 600_000, endValue: NOW - 300_000 }] })
    await settle()
    await vi.advanceTimersByTimeAsync(10_000)

    await button(wrapper, '重置缩放').trigger('click')
    await settle()

    const now = Date.now()
    expect(historyApi.query).toHaveBeenLastCalledWith('dk1', DEFAULT_IDS, now - HOUR, now)
    const calls = vi.mocked(historyApi.query).mock.calls.length
    await vi.advanceTimersByTimeAsync(30_000)
    expect(historyApi.query).toHaveBeenCalledTimes(calls + 1)
    expect(wrapper.text()).toContain('每 30 秒自动刷新')
    wrapper.unmount()
  })

  it('drops a response that arrives after a newer one', async () => {
    let resolveFirst: (v: HistoryView) => void = () => {}
    vi.mocked(historyApi.query)
      .mockImplementationOnce(() => new Promise<HistoryView>((resolve) => (resolveFirst = resolve)))
      .mockResolvedValueOnce(view({ bucketMs: 60_000, series: [ua([[NOW - HOUR, 220, 219, 221]], 'AGG')] }))
    const wrapper = await mountPanel()

    await button(wrapper, '刷新').trigger('click')
    await settle()
    expect(wrapper.text()).toContain('已按 1 分钟聚合（平均/最小/最大）')

    resolveFirst(view())
    await settle()
    expect(wrapper.text()).toContain('已按 1 分钟聚合（平均/最小/最大）')
    expect(wrapper.text()).not.toContain('原始数据')
    wrapper.unmount()
  })

  it('keeps the previous curves and states their real range when a refresh fails', async () => {
    const wrapper = await mountPanel()
    expect(fake.charts).toHaveLength(1)

    // 刷新失败：工具栏已显示新范围，图表仍是上一段数据，必须显式说明屏幕上是哪一段
    vi.mocked(historyApi.query).mockRejectedValueOnce(new Error('查询范围超过时序库限制，请缩短时间范围'))
    vi.advanceTimersByTime(30_000)
    await settle()

    expect(wrapper.text()).toContain('本次查询失败')
    expect(wrapper.text()).toContain('查询范围超过时序库限制')
    expect(wrapper.text()).toContain('下方仍是上次成功查询的结果')
    expect(wrapper.findComponent({ name: 'ElAlert' }).exists()).toBe(true)
    // 旧曲线保留，没有被清空
    expect(wrapper.text()).toContain('A 相电压（Ua）')

    // 恢复成功后提示消失
    vi.mocked(historyApi.query).mockResolvedValue(view())
    vi.advanceTimersByTime(30_000)
    await settle()
    expect(wrapper.text()).not.toContain('本次查询失败')
    wrapper.unmount()
  })

  it('offers a retry instead of an empty chart when the first load fails', async () => {
    vi.mocked(historyApi.query).mockRejectedValue(new Error('时序库暂不可用'))
    const wrapper = await mountPanel()

    // 首次加载失败没有可保留的旧曲线，应给出可重试的空状态而不是空白
    expect(wrapper.text()).toContain('时序库暂不可用')
    expect(wrapper.text()).not.toContain('下方仍是上次成功查询的结果')
    const retry = wrapper.findAll('button').find((b) => b.text() === '重试')
    expect(retry).toBeTruthy()

    vi.mocked(historyApi.query).mockResolvedValue(view())
    await retry!.trigger('click')
    await settle()
    expect(wrapper.text()).toContain('A 相电压（Ua）')
    wrapper.unmount()
  })

  it('shows the aggregation bucket', async () => {
    vi.mocked(historyApi.query).mockResolvedValue(
      view({ bucketMs: 300_000, series: [ua([[NOW - HOUR, 220, 219, 221]], 'AGG')] }),
    )
    const wrapper = await mountPanel()

    expect(wrapper.text()).toContain('已按 5 分钟聚合（平均/最小/最大）')
    wrapper.unmount()
  })

  it('shows an empty state instead of a chart when a series has no points', async () => {
    vi.mocked(historyApi.query).mockResolvedValue(view({ series: [ua([])] }))
    const wrapper = await mountPanel()

    expect(wrapper.text()).toContain('该时段没有数据')
    expect(fake.charts).toHaveLength(0)
    wrapper.unmount()
  })

  it('keeps the previous valid custom range when an invalid one is picked', async () => {
    const wrapper = await mountPanel()
    const picker = wrapper.findComponent({ name: 'ElDatePicker' })
    const valid: [Date, Date] = [new Date(NOW - 2 * HOUR), new Date(NOW - HOUR)]
    const tooLong: [Date, Date] = [new Date(NOW - 45 * 24 * HOUR), new Date(NOW)]

    // v-model 先写入选择器的值，随后才触发 change
    picker.vm.$emit('update:modelValue', valid)
    picker.vm.$emit('change', valid)
    await settle()
    expect(historyApi.query).toHaveBeenLastCalledWith('dk1', DEFAULT_IDS, NOW - 2 * HOUR, NOW - HOUR)

    picker.vm.$emit('update:modelValue', tooLong)
    picker.vm.$emit('change', tooLong)
    await settle()
    expect(picker.props('modelValue')).toEqual(valid)

    await button(wrapper, '刷新').trigger('click')
    await settle()
    expect(historyApi.query).toHaveBeenLastCalledWith('dk1', DEFAULT_IDS, NOW - 2 * HOUR, NOW - HOUR)
    wrapper.unmount()
  })

  it('clears the picker when an invalid range is picked in quick-range mode', async () => {
    const wrapper = await mountPanel()
    const picker = wrapper.findComponent({ name: 'ElDatePicker' })
    const tooLong: [Date, Date] = [new Date(NOW - 45 * 24 * HOUR), new Date(NOW)]

    picker.vm.$emit('update:modelValue', tooLong)
    picker.vm.$emit('change', tooLong)
    await settle()
    expect(picker.props('modelValue')).toBe('')

    await button(wrapper, '刷新').trigger('click')
    await settle()
    expect(historyApi.query).toHaveBeenLastCalledWith('dk1', DEFAULT_IDS, Date.now() - HOUR, Date.now())
    wrapper.unmount()
  })

  it('disposes the chart of a removed series, reuses the kept one and never inits an element twice', async () => {
    const ub: SeriesView = { ...ua(), identifier: 'Ub', name: 'B 相电压' }
    vi.mocked(historyApi.query).mockResolvedValueOnce(view({ series: [ua(), ub] }))
    const wrapper = await mountPanel()
    expect(fake.charts).toHaveLength(2)
    const [chartUa, chartUb] = fake.charts

    vi.mocked(historyApi.query).mockResolvedValue(view({ series: [ua()] }))
    // 选择由 Ua、Ub、Zz 改为 Ua
    wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', ['Ua'])
    await settle()

    expect(historyApi.query).toHaveBeenLastCalledWith('dk1', ['Ua'], expect.any(Number), expect.any(Number))
    expect(chartUb.dispose).toHaveBeenCalled()
    expect(chartUa.dispose).not.toHaveBeenCalled()
    expect(fake.charts).toHaveLength(2)
    expect(chartUa.setOption.mock.calls.length).toBeGreaterThan(1)
    expect(new Set(fake.charts.map((c) => c.getDom())).size).toBe(fake.charts.length)
    wrapper.unmount()
  })

  it('dispatches the cursor action without replaying it on connected charts and disconnects the group on unmount', async () => {
    const wrapper = await mountPanel()

    expect(fake.charts[0].dispatchAction).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'takeGlobalCursor', escapeConnect: true }),
    )
    wrapper.unmount()
    expect(echarts.disconnect).toHaveBeenCalledWith('history-dk1')
  })

  it('stops polling and disposes charts on unmount', async () => {
    const wrapper = await mountPanel()
    const chart = fake.charts[0]

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(90_000)

    expect(historyApi.query).toHaveBeenCalledTimes(1)
    expect(chart.dispose).toHaveBeenCalled()
  })

  it('rebuilds the charts with the ECharts dark theme when the theme changes', async () => {
    const wrapper = await mountPanel()
    const first = fake.charts[0]
    expect(vi.mocked(echarts.init).mock.calls[0][1]).toBeUndefined()

    applyTheme(true)
    await settle()

    expect(first.dispose).toHaveBeenCalled()
    expect(fake.charts).toHaveLength(2)
    expect(vi.mocked(echarts.init).mock.calls[1][1]).toBe('dark')
    expect(fake.charts[1].setOption).toHaveBeenCalled()

    applyTheme(false)
    await settle()
    wrapper.unmount()
  })

  it('starts with the properties passed in initialProps and ignores unknown ones', async () => {
    const wrapper = mount(HistoryPanel, {
      props: { deviceKey: 'dk1', productKey: 'pk1', initialProps: ['Ub', 'nope'] },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()

    expect(historyApi.query).toHaveBeenCalledWith('dk1', ['Ub'], NOW - HOUR, NOW)
    wrapper.unmount()
  })

  it('falls back to the default selection when no initial property exists', async () => {
    const wrapper = mount(HistoryPanel, {
      props: { deviceKey: 'dk1', productKey: 'pk1', initialProps: ['nope'] },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })
    await settle()

    expect(historyApi.query).toHaveBeenCalledWith('dk1', DEFAULT_IDS, NOW - HOUR, NOW)
    wrapper.unmount()
  })
})
