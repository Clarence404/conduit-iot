/** 迷你趋势线的 ECharts option（纯函数）：无坐标轴、无提示框的单条折线加浅色面积，背景透明以适配浅色与深色主题。 */
import type { EChartsCoreOption } from 'echarts/core'
import type { SparkPoint } from './metricCards'

/**
 * 生成迷你趋势线 option。
 * @param points 按时间排序的 [时间毫秒, 数值]
 */
export function sparklineOption(points: readonly SparkPoint[]): EChartsCoreOption {
  return {
    animation: false,
    backgroundColor: 'transparent',
    grid: { left: 0, right: 0, top: 2, bottom: 2 },
    xAxis: { type: 'time', show: false },
    yAxis: { type: 'value', show: false, scale: true },
    series: [
      {
        type: 'line',
        connectNulls: false,
        data: points.map((p) => [p[0], p[1]]),
        symbol: 'none',
        silent: true,
        lineStyle: { width: 1.5 },
        areaStyle: { opacity: 0.12 },
      },
    ],
  }
}
