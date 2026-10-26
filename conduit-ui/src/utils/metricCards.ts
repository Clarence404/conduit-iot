/**
 * 设备最新值卡片：把最新值与物模型合成卡片数据（标题、数值文本、单位、是否状态量），
 * 并选出画迷你趋势线的测点（前 8 个数值测点）、把历史查询结果转成趋势点。纯函数，便于单测。
 */
import type { DataType, HistoryView, PointView, PropertyView } from '@/api/types'
import { formatValue } from './connection'

/** 趋势线上的一个点：[时间毫秒, 数值] */
export type SparkPoint = [number, number | null]

/** 最多为多少个数值测点画趋势线（一次历史查询的上限也是 8） */
export const MAX_SPARKLINES = 8
/** 趋势线的时间窗口：最近 1 小时 */
export const SPARK_WINDOW_MS = 3_600_000

/** 一张指标卡片 */
export interface MetricCardModel {
  identifier: string
  /** 物模型名称，未定义时为标识 */
  title: string
  /** 数值（按小数位）或状态文字（枚举名称、是/否、文本） */
  valueText: string
  /** 单位；状态量没有单位 */
  unit: string | null
  /** 是否为状态量：显示文字标签，不画趋势线 */
  state: boolean
  /** 采样时间（毫秒） */
  ts: number
}

/** 是否按状态量展示：物模型为 BOOL/ENUM/TEXT，或值不是数字，或服务端给出了枚举名称 */
function isState(p: PointView, type: DataType | undefined): boolean {
  if (type === 'BOOL' || type === 'ENUM' || type === 'TEXT') {
    return true
  }
  return typeof p.value !== 'number' || p.enumLabel !== null
}

/** 状态量文字：枚举名称优先；布尔为「是/否」；其他原样 */
function stateText(p: PointView): string {
  if (p.enumLabel) {
    return p.enumLabel
  }
  if (typeof p.value === 'boolean') {
    return p.value ? '是' : '否'
  }
  return formatValue(p.value)
}

/** 数值文字：有小数位定义时按其格式化，否则整数原样、小数最多 3 位 */
function numberText(p: PointView): string {
  return typeof p.value === 'number' && p.decimals !== null && p.decimals !== undefined
    ? p.value.toFixed(p.decimals)
    : formatValue(p.value)
}

/**
 * 生成卡片：顺序为物模型中的属性顺序，物模型未定义的测点按标识字母序排在后面。
 * @param values 设备最新值
 * @param model 产品物模型；取不到时传空数组，此时只按值本身判断数值/状态
 */
export function toMetricCards(values: PointView[], model: PropertyView[]): MetricCardModel[] {
  const order = new Map(model.map((p, i) => [p.identifier, i]))
  const types = new Map(model.map((p) => [p.identifier, p.dataType]))
  return [...values]
    .sort(
      (a, b) =>
        (order.get(a.identifier) ?? Infinity) - (order.get(b.identifier) ?? Infinity) ||
        a.identifier.localeCompare(b.identifier),
    )
    .map((p) => {
      const state = isState(p, types.get(p.identifier))
      return {
        identifier: p.identifier,
        title: p.name ?? p.identifier,
        valueText: state ? stateText(p) : numberText(p),
        unit: state ? null : p.unit,
        state,
        ts: p.ts,
      }
    })
}

/** 画趋势线的测点：按卡片顺序取前 MAX_SPARKLINES 个数值测点 */
export function sparklineIdentifiers(cards: MetricCardModel[]): string[] {
  return cards
    .filter((c) => !c.state)
    .slice(0, MAX_SPARKLINES)
    .map((c) => c.identifier)
}

/**
 * 把一次历史查询结果转成各测点的趋势点：聚合模式取平均值（点的第 2 项），原始模式取值；时间或值不是数字的点跳过。
 * @returns 键为测点标识；查询中有但没有数值点的测点为空数组
 */
export function sparklineSeries(view: HistoryView): Record<string, SparkPoint[]> {
  const result: Record<string, SparkPoint[]> = {}
  for (const s of view.series) {
    result[s.identifier] = s.points
      .filter((p) => typeof p[0] === 'number' && (typeof p[1] === 'number' || p[1] === null))
      .map((p) => [p[0] as number, p[1] as number | null])
  }
  return result
}
