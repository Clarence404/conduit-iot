/**
 * 历史曲线：把接口返回的一条曲线转成 ECharts option（纯函数，便于单测）。
 * 数值测点：AGG 时为平均值折线 + 最小～最大浅色波动带（两条堆叠的透明线，第二条填充），RAW 时为单条折线；
 * 状态测点（布尔、枚举）：阶梯线（step: 'end'），纵轴刻度为「否/是」或枚举名称。
 */
import type { EChartsCoreOption } from 'echarts/core'
import type { HistoryPoint, SeriesView } from '@/api/types'
import { formatDateTime } from './format'
import type { TimeRange } from './historyRange'

/** 生成 option 所需的上下文：查询范围与聚合桶宽（原始模式为 null） */
export interface ChartContext {
  from: number
  to: number
  bucketMs: number | null
}

/** 图表标题：「名称（标识）」，物模型未定义时只显示标识 */
export function seriesLabel(s: SeriesView): string {
  return s.name ? `${s.name}（${s.identifier}）` : s.identifier
}

/** 是否按状态测点绘制：布尔、枚举，或服务端按 LAST 聚合的测点 */
export function isStateSeries(s: SeriesView): boolean {
  return s.mode === 'LAST' || s.dataType === 'BOOL' || s.dataType === 'ENUM'
}

/** 横轴起点：聚合时向下对齐到桶边界（服务端按 1970 原点分桶，首桶起点可能早于 from），原始模式即 from */
export function axisMin(ctx: ChartContext): number {
  return ctx.bucketMs ? Math.floor(ctx.from / ctx.bucketMs) * ctx.bucketMs : ctx.from
}

/** 数值文本：有 decimals 时按其格式化，有单位时追加单位 */
export function numberLabel(s: SeriesView, value: number): string {
  const text = s.decimals !== null && s.decimals !== undefined ? value.toFixed(s.decimals) : String(value)
  return s.unit ? `${text} ${s.unit}` : text
}

/** 状态值文本：布尔为「是/否」（数值 1/0 同样处理），枚举为对应名称，未定义的值原样显示 */
export function stateLabel(s: SeriesView, value: unknown): string {
  if (typeof value === 'boolean') {
    return value ? '是' : '否'
  }
  if (s.dataType === 'BOOL') {
    return value === 1 ? '是' : value === 0 ? '否' : String(value)
  }
  const item = s.enumItems?.find((e) => e.value === value)
  return item ? item.label : String(value)
}

/** 一个点的提示文本（未转义）：状态取名称；AGG 显示平均/最小/最大；RAW 显示数值 */
export function pointText(s: SeriesView, point: HistoryPoint): string {
  if (point[1] == null) return '无数据'
  if (isStateSeries(s)) {
    return stateLabel(s, point[1])
  }
  if (s.mode === 'AGG') {
    return `平均 ${numberLabel(s, point[1] as number)} · 最小 ${numberLabel(s, point[2] as number)} · 最大 ${numberLabel(s, point[3] as number)}`
  }
  return numberLabel(s, point[1] as number)
}

/** HTML 转义，用于提示框内容 */
function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

/** 提示框内容：时间 + 该点文本。文本经 HTML 转义（名称、单位、枚举名来自用户编辑的物模型）；找不到点时为空 */
function tooltipText(s: SeriesView, params: unknown): string {
  const first = (Array.isArray(params) ? params[0] : params) as { dataIndex?: unknown } | undefined
  const point = typeof first?.dataIndex === 'number' ? s.points[first.dataIndex] : undefined
  if (!point) {
    return ''
  }
  return `${formatDateTime(point[0] as number)}<br/>${escapeHtml(pointText(s, point))}`
}

/**
 * 数值测点的纵轴与序列：AGG 为波动带 + 平均线，RAW 为单条折线。
 * 波动带两条序列用 stackStrategy 'all' 堆叠：默认的 'samesign' 会忽略负的基线（min 为负时带顶会算错）。
 */
function numericParts(s: SeriesView) {
  const yAxis = {
    type: 'value',
    scale: true,
    name: s.unit ?? '',
    axisLabel: {
      formatter: (v: number) => (s.decimals !== null && s.decimals !== undefined ? v.toFixed(s.decimals) : String(v)),
    },
  }
  if (s.mode === 'AGG') {
    return {
      yAxis,
      series: [
        { name: '最小', type: 'line', connectNulls: false, stack: 'band', stackStrategy: 'all', symbol: 'none', silent: true, lineStyle: { opacity: 0 }, data: s.points.map((p) => [p[0], p[2]]) },
        {
          name: '波动范围',
          connectNulls: false,
          type: 'line',
          stack: 'band',
          stackStrategy: 'all',
          symbol: 'none',
          silent: true,
          lineStyle: { opacity: 0 },
          areaStyle: { opacity: 0.2 },
          data: s.points.map((p) => [p[0], typeof p[3] === 'number' && typeof p[2] === 'number' ? p[3] - p[2] : null]),
        },
        { name: '平均', type: 'line', connectNulls: false, symbol: 'none', data: s.points.map((p) => [p[0], p[1]]) },
      ],
    }
  }
  return {
    yAxis,
    series: [{ name: '值', type: 'line', connectNulls: false, symbol: 'circle', showSymbol: s.points.length === 1, data: s.points.map((p) => [p[0], p[1]]) }],
  }
}

/** 状态测点的纵轴（整数刻度，标签为名称）与阶梯线序列；布尔值映射为 1/0 */
function stateParts(s: SeriesView) {
  const values = s.dataType === 'ENUM' && s.enumItems && s.enumItems.length > 0 ? s.enumItems.map((e) => e.value) : [0, 1]
  return {
    yAxis: {
      type: 'value',
      min: Math.min(...values),
      max: Math.max(...values),
      interval: 1,
      axisLabel: { formatter: (v: number) => stateLabel(s, v) },
    },
    series: [
      {
        name: '状态',
        connectNulls: false,
        type: 'line',
        step: 'end',
        symbol: 'none',
        data: s.points.map((p) => [p[0], typeof p[1] === 'boolean' ? (p[1] ? 1 : 0) : p[1]]),
      },
    ],
  }
}

/** 透明 1x1 GIF，用来占位被隐藏的工具栏图标 */
const TRANSPARENT_ICON = 'image://data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw=='

/**
 * 生成一条曲线的 ECharts option：时间横轴固定为查询范围，工具栏只提供框选缩放，提示框按 axis 触发（配合 echarts.connect 联动）。
 * points 为空时仍返回带坐标轴、空序列的 option（界面另行显示「该时段没有数据」）。
 */
export function buildChartOption(s: SeriesView, ctx: ChartContext): EChartsCoreOption {
  const common = {
    animation: false,
    // 透明背景：深色时 ECharts 内置 dark 主题自带深蓝背景，与页面底色不一致
    backgroundColor: 'transparent',
    grid: { left: 64, right: 48, top: 24, bottom: 28 },
    // 不提供「还原」：每次 setOption 都会清空缩放历史，该按钮不起作用（重置缩放由面板按钮负责）。ECharts 无法移除内置 back 图标，换成透明 1x1 图片并清空标题
    toolbox: {
      right: 8,
      top: 0,
      feature: { dataZoom: { yAxisIndex: 'none', icon: { back: TRANSPARENT_ICON }, title: { zoom: '框选缩放', back: '' } } },
    },
    tooltip: { trigger: 'axis', axisPointer: { type: 'cross' }, formatter: (params: unknown) => tooltipText(s, params) },
    xAxis: { type: 'time', min: axisMin(ctx), max: ctx.to },
  }
  return isStateSeries(s) ? { ...common, ...stateParts(s) } : { ...common, ...numericParts(s) }
}

/**
 * 从 ECharts 的 datazoom 事件中取框选的时间范围。工具栏框选时值在 batch[0].startValue/endValue；
 * 只有百分比（如还原）、为空或反向时返回 null。
 */
export function zoomRangeFromEvent(params: unknown): TimeRange | null {
  const batch = (params as { batch?: unknown } | null)?.batch
  const item = (Array.isArray(batch) ? batch[0] : params) as { startValue?: unknown; endValue?: unknown } | null | undefined
  const from = item?.startValue
  const to = item?.endValue
  return typeof from === 'number' && typeof to === 'number' && to > from ? { from, to } : null
}
