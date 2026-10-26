/** metricCards 的单元测试：卡片顺序、数值与状态文本、趋势线测点选择（前 8 个数值测点）、历史结果转趋势点 */
import { describe, expect, it } from 'vitest'
import type { DataType, HistoryView, PointView, PropertyView } from '@/api/types'
import { MAX_SPARKLINES, sparklineIdentifiers, sparklineSeries, toMetricCards } from './metricCards'

function point(identifier: string, value: number | string | boolean, patch: Partial<PointView> = {}): PointView {
  return { identifier, name: null, value, ts: 1000, unit: null, decimals: null, enumLabel: null, ...patch }
}

function prop(identifier: string, dataType: DataType): PropertyView {
  return {
    identifier,
    name: identifier,
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

describe('toMetricCards', () => {
  it('orders cards like the thing model, then unknown identifiers alphabetically', () => {
    const cards = toMetricCards(
      [point('zz', 1), point('Ia', 5), point('aa', 2), point('Ua', 220)],
      [prop('Ua', 'DOUBLE'), prop('Ia', 'DOUBLE')],
    )

    expect(cards.map((c) => c.identifier)).toEqual(['Ua', 'Ia', 'aa', 'zz'])
  })

  it('formats numbers with decimals and unit, and states as text without a unit', () => {
    const cards = toMetricCards(
      [
        point('Ua', 220.123, { name: 'A 相电压', unit: 'V', decimals: 1 }),
        point('pf', 0.98765),
        point('st', 1, { name: '运行状态', enumLabel: '运行', unit: 'x' }),
        point('code', 5),
        point('on', true),
        point('note', 'abc'),
      ],
      [prop('Ua', 'DOUBLE'), prop('pf', 'DOUBLE'), prop('st', 'ENUM'), prop('code', 'ENUM'), prop('on', 'BOOL'), prop('note', 'TEXT')],
    )

    expect(cards).toEqual([
      { identifier: 'Ua', title: 'A 相电压', valueText: '220.1', unit: 'V', state: false, ts: 1000 },
      { identifier: 'pf', title: 'pf', valueText: '0.988', unit: null, state: false, ts: 1000 },
      { identifier: 'st', title: '运行状态', valueText: '运行', unit: null, state: true, ts: 1000 },
      { identifier: 'code', title: 'code', valueText: '5', unit: null, state: true, ts: 1000 },
      { identifier: 'on', title: 'on', valueText: '是', unit: null, state: true, ts: 1000 },
      { identifier: 'note', title: 'note', valueText: 'abc', unit: null, state: true, ts: 1000 },
    ])
  })

  it('decides by the value when the thing model is unavailable', () => {
    const cards = toMetricCards([point('Ua', 220), point('on', false), point('st', 2, { enumLabel: '检修' })], [])

    expect(cards.map((c) => [c.identifier, c.state, c.valueText])).toEqual([
      ['on', true, '否'],
      ['st', true, '检修'],
      ['Ua', false, '220'],
    ])
  })
})

describe('sparklineIdentifiers', () => {
  it('takes the first 8 numeric cards', () => {
    const values = Array.from({ length: 10 }, (_, i) => point(`P${String(i + 1).padStart(2, '0')}`, i))
    const cards = toMetricCards([...values, point('st', 1, { enumLabel: '运行' })], [])

    expect(MAX_SPARKLINES).toBe(8)
    expect(sparklineIdentifiers(cards)).toEqual(['P01', 'P02', 'P03', 'P04', 'P05', 'P06', 'P07', 'P08'])
  })
})

describe('sparklineSeries', () => {
  it('retains missing buckets and real zero for sparklines', () => {
    const view = { series: [{ identifier: 'x', points: [[0, 4], [5, null], [10, 0]] }] } as unknown as HistoryView
    expect(sparklineSeries(view)).toEqual({ x: [[0, 4], [5, null], [10, 0]] })
  })
  it('uses the average of aggregated points and the value of raw points, skipping non-numbers', () => {
    const view: HistoryView = {
      deviceKey: 'dk1',
      from: 0,
      to: 3_600_000,
      bucketMs: null,
      series: [
        { identifier: 'Ua', name: null, dataType: 'DOUBLE', unit: null, decimals: null, enumItems: null, mode: 'AGG', points: [[0, 220, 219, 221]] },
        { identifier: 'Ia', name: null, dataType: 'DOUBLE', unit: null, decimals: null, enumItems: null, mode: 'RAW', points: [[0, 5], [10, 6]] },
        { identifier: 'on', name: null, dataType: 'BOOL', unit: null, decimals: null, enumItems: null, mode: 'LAST', points: [[0, true]] },
      ],
    }

    expect(sparklineSeries(view)).toEqual({ Ua: [[0, 220]], Ia: [[0, 5], [10, 6]], on: [] })
  })
})
