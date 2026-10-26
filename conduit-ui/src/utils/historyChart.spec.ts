/** historyChart 的单元测试：原始折线、波动带、阶梯线与枚举刻度、空数据、提示文本、缩放事件解析 */
import { describe, expect, it } from 'vitest'
import type { EChartsCoreOption } from 'echarts/core'
import type { SeriesView } from '@/api/types'
import {
  axisMin,
  buildChartOption,
  isStateSeries,
  pointText,
  seriesLabel,
  stateLabel,
  zoomRangeFromEvent,
  type ChartContext,
} from './historyChart'

interface ChartParts {
  xAxis: { type: string; min: number; max: number }
  yAxis: { name?: string; min?: number; max?: number; interval?: number; axisLabel: { formatter: (v: number) => string } }
  series: Array<{ name: string; stack?: string; stackStrategy?: string; step?: string; areaStyle?: unknown; data: unknown[][] }>
  tooltip: { formatter: (params: unknown) => string }
  toolbox: { feature: { dataZoom: unknown } }
}

const parts = (o: EChartsCoreOption) => o as unknown as ChartParts
const RAW_CTX: ChartContext = { from: 1_000_000, to: 4_600_000, bucketMs: null }
const AGG_CTX: ChartContext = { from: 1_000_000, to: 4_600_000, bucketMs: 300_000 }

function series(patch: Partial<SeriesView> = {}): SeriesView {
  return {
    identifier: 'Ua',
    name: 'A 相电压',
    dataType: 'DOUBLE',
    unit: 'V',
    decimals: 1,
    enumItems: null,
    mode: 'RAW',
    points: [
      [1_000_000, 220.12],
      [1_010_000, 221],
    ],
    ...patch,
  }
}

const ST = series({
  identifier: 'st',
  name: '运行状态',
  dataType: 'ENUM',
  unit: null,
  decimals: null,
  enumItems: [
    { value: 0, label: '停机' },
    { value: 1, label: '运行' },
    { value: 2, label: '检修' },
  ],
  mode: 'LAST',
  points: [
    [900_000, 1],
    [1_200_000, 2],
  ],
})

describe('labels and classification', () => {
  it('labels with name and identifier, or identifier only', () => {
    expect(seriesLabel(series())).toBe('A 相电压（Ua）')
    expect(seriesLabel(series({ name: null }))).toBe('Ua')
  })

  it('treats BOOL, ENUM and LAST as state', () => {
    expect(isStateSeries(series())).toBe(false)
    expect(isStateSeries(series({ dataType: 'BOOL' }))).toBe(true)
    expect(isStateSeries(ST)).toBe(true)
  })

  it('aligns the axis start to the bucket when aggregated', () => {
    expect(axisMin(RAW_CTX)).toBe(1_000_000)
    expect(axisMin(AGG_CTX)).toBe(900_000)
  })
})

describe('numeric charts', () => {
  it('keeps null gaps in all band lines and preserves reported zero', () => {
    const s = series({ mode: 'AGG', points: [[0, 5, 4, 6], [5, null, null, null], [10, 0, 0, 0], [15, 2, 1, 3]] })
    const o = parts(buildChartOption(s, AGG_CTX))
    for (const line of o.series) {
      expect(line).toMatchObject({ connectNulls: false })
      expect(line.data[1]).toEqual([5, null])
      expect(line.data[2]).toEqual([10, 0])
    }
    expect(pointText(s, s.points[1])).toBe('无数据')
    expect(pointText(s, s.points[2])).toContain('0.0 V')
  })

  it('preserves null and false in state lines and null in raw numeric lines', () => {
    const s = series({ dataType: 'BOOL', points: [[0, true], [5, null], [10, false]] })
    expect(parts(buildChartOption(s, RAW_CTX)).series[0]).toMatchObject({
      connectNulls: false, data: [[0, 1], [5, null], [10, 0]],
    })
    expect(pointText(s, [5, null])).toBe('无数据')
    expect(parts(buildChartOption(series({ points: [[0, 1], [5, null], [10, 0]] }), RAW_CTX)).series[0])
      .toMatchObject({ connectNulls: false, data: [[0, 1], [5, null], [10, 0]] })
  })
  it('draws one line for raw data with the unit as axis name', () => {
    const o = parts(buildChartOption(series(), RAW_CTX))

    expect(o.series).toHaveLength(1)
    expect(o.series[0].data).toEqual([
      [1_000_000, 220.12],
      [1_010_000, 221],
    ])
    expect(o.series[0].stack).toBeUndefined()
    expect(o.yAxis.name).toBe('V')
    expect(o.xAxis).toMatchObject({ type: 'time', min: 1_000_000, max: 4_600_000 })
    expect(o.toolbox.feature.dataZoom).toBeDefined()
  })

  it('draws a min-max band under the average when aggregated', () => {
    const o = parts(
      buildChartOption(series({ mode: 'AGG', points: [[900_000, 220, 219.5, 221]] }), AGG_CTX),
    )

    expect(o.series.map((s) => s.name)).toEqual(['最小', '波动范围', '平均'])
    expect(o.series[0]).toMatchObject({ stack: 'band', data: [[900_000, 219.5]] })
    expect(o.series[1]).toMatchObject({ stack: 'band', data: [[900_000, 1.5]] })
    expect(o.series[1].areaStyle).toBeDefined()
    expect(o.series[2].data).toEqual([[900_000, 220]])
    expect(o.xAxis.min).toBe(900_000)
  })

  it('stacks the band with every sign so a negative minimum stays correct', () => {
    const o = parts(buildChartOption(series({ mode: 'AGG', points: [[900_000, -1, -5, 3]] }), AGG_CTX))

    expect(o.series[0]).toMatchObject({ stackStrategy: 'all', data: [[900_000, -5]] })
    expect(o.series[1]).toMatchObject({ stackStrategy: 'all', data: [[900_000, 8]] })
  })

  it('formats tooltip values with decimals and unit', () => {
    expect(pointText(series(), [1_000_000, 220.12])).toBe('220.1 V')
    expect(pointText(series({ mode: 'AGG' }), [900_000, 220, 219.5, 221])).toBe('平均 220.0 V · 最小 219.5 V · 最大 221.0 V')
    const o = parts(buildChartOption(series(), RAW_CTX))
    expect(o.tooltip.formatter([{ dataIndex: 0 }])).toContain('220.1 V')
    expect(o.tooltip.formatter([{ dataIndex: 99 }])).toBe('')
  })
})

describe('state charts', () => {
  it('draws an end-step line with enum labels on the axis', () => {
    const o = parts(buildChartOption(ST, AGG_CTX))

    expect(o.series).toHaveLength(1)
    expect(o.series[0].step).toBe('end')
    expect(o.series[0].data).toEqual([
      [900_000, 1],
      [1_200_000, 2],
    ])
    expect(o.yAxis).toMatchObject({ min: 0, max: 2, interval: 1 })
    expect(o.yAxis.axisLabel.formatter(1)).toBe('运行')
    expect(o.yAxis.axisLabel.formatter(2)).toBe('检修')
  })

  it('maps booleans to 1/0 with 否/是 ticks', () => {
    const flag = series({ identifier: 'on', dataType: 'BOOL', unit: null, mode: 'RAW', points: [[1_000_000, true], [1_005_000, false]] })
    const o = parts(buildChartOption(flag, RAW_CTX))

    expect(o.series[0].data).toEqual([
      [1_000_000, 1],
      [1_005_000, 0],
    ])
    expect(o.yAxis).toMatchObject({ min: 0, max: 1, interval: 1 })
    expect(o.yAxis.axisLabel.formatter(0)).toBe('否')
    expect(o.yAxis.axisLabel.formatter(1)).toBe('是')
    expect(stateLabel(flag, true)).toBe('是')
  })

  it('shows unknown enum values as numbers and escapes labels in the tooltip', () => {
    expect(stateLabel(ST, 7)).toBe('7')
    const evil = series({ ...ST, enumItems: [{ value: 1, label: '<b>运行</b>' }] })
    expect(parts(buildChartOption(evil, AGG_CTX)).tooltip.formatter([{ dataIndex: 0 }])).toContain('&lt;b&gt;运行&lt;/b&gt;')
  })
})

describe('tooltip escaping', () => {
  it('escapes user-defined name and unit text in the formatter output', () => {
    const xss = '<img src=x onerror=alert(1)>'
    const raw = series({ name: xss, unit: xss })
    const agg = series({ name: xss, unit: xss, mode: 'AGG', points: [[900_000, 220, 219.5, 221]] })

    for (const out of [
      parts(buildChartOption(raw, RAW_CTX)).tooltip.formatter([{ dataIndex: 0 }]),
      parts(buildChartOption(agg, AGG_CTX)).tooltip.formatter([{ dataIndex: 0 }]),
    ]) {
      expect(out).not.toContain('<img')
      expect(out).toContain('&lt;img src=x onerror=alert(1)&gt;')
    }
  })
})

describe('empty data', () => {
  it('still returns axes with empty series', () => {
    const o = parts(buildChartOption(series({ points: [] }), RAW_CTX))

    expect(o.series[0].data).toEqual([])
    expect(o.xAxis).toMatchObject({ min: 1_000_000, max: 4_600_000 })
  })
})

describe('zoomRangeFromEvent', () => {
  it('reads startValue/endValue from a toolbox zoom batch or a plain event', () => {
    expect(zoomRangeFromEvent({ batch: [{ startValue: 1000, endValue: 2000 }] })).toEqual({ from: 1000, to: 2000 })
    expect(zoomRangeFromEvent({ startValue: 1000, endValue: 2000 })).toEqual({ from: 1000, to: 2000 })
  })

  it('ignores percentage-only, empty or reversed events', () => {
    expect(zoomRangeFromEvent({ batch: [{ start: 0, end: 100 }] })).toBeNull()
    expect(zoomRangeFromEvent(null)).toBeNull()
    expect(zoomRangeFromEvent({ startValue: 2000, endValue: 1000 })).toBeNull()
  })
})

describe('chart background', () => {
  it('is transparent so the ECharts dark theme blends with the page', () => {
    const o = buildChartOption(series(), RAW_CTX) as { backgroundColor?: string }

    expect(o.backgroundColor).toBe('transparent')
  })
})
