<script setup lang="ts">
/**
 * 设备详情页：上方设备信息卡（名称、在线与启用状态、所属产品、设备 Key、最后上报、创建日期，「连接参数」弹窗与「编辑」，「编辑」只对可写身份显示）；
 * 下方「最新值 | 历史」两个标签页，标签与 ?tab= 同步（router.replace，不产生历史记录）。
 * 最新值每 5 秒轮询，交给 LatestValuesPanel 以卡片或表格展示；点击卡片切到历史标签并只选中该测点（?tab=history&props=Ua）。
 * HistoryPanel 只在历史标签激活时挂载，从 ?props= 读取初始选中。设备名称写入面包屑与浏览器标题；
 * 物模型用于判断卡片是数值还是状态量，取不到时按值本身判断。
 * 路由从一台设备切到另一台时组件会被复用：deviceKey 变化时先清掉面包屑里的旧名称与页面状态再加载新设备，旧设备的迟到响应被丢弃。
 */

import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { deviceApi } from '@/api/devices'
import { assetApi, siteApi } from '@/api/organization'
import { productApi } from '@/api/products'
import { thingModelApi } from '@/api/thingModel'
import { problemMessage } from '@/api/errors'
import type { AssetView, DeviceAssignmentView, DeviceView, PointView, PropertyView, SiteView } from '@/api/types'
import { deviceTabFromQuery, deviceTabQuery, historyQuery, propsFromQuery, type DeviceTab } from '@/utils/deviceTabs'
import { createRequestSeq } from '@/utils/requestSeq'
import { usePolling } from '@/composables/usePolling'
import { useBreadcrumb } from '@/composables/useBreadcrumb'
import { usePermission } from '@/composables/usePermission'
import DeviceInfoCard from '@/components/DeviceInfoCard.vue'
import ConnectionDialog from '@/components/ConnectionDialog.vue'
import LatestValuesPanel from '@/components/LatestValuesPanel.vue'
import HistoryPanel from '@/components/HistoryPanel.vue'

// 不能叫 key：key 是 Vue 保留属性，不会作为 prop 传入
const props = defineProps<{ deviceKey: string }>()

const route = useRoute()
const router = useRouter()
const { setName } = useBreadcrumb()
const { canWrite } = usePermission()

// 当前标签与 ?tab= 同步；用 replace 切换，不产生历史记录
const activeTab = computed<DeviceTab>({
  get: () => deviceTabFromQuery(route.query.tab),
  set: (tab) => {
    void router.replace({ query: deviceTabQuery(route.query, tab) })
  },
})
/** 历史标签初始选中的测点（?props=） */
const historyProps = computed(() => propsFromQuery(route.query.props))

const device = ref<DeviceView | null>(null)
const failed = ref(false)
const productName = ref<string | null>(null)
const model = ref<PropertyView[]>([])
// 物模型已取回或确定取不到：之前最新值面板不查趋势，避免按错误的测点分类查两次
const modelReady = ref(false)
const values = ref<PointView[]>([])
const lastRefreshed = ref<number | null>(null)
const connectionVisible = ref(false)
const editVisible = ref(false)
const saving = ref(false)
const assignment = ref<DeviceAssignmentView | null>(null)
const assignmentVisible = ref(false)
const assignmentSaving = ref(false)
const sites = ref<SiteView[]>([])
const assets = ref<AssetView[]>([])
const assignmentForm = reactive({ siteCode: '', assetCode: '' })
const editForm = reactive({ name: '', enabled: true })
// 加载设备的请求序号：切换设备后，上一台设备的迟到响应不得写入页面
const loadSeq = createRequestSeq()

/**
 * 加载设备，再并行加载所属产品名称与物模型。设备加载失败时提示并显示结果页；
 * 产品名称与物模型只是辅助展示，失败时静默（名称退回产品 Key，卡片按值本身判断类型）。
 */
async function loadDevice() {
  const id = loadSeq.next()
  let loaded: DeviceView
  try {
    loaded = await deviceApi.get(props.deviceKey)
  } catch (e) {
    if (loadSeq.isLatest(id)) {
      failed.value = true
      ElMessage.error(problemMessage(e))
    }
    return
  }
  if (!loadSeq.isLatest(id)) {
    return
  }
  device.value = loaded
  setName(loaded.name)
  const productKey = loaded.productKey
  const [product, properties] = await Promise.allSettled([productApi.get(productKey), thingModelApi.list(productKey)])
  if (!loadSeq.isLatest(id)) {
    return
  }
  if (product.status === 'fulfilled') {
    productName.value = product.value.name
  }
  if (properties.status === 'fulfilled') {
    model.value = properties.value
  }
  modelReady.value = true
  void loadAssignment()
}

async function loadAssignment() {
  try {
    const [current, siteRows, assetRows] = await Promise.all([
      deviceApi.assignment(props.deviceKey), siteApi.list(), assetApi.list(),
    ])
    assignment.value = current; sites.value = siteRows; assets.value = assetRows
  } catch (e) { ElMessage.error(problemMessage(e)) }
}

function openAssignment() {
  assignmentForm.siteCode = assignment.value?.siteCode ?? sites.value[0]?.code ?? ''
  assignmentForm.assetCode = assignment.value?.assetCode ?? ''
  assignmentVisible.value = true
}

async function saveAssignment() {
  if (!assignmentForm.siteCode) return ElMessage.warning('请选择站点')
  assignmentSaving.value = true
  try {
    assignment.value = await deviceApi.assign(props.deviceKey, assignmentForm.siteCode, assignmentForm.assetCode || null)
    assignmentVisible.value = false; ElMessage.success('组织归属已更新')
  } catch (e) { ElMessage.error(problemMessage(e)) } finally { assignmentSaving.value = false }
}

/**
 * 加载最新值并记录刷新时间；用最新的采样时间推进信息卡的「最后上报」。
 * 不处理错误：由轮询吞掉，下一轮继续重试。
 */
async function loadLatest() {
  const key = props.deviceKey
  const res = await deviceApi.latest(key)
  if (key !== props.deviceKey) {
    return
  }
  values.value = res.values
  lastRefreshed.value = Date.now()
  const newest = res.values.reduce((max, v) => Math.max(max, v.ts), 0)
  if (device.value && newest > (device.value.lastReportAt ?? 0)) {
    device.value = { ...device.value, lastReportAt: newest }
  }
}

/** 点击指标卡片：切到历史标签并只选中该测点 */
function openHistory(identifier: string) {
  void router.replace({ query: historyQuery(route.query, identifier) })
}

/** 打开编辑对话框，回填当前名称与启用状态 */
function openEdit() {
  if (!device.value) {
    return
  }
  editForm.name = device.value.name
  editForm.enabled = device.value.status === 'ENABLED'
  editVisible.value = true
}

/**
 * 保存名称与启用状态：名称必填；保存中忽略重复点击；成功后更新信息卡、面包屑并关闭对话框。
 * 保存期间切换到另一台设备时，旧设备的迟到结果（成功或失败）被丢弃，不写页面、不提示，saving 只由当前设备的状态决定。
 */
async function saveEdit() {
  if (saving.value || !device.value) {
    return
  }
  const name = editForm.name.trim()
  if (!name) {
    ElMessage.warning('请输入设备名称')
    return
  }
  const key = device.value.deviceKey
  saving.value = true
  try {
    const updated = await deviceApi.update(key, name, editForm.enabled ? 'ENABLED' : 'DISABLED')
    // 保存期间路由已切到另一台设备：结果属于旧设备，不能写到新设备的页面与面包屑上
    if (key !== props.deviceKey) {
      return
    }
    device.value = updated
    setName(updated.name)
    editVisible.value = false
    ElMessage.success('已保存')
  } catch (e) {
    if (key === props.deviceKey) {
      ElMessage.error(problemMessage(e))
    }
  } finally {
    // 切换设备时 resetForDevice 已复位 saving，旧设备的迟到结果不能再改它
    if (key === props.deviceKey) {
      saving.value = false
    }
  }
}

/** 切换到另一台设备：面包屑名称与全部页面状态回到初始，再加载新设备及其最新值（骨架屏期间两个标签页面板都已卸载） */
function resetForDevice() {
  setName(null)
  device.value = null
  failed.value = false
  productName.value = null
  model.value = []
  modelReady.value = false
  values.value = []
  lastRefreshed.value = null
  connectionVisible.value = false
  editVisible.value = false
  saving.value = false
  assignment.value = null
  assignmentVisible.value = false
  void loadDevice()
  // 不等下一轮 5 秒轮询，立即取新设备的最新值（失败由下一轮轮询重试）
  void loadLatest().catch(() => {})
}

watch(() => props.deviceKey, resetForDevice)
onMounted(loadDevice)
usePolling(loadLatest, 5000)
</script>

<template>
  <div v-if="device" class="detail">
    <DeviceInfoCard
      :device="device"
      :product-name="productName"
      :can-edit="canWrite"
      @connection="connectionVisible = true"
      @edit="openEdit"
    />

    <div class="cd-panel assignment-panel">
      <div><strong>组织归属</strong><div class="cd-muted">站点：{{ assignment?.siteCode ?? '加载中' }} · 资产：{{ assignment?.assetCode ?? '未分配' }}</div></div>
      <el-button v-if="canWrite" @click="openAssignment">调整归属</el-button>
    </div>

    <div class="cd-panel">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="最新值" name="latest">
          <LatestValuesPanel
            v-if="activeTab === 'latest'"
            :device-key="device.deviceKey"
            :values="values"
            :model="model"
            :model-ready="modelReady"
            :last-refreshed="lastRefreshed"
            @open-history="openHistory"
          />
        </el-tab-pane>
        <el-tab-pane label="历史" name="history">
          <HistoryPanel
            v-if="activeTab === 'history'"
            :device-key="device.deviceKey"
            :product-key="device.productKey"
            :initial-props="historyProps"
          />
        </el-tab-pane>
      </el-tabs>
    </div>

    <ConnectionDialog v-model="connectionVisible" :product-key="device.productKey" :device-key="device.deviceKey" />

    <el-dialog v-model="editVisible" title="编辑设备" width="min(440px, 92vw)" :close-on-click-modal="false">
      <el-form label-width="80px" class="edit-form" @submit.prevent="saveEdit">
        <el-form-item label="名称" required>
          <el-input v-model="editForm.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="assignmentVisible" title="调整组织归属" width="min(440px, 92vw)">
      <el-form label-width="70px">
        <el-form-item label="站点" required><el-select v-model="assignmentForm.siteCode" @change="assignmentForm.assetCode = ''"><el-option v-for="x in sites" :key="x.code" :label="x.name" :value="x.code"/></el-select></el-form-item>
        <el-form-item label="资产"><el-select v-model="assignmentForm.assetCode" clearable placeholder="不分配资产"><el-option v-for="x in assets.filter(a => a.siteCode === assignmentForm.siteCode)" :key="x.code" :label="x.name" :value="x.code"/></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="assignmentVisible = false">取消</el-button><el-button type="primary" :loading="assignmentSaving" @click="saveAssignment">保存</el-button></template>
    </el-dialog>
  </div>
  <el-result v-else-if="failed" icon="warning" title="无法加载设备" sub-title="设备可能已被删除，或不属于当前租户。">
    <template #extra>
      <el-button type="primary" @click="router.push({ name: 'devices' })">返回设备列表</el-button>
    </template>
  </el-result>
  <el-skeleton v-else :rows="6" animated />
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: var(--cd-space-4);
}
.assignment-panel { display:flex; align-items:center; justify-content:space-between; gap:var(--cd-space-3) }
.assignment-panel .cd-muted { margin-top:var(--cd-space-1) }
.el-select { width:100% }
</style>
