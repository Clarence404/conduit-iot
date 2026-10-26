<script setup lang="ts">
/**
 * 产品详情页：页头显示产品名称、产品 Key（可复制）与设备数（在线/总数），产品名称同时写入面包屑与浏览器标题；
 * 下方用 ?tab= 区分三个标签（router.replace 切换，不产生历史记录）：
 * - 基本信息：编辑名称、描述、解码器；
 * - 物模型：属性的增删改、整体导入导出；属性一次取回，前端分页（useTable，默认每页 10 条，删除后页码自动修正）；
 * - 设备：该产品下的设备，服务端分页（useTable），每次切到该标签都重新加载以保持在线状态最新。
 * 写操作（保存基本信息、新增/编辑/删除/导入属性、去新建设备）只对可写身份显示（usePermission().canWrite），导出对所有人可用。
 */

import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { DocumentCopy } from '@element-plus/icons-vue'
import { productApi } from '@/api/products'
import { deviceApi } from '@/api/devices'
import { codecApi } from '@/api/codecs'
import { thingModelApi } from '@/api/thingModel'
import { problemMessage } from '@/api/errors'
import type { CodecView, DeviceView, ProductView, PropertyView } from '@/api/types'
import { dataTypeLabel, formatRange } from '@/utils/thingModel'
import { formatCount, formatDateTime } from '@/utils/format'
import { copyText } from '@/utils/clipboard'
import { PAGE_SIZES, pageSlice } from '@/utils/pagination'
import { tabFromQuery, tabQuery, type ProductTab } from '@/utils/productTabs'
import { downloadJson } from '@/utils/download'
import { useTable } from '@/composables/useTable'
import { useBreadcrumb } from '@/composables/useBreadcrumb'
import { usePermission } from '@/composables/usePermission'
import PageHeader from '@/components/PageHeader.vue'
import OnlineDot from '@/components/OnlineDot.vue'
import RelativeTime from '@/components/RelativeTime.vue'
import PropertyDialog from '@/components/PropertyDialog.vue'
import ThingModelImportDialog from '@/components/ThingModelImportDialog.vue'

const props = defineProps<{ productKey: string }>()

const route = useRoute()
const router = useRouter()
const { setName } = useBreadcrumb()
const { can } = usePermission()
const canWrite = computed(() => can('product.manage', true))

// 当前标签与 ?tab= 同步；用 replace 切换，不产生历史记录
const activeTab = computed<ProductTab>({
  get: () => tabFromQuery(route.query.tab),
  set: (tab) => {
    void router.replace({ query: tabQuery(route.query, tab) })
  },
})

const product = ref<ProductView | null>(null)
const codecs = ref<CodecView[]>([])
const properties = ref<PropertyView[]>([])
const fetchingProperties = ref(false)
// 属性加载失败：属性表空态显示「加载失败，点击重试」而不是引导新增
const propertiesFailed = ref(false)
const basic = reactive({ name: '', description: '', codec: '' })
const savingBasic = ref(false)
const dialogVisible = ref(false)
const editing = ref<PropertyView | null>(null)
const importVisible = ref(false)

// 属性：一次取回全部，前端分页
const {
  items: pagedProperties,
  total: propertyTotal,
  page: propertyPage,
  size: propertySize,
  load: loadPropertyPage,
  onSizeChange: onPropertySizeChange,
  onPageChange: onPropertyPageChange,
} = useTable<PropertyView>((page, size) =>
  Promise.resolve({ items: pageSlice(properties.value, page, size), total: properties.value.length, page, size }),
)

// 设备：服务端分页
const {
  items: devices,
  total: deviceTotal,
  page: devicePage,
  size: deviceSize,
  loading: loadingDevices,
  failed: devicesFailed,
  load: loadDevices,
  onSizeChange: onDeviceSizeChange,
  onPageChange: onDevicePageChange,
} = useTable<DeviceView>((page, size) => deviceApi.list({ productKey: props.productKey, page, size }))

/** 页头里的设备计数文案，如「设备 5（在线 4）」；在线数未知时显示「—」 */
const deviceSummary = computed(() =>
  product.value ? `设备 ${product.value.deviceCount}（在线 ${formatCount(product.value.onlineCount)}）` : '',
)

/** 加载产品并回填基本信息表单（会覆盖表单里未保存的修改，只在进入页面时调用）；产品名称写入面包屑。 */
async function loadProduct() {
  try {
    product.value = await productApi.get(props.productKey)
    basic.name = product.value.name
    basic.description = product.value.description ?? ''
    basic.codec = product.value.codec
    setName(product.value.name)
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

/** 属性变化后只刷新属性数与设备计数，不重置基本信息表单里尚未保存的修改 */
async function refreshCounts() {
  try {
    const fresh = await productApi.get(props.productKey)
    if (product.value) {
      product.value = {
        ...product.value,
        propertyCount: fresh.propertyCount,
        deviceCount: fresh.deviceCount,
        onlineCount: fresh.onlineCount,
      }
    }
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

/** 复制产品 Key 到剪贴板并提示结果。 */
async function copyProductKey() {
  if (await copyText(props.productKey)) {
    ElMessage.success('已复制')
  } else {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}

/** 加载解码器列表，用于基本信息的下拉选项。 */
async function loadCodecs() {
  try {
    codecs.value = await codecApi.list()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

/** 加载产品的全部属性（失败时置 propertiesFailed，表格空态改为「加载失败，点击重试」），再按当前页码切片（属性变少导致当前页不存在时 useTable 退到最后一页）。 */
async function loadProperties() {
  fetchingProperties.value = true
  propertiesFailed.value = false
  try {
    properties.value = await thingModelApi.list(props.productKey)
    await loadPropertyPage()
  } catch (e) {
    propertiesFailed.value = true
    ElMessage.error(problemMessage(e))
  } finally {
    fetchingProperties.value = false
  }
}

/** 保存基本信息（名称必填，解码器一并提交）。成功后用服务端返回值更新产品与面包屑；保存中忽略重复点击。 */
async function saveBasic() {
  if (savingBasic.value) return
  if (!basic.name.trim()) {
    ElMessage.warning('请输入产品名称')
    return
  }
  savingBasic.value = true
  try {
    product.value = await productApi.update(props.productKey, {
      name: basic.name.trim(),
      description: basic.description.trim() || null,
      codec: basic.codec,
    })
    setName(product.value.name)
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    savingBasic.value = false
  }
}

/** 打开属性对话框，新增模式。 */
function openCreate() {
  editing.value = null
  dialogVisible.value = true
}

/** 打开属性对话框，编辑模式。 */
function openEdit(row: PropertyView) {
  editing.value = row
  dialogVisible.value = true
}

/** 删除属性：先确认再请求（历史数据不会删除），成功后刷新属性列表与计数。 */
async function remove(row: PropertyView) {
  try {
    await ElMessageBox.confirm(`确定删除属性「${row.name}（${row.identifier}）」？历史数据不会删除。`, '删除属性', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await thingModelApi.remove(props.productKey, row.identifier)
    ElMessage.success('已删除')
    await onPropertiesChanged()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

/** 导出物模型并下载为 {productKey}.thing-model.json。 */
async function exportModel() {
  try {
    downloadJson(`${props.productKey}.thing-model.json`, await thingModelApi.exportModel(props.productKey))
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

/** 属性新增、编辑、删除、导入后统一刷新：并行重载属性列表和产品计数。 */
async function onPropertiesChanged() {
  await Promise.all([loadProperties(), refreshCounts()])
}

// 每次切到「设备」标签都重新拉取，在线状态保持最新
watch(
  activeTab,
  (tab) => {
    if (tab === 'devices') {
      void loadDevices()
    }
  },
  { immediate: true },
)

onMounted(() => {
  void loadProduct()
  void loadCodecs()
  void loadProperties()
})
</script>

<template>
  <div>
    <PageHeader :title="product?.name ?? productKey">
      <template #subtitle>
        产品 Key：<code>{{ productKey }}</code>
        <el-button link type="primary" :icon="DocumentCopy" @click="copyProductKey">复制</el-button>
        <span v-if="product" class="summary"> · {{ deviceSummary }}</span>
      </template>
    </PageHeader>

    <div class="cd-panel">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="基本信息" name="basic">
          <el-form label-width="96px" class="basic" :disabled="!canWrite" @submit.prevent="saveBasic">
            <el-form-item label="名称" required>
              <el-input v-model="basic.name" maxlength="128" />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="basic.description" type="textarea" maxlength="512" />
            </el-form-item>
            <el-form-item label="解码器">
              <el-select v-model="basic.codec">
                <el-option v-for="c in codecs" :key="c.name" :label="c.displayName" :value="c.name" />
              </el-select>
            </el-form-item>
            <el-form-item v-if="canWrite">
              <el-button type="primary" :loading="savingBasic" @click="saveBasic">保存</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="物模型" name="model">
          <div class="toolbar">
            <span class="cd-muted">共 {{ properties.length }} 个属性（上限 200）</span>
            <div>
              <el-button v-if="canWrite" @click="importVisible = true">导入</el-button>
              <el-button @click="exportModel">导出</el-button>
              <el-button v-if="canWrite" type="primary" @click="openCreate">新增属性</el-button>
            </div>
          </div>
          <el-table v-loading="fetchingProperties" :data="pagedProperties">
            <el-table-column prop="identifier" label="标识" width="160" />
            <el-table-column prop="name" label="名称" min-width="140" />
            <el-table-column label="类型" width="100">
              <template #default="{ row }">{{ dataTypeLabel(row.dataType) }}</template>
            </el-table-column>
            <el-table-column label="单位" width="90">
              <template #default="{ row }">{{ row.unit ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="范围" width="160">
              <template #default="{ row }">{{ formatRange(row.minValue, row.maxValue) }}</template>
            </el-table-column>
            <el-table-column label="小数位数" width="100">
              <template #default="{ row }">{{ row.decimals ?? '—' }}</template>
            </el-table-column>
            <el-table-column v-if="canWrite" label="操作" width="140">
              <template #default="{ row }">
                <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button link type="danger" @click="remove(row)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty v-if="propertiesFailed" description="加载失败，点击重试">
                <el-button type="primary" @click="loadProperties">重试</el-button>
              </el-empty>
              <el-empty v-else :description="canWrite ? '暂无属性：未定义物模型时按 V0.1 规则接收数据（数值一律浮点）' : '暂无属性'">
                <el-button v-if="canWrite" type="primary" @click="openCreate">新增属性</el-button>
                <el-button v-if="canWrite" @click="importVisible = true">导入物模型</el-button>
              </el-empty>
            </template>
          </el-table>
          <el-pagination
            v-model:current-page="propertyPage"
            v-model:page-size="propertySize"
            class="cd-pager"
            layout="total, sizes, prev, pager, next"
            :page-sizes="PAGE_SIZES"
            :total="propertyTotal"
            @current-change="onPropertyPageChange"
            @size-change="onPropertySizeChange"
          />
        </el-tab-pane>

        <el-tab-pane label="设备" name="devices">
          <el-table v-loading="loadingDevices" :data="devices">
            <el-table-column label="名称" min-width="180">
              <template #default="{ row }">
                <router-link :to="{ name: 'device-detail', params: { deviceKey: row.deviceKey } }">{{ row.name }}</router-link>
              </template>
            </el-table-column>
            <el-table-column prop="deviceKey" label="设备 Key" width="190" />
            <el-table-column label="在线" width="90">
              <template #default="{ row }"><OnlineDot :online="row.online" /></template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'" size="small">
                  {{ row.status === 'ENABLED' ? '启用' : '禁用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="最后上报" width="120">
              <template #default="{ row }"><RelativeTime :value="row.lastReportAt" /></template>
            </el-table-column>
            <el-table-column label="创建时间" width="180">
              <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
            </el-table-column>
            <template #empty>
              <el-empty v-if="devicesFailed" description="加载失败，点击重试">
                <el-button type="primary" @click="loadDevices">重试</el-button>
              </el-empty>
              <el-empty v-else description="该产品下暂无设备">
                <el-button v-if="canWrite" type="primary" @click="router.push({ name: 'devices' })">去设备页新建</el-button>
              </el-empty>
            </template>
          </el-table>
          <el-pagination
            v-model:current-page="devicePage"
            v-model:page-size="deviceSize"
            class="cd-pager"
            layout="total, sizes, prev, pager, next"
            :page-sizes="PAGE_SIZES"
            :total="deviceTotal"
            @current-change="onDevicePageChange"
            @size-change="onDeviceSizeChange"
          />
        </el-tab-pane>
      </el-tabs>
    </div>

    <PropertyDialog v-model="dialogVisible" :product-key="productKey" :property="editing" @saved="onPropertiesChanged" />
    <ThingModelImportDialog v-model="importVisible" :product-key="productKey" @imported="onPropertiesChanged" />
  </div>
</template>

<style scoped>
.basic {
  max-width: 560px;
}
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: var(--cd-space-2);
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--cd-space-3);
}
</style>
