<script setup lang="ts">
/**
 * 设备历史曲线面板（V0.2-B）：工具栏（快捷范围 / 自定义起止 / 测点多选 / 重置缩放 / 刷新）+ 每个测点一张联动小图。
 * 快捷范围且未缩放时每 30 秒自动刷新（页面隐藏时暂停）；框选缩放后按选中范围重新查询并暂停自动刷新，
 * 「重置缩放」回到原范围；用请求序号丢弃过期响应（缩放、改范围、改测点、刷新、轮询都会使旧请求作废）。
 * 图表随主题切换：深色时用 ECharts 内置 dark 主题，主题变化时销毁并按新主题重建实例。
 * 初始选中的测点可由 initialProps 指定（来自 ?props=），不存在的标识被忽略，一个都不剩时用默认选中（前 4 个数值测点）。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { historyApi } from '@/api/history'
import { thingModelApi } from '@/api/thingModel'
import { deviceApi } from '@/api/devices'
import { problemMessage } from '@/api/errors'
import type { HistoryView } from '@/api/types'
import { echarts, type ECharts } from '@/utils/echarts'
import { createVisiblePoller } from '@/composables/usePolling'
import { createRequestSeq } from '@/utils/requestSeq'
import { chartTheme, useTheme } from '@/composables/useTheme'
import {
  bucketLabel,
  DEFAULT_QUICK_RANGE,
  QUICK_RANGES,
  quickRange,
  rangeError,
  zoomTo,
  type QuickRangeKey,
  type TimeRange,
} from '@/utils/historyRange'
import { buildChartOption, seriesLabel, zoomRangeFromEvent } from '@/utils/historyChart'
import { initialSelection, MAX_SELECTED, propertyCandidates, type PropertyCandidate } from '@/utils/historyProperties'

const props = defineProps<{ deviceKey: string; productKey: string; initialProps?: string[] }>()

const REFRESH_MS = 30_000
const group = `history-${props.deviceKey}`

const candidates = ref<PropertyCandidate[]>([])
const selected = ref<string[]>([])
const rangeKey = ref<QuickRangeKey | 'custom'>(DEFAULT_QUICK_RANGE)
// 自定义起止的选择器绑定值（el-date-picker datetimerange）：v-model 会先于校验写入，可能暂存不合法的范围；清空后组件会写入 null
const customValue = ref<[Date, Date] | null | ''>('')
// 最近一次通过校验的自定义范围，查询只读它；校验失败时选择器回退到该值，因此不合法的范围不会进入后续查询
const appliedCustom = ref<[Date, Date] | null>(null)
const zoom = ref<TimeRange | null>(null)
const data = ref<HistoryView | null>(null)
const loading = ref(false)

const chartEls = new Map<string, HTMLElement>()
const charts = new Map<string, ECharts>()
const seq = createRequestSeq()
const poller = createVisiblePoller(() => load(currentRange()), REFRESH_MS)
const { isDark } = useTheme()

const modeLabel = computed(() => {
  if (!data.value) {
    return ''
  }
  return data.value.bucketMs ? `已按 ${bucketLabel(data.value.bucketMs)}聚合（平均/最小/最大）` : '原始数据'
})

const refreshHint = computed(() => {
  if (zoom.value) {
    return '已缩放，自动刷新已暂停'
  }
  return rangeKey.value === 'custom' ? '自定义范围不自动刷新' : '每 30 秒自动刷新'
})

/** 当前要查询的范围：缩放范围优先，其次自定义范围，否则为以当前时间为终点的快捷范围 */
function currentRange(): TimeRange | null {
  if (zoom.value) {
    return zoom.value
  }
  if (rangeKey.value === 'custom') {
    const value = appliedCustom.value
    return value ? { from: value[0].getTime(), to: value[1].getTime() } : null
  }
  return quickRange(rangeKey.value, Date.now())
}

/**
 * 按当前状态安排查询：未选测点时清空；快捷范围且未缩放时启动自动刷新（立即查询一次）；
 * 否则停止自动刷新并只查询一次。开头先使在途请求作废（停止轮询并不会取消已发出的这一轮）。
 */
function schedule() {
  poller.stop()
  seq.next()
  if (selected.value.length === 0) {
    loading.value = false
    data.value = null
    disposeCharts()
    return
  }
  if (rangeKey.value !== 'custom' && !zoom.value) {
    poller.start()
    return
  }
  void load(currentRange())
}

/** 查询并渲染；只有最后发出的请求能写入结果，失败时提示错误 */
async function load(range: TimeRange | null) {
  if (!range || selected.value.length === 0) {
    return
  }
  const id = seq.next()
  loading.value = true
  try {
    const res = await historyApi.query(props.deviceKey, selected.value, range.from, range.to)
    if (!seq.isLatest(id)) {
      return
    }
    data.value = res
    await nextTick()
    if (seq.isLatest(id)) {
      renderCharts()
    }
  } catch (e) {
    if (seq.isLatest(id)) {
      ElMessage.error(problemMessage(e))
    }
  } finally {
    if (seq.isLatest(id)) {
      loading.value = false
    }
  }
}

/** 记录每个测点的图表容器；元素卸载时 Vue 以 null 调用 */
function setChartEl(identifier: string, el: HTMLElement | null) {
  if (el) {
    chartEls.set(identifier, el)
  } else {
    chartEls.delete(identifier)
  }
}

/** 用最新数据更新图表：复用同一测点的实例，容器已不存在的实例销毁；所有实例加入同一联动分组 */
function renderCharts() {
  const view = data.value
  if (!view) {
    return
  }
  for (const [id, chart] of charts) {
    if (chartEls.get(id) !== chart.getDom()) {
      chart.dispose()
      charts.delete(id)
    }
  }
  const ctx = { from: view.from, to: view.to, bucketMs: view.bucketMs }
  for (const s of view.series) {
    const el = chartEls.get(s.identifier)
    if (!el) {
      continue
    }
    let chart = charts.get(s.identifier)
    if (!chart) {
      chart = echarts.init(el, chartTheme(isDark.value))
      chart.group = group
      chart.on('datazoom', (params: unknown) => onZoom(params))
      charts.set(s.identifier, chart)
    }
    chart.setOption(buildChartOption(s, ctx), true)
    // 让框选缩放处于激活状态，用户直接拖动即可选择范围
    // escapeConnect：联动图表不重放此动作，否则每次渲染要做 N×(N-1) 次完整更新
    chart.dispatchAction({ type: 'takeGlobalCursor', key: 'dataZoomSelect', dataZoomSelectActive: true, escapeConnect: true })
  }
  echarts.connect(group)
}

/** 销毁全部图表实例（清空测点、卸载时） */
function disposeCharts() {
  charts.forEach((chart) => chart.dispose())
  charts.clear()
}

/** 窗口尺寸变化时让所有图表重新适配容器 */
function resizeCharts() {
  charts.forEach((chart) => chart.resize())
}

/** 框选缩放：按选中范围（不足 1 分钟补足）重新查询并暂停自动刷新；联动图表重复触发的同一范围忽略 */
function onZoom(params: unknown) {
  const selection = zoomRangeFromEvent(params)
  if (!selection) {
    return
  }
  const next = zoomTo(selection.from, selection.to)
  if (zoom.value && zoom.value.from === next.from && zoom.value.to === next.to) {
    return
  }
  zoom.value = next
  schedule()
}

/** 重置缩放：回到缩放前的快捷或自定义范围，快捷范围恢复自动刷新 */
function resetZoom() {
  zoom.value = null
  schedule()
}

/** 选择快捷范围：清除自定义范围与缩放 */
function selectQuick(key: QuickRangeKey) {
  rangeKey.value = key
  customValue.value = ''
  appliedCustom.value = null
  zoom.value = null
  schedule()
}

/** 快捷范围单选组的变化回调 */
function onQuickChange(value: unknown) {
  selectQuick(value as QuickRangeKey)
}

/**
 * 自定义起止：清空时回到默认快捷范围；跨度不合法时提示、选择器回退到最近一次合法的范围（快捷范围模式下为空）并保持当前视图。
 * 参数为组件抛出的值（[开始, 结束] 或 null）
 */
function onCustomChange(value: unknown) {
  if (!Array.isArray(value) || value.length !== 2) {
    selectQuick(DEFAULT_QUICK_RANGE)
    return
  }
  const [start, end] = value as [Date, Date]
  const error = rangeError({ from: start.getTime(), to: end.getTime() })
  if (error) {
    ElMessage.warning(error)
    customValue.value = appliedCustom.value ?? ''
    return
  }
  appliedCustom.value = [start, end]
  rangeKey.value = 'custom'
  zoom.value = null
  schedule()
}

/** 主题切换：ECharts 不能修改已有实例的主题，销毁全部实例后按新主题重新渲染当前数据 */
watch(isDark, () => {
  disposeCharts()
  renderCharts()
})

watch(selected, () => schedule())

onMounted(async () => {
  window.addEventListener('resize', resizeCharts)
  try {
    const [model, latest] = await Promise.all([thingModelApi.list(props.productKey), deviceApi.latest(props.deviceKey)])
    candidates.value = propertyCandidates(model, latest.values)
    selected.value = initialSelection(candidates.value, props.initialProps ?? [])
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
})

onBeforeUnmount(() => {
  poller.dispose()
  seq.next()
  window.removeEventListener('resize', resizeCharts)
  disposeCharts()
  echarts.disconnect(group)
})
</script>

<template>
  <div class="history">
    <div class="toolbar">
      <el-radio-group :model-value="rangeKey" size="small" @change="onQuickChange">
        <el-radio-button v-for="r in QUICK_RANGES" :key="r.key" :value="r.key">{{ r.label }}</el-radio-button>
      </el-radio-group>
      <el-date-picker
        v-model="customValue"
        type="datetimerange"
        size="small"
        range-separator="至"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        @change="onCustomChange"
      />
      <el-select
        v-model="selected"
        multiple
        collapse-tags
        collapse-tags-tooltip
        :multiple-limit="MAX_SELECTED"
        size="small"
        placeholder="选择测点（最多 8 个）"
        class="properties"
      >
        <el-option v-for="c in candidates" :key="c.identifier" :label="c.label" :value="c.identifier" />
      </el-select>
      <el-button size="small" :disabled="!zoom" @click="resetZoom">重置缩放</el-button>
      <el-button size="small" @click="schedule">刷新</el-button>
    </div>
    <div class="status">
      <span class="mode">{{ modeLabel }}</span>
      <span class="hint">{{ refreshHint }}</span>
    </div>
    <div v-loading="loading" class="charts">
      <el-empty v-if="selected.length === 0" description="请选择测点" />
      <template v-else-if="data">
        <div v-for="s in data.series" :key="s.identifier" class="chart-row">
          <div class="chart-title">{{ seriesLabel(s) }}</div>
          <div v-if="!s.points.some(p => p[1] != null)" class="empty">该时段没有数据</div>
          <div v-else :ref="(el) => setChartEl(s.identifier, el as HTMLElement | null)" class="chart" />
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.history {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.properties {
  width: 320px;
}
.status {
  display: flex;
  gap: 16px;
  font-size: 12px;
}
.mode {
  color: var(--el-text-color-regular);
}
.hint {
  color: var(--el-text-color-secondary);
}
.charts {
  min-height: 120px;
}
.chart-row {
  margin-bottom: 8px;
}
.chart-title {
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.chart {
  height: 180px;
}
.empty {
  height: 60px;
  line-height: 60px;
  text-align: center;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
