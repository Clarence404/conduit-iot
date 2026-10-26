<script setup lang="ts">
/**
 * 设备列表页：筛选栏（主行：关键字、产品、在线状态、启用状态；「更多筛选」：最后上报、创建时间；回车即查询）+ 服务端分页（useTable，默认每页 10 条）；在线状态用圆点，启用状态用标签，
 * 最后上报显示相对时间。支持新建设备、启用/禁用、重置密钥、删除（只读身份看不到这些操作），行内可跳到设备详情与所属产品。
 * 创建设备和重置密钥的响应里有密钥明文，通过 SecretDialog 只展示一次。首次加载显示骨架屏，没有设备时引导新建，加载失败时改为「加载失败，点击重试」。
 * 查询只使用「已应用」的筛选条件（点查询/回车时才从输入框复制）：翻页、刷新、新建默认产品、空态判断都以它为准，不受尚未提交的输入影响。
 * 已应用的筛选同步到路由 query（router.replace，键见 utils/deviceFilters），进入页面时从 query 还原（非法值忽略），
 * 因此 /devices?online=true 之类的链接可直接打开筛选结果；「更多筛选」里有条件生效时保持展开，开关上显示个数。
 */

import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { deviceApi } from '@/api/devices'
import { productApi } from '@/api/products'
import { siteApi } from '@/api/organization'
import { useAuthStore } from '@/stores/auth'
import { problemMessage } from '@/api/errors'
import type { DeviceStatus, DeviceView, ProductView, SiteView } from '@/api/types'
import { PAGE_SIZES } from '@/utils/pagination'
import {
  dayRangeToMillis,
  emptyDeviceFilters,
  hasAnyFilter,
  hiddenFilterCount,
  isCanonicalQuery,
  millisToDayRange,
  parseDeviceQuery,
  sameDeviceFilters,
  toDeviceParams,
  toDeviceQuery,
} from '@/utils/deviceFilters'
import { useTable } from '@/composables/useTable'
import { usePermission } from '@/composables/usePermission'
import PageHeader from '@/components/PageHeader.vue'
import FilterBar from '@/components/FilterBar.vue'
import OnlineDot from '@/components/OnlineDot.vue'
import RelativeTime from '@/components/RelativeTime.vue'
import SecretDialog from '@/components/SecretDialog.vue'

const router = useRouter()
const route = useRoute()
const { can } = usePermission()
const auth = useAuthStore()
const canWrite = computed(() => can('device.manage'))
function rowCan(row: DeviceView, permission: string) { return row.permissions ? row.permissions.includes(permission) : can(permission) }
const sites = ref<SiteView[]>([])

const products = ref<ProductView[]>([])
const productNames = computed(() => new Map(products.value.map((p) => [p.productKey, p.name])))
// 筛选栏里正在编辑的输入；点「查询」才复制到 applied。进入页面时从路由 query 还原（非法值忽略）
const filters = reactive(parseDeviceQuery(route.query))
// 最近一次查询所用的筛选条件：翻页、刷新、空态与新建默认产品都读它
const applied = reactive(parseDeviceQuery(route.query))
const filtered = computed(() => hasAnyFilter(applied))
/** 空列表（无筛选）的说明：可写身份给出创建引导，只读身份只显示中性文字 */
const emptyDescription = computed(() => {
  if (!canWrite.value) return '暂无设备'
  return products.value.length === 0 ? '还没有产品，先到「产品」页创建产品' : '还没有设备，先新建一台设备'
})

// 「更多筛选」：moreOpen 是手动展开标记；已应用的隐藏条件有生效项时强制展开（此时开关禁用）。
// 角标个数与强制展开都只看已应用的条件；编辑隐藏字段不会收起，且会把 moreOpen 置为 true
const moreOpen = ref(hiddenFilterCount(applied) > 0)
const moreCount = computed(() => hiddenFilterCount(applied))
const moreLocked = computed(() => moreCount.value > 0)
const moreExpanded = computed(() => moreOpen.value || moreLocked.value)
watch(
  () => [filters.reported, filters.createdFrom],
  () => {
    moreOpen.value = true
  },
)

/** 创建时间日期选择器的绑定值：与 filters 的毫秒区间互转（结束日含当天，所以 createdTo 取次日 0 点） */
const createdRange = computed<[Date, Date] | null>({
  get: () => (filters.createdFrom !== null && filters.createdTo !== null ? millisToDayRange(filters.createdFrom, filters.createdTo) : null),
  set: (range) => {
    if (range) {
      const { from, to } = dayRangeToMillis(range[0], range[1])
      filters.createdFrom = from
      filters.createdTo = to
    } else {
      filters.createdFrom = null
      filters.createdTo = null
    }
  },
})

const { items, total, page, size, loading, loaded, failed, load, search: searchPage, onSizeChange, onPageChange } = useTable<DeviceView>(
  (p, s) => deviceApi.list({ ...toDeviceParams(applied), page: p, size: s }),
)

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({ productKey: '', name: '', siteCode: '' })

const secretVisible = ref(false)
const secret = reactive({ deviceKey: '', value: '' })

// 行级操作进行中的设备 Key，防止重复触发
const busy = reactive(new Set<string>())

/** 加载产品列表，供筛选下拉、新建表单和产品名称显示使用（最多取 100 个）。 */
async function loadProducts() {
  try {
    products.value = (await productApi.list(1, 100)).items
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

/** 应用输入框里的筛选条件（关键字去首尾空格），同步到路由 query，并回到第 1 页查询。有隐藏条件生效时把展开标记置为 true，重置后行仍保持展开。 */
function search() {
  filters.keyword = filters.keyword.trim()
  Object.assign(applied, filters)
  if (hiddenFilterCount(applied) > 0) {
    moreOpen.value = true
  }
  void router.replace({ query: toDeviceQuery(applied) })
  return searchPage()
}

/** 清空筛选条件（输入、已应用的与路由 query）并回到第 1 页查询。 */
function reset() {
  Object.assign(filters, emptyDeviceFilters())
  void search()
}

/** 打开新建对话框：产品默认取已应用筛选的产品，没有筛选则取第一个产品。 */
function openCreate() {
  createForm.productKey = applied.productKey || products.value[0]?.productKey || ''
  createForm.name = ''
  createForm.siteCode = ''
  if (auth.user?.authorizationEnforced) {
    void siteApi.list().then((v) => { sites.value = v }).catch((e) => ElMessage.error(problemMessage(e)))
  }
  createVisible.value = true
}

/** 创建设备：成功后关闭对话框、展示一次性密钥并刷新列表。创建中忽略重复点击。 */
async function create() {
  if (creating.value) {
    return
  }
  if (!createForm.productKey || !createForm.name.trim()) {
    ElMessage.warning('请选择产品并填写设备名称')
    return
  }
  creating.value = true
  try {
    if (auth.user?.authorizationEnforced && !createForm.siteCode) { ElMessage.warning('请选择已授权站点'); return }
    const res = createForm.siteCode
      ? await deviceApi.create(createForm.productKey, createForm.name.trim(), createForm.siteCode)
      : await deviceApi.create(createForm.productKey, createForm.name.trim())
    createVisible.value = false
    showSecret(res.device.deviceKey, res.deviceSecret)
    await load()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    creating.value = false
  }
}

/** 弹出密钥对话框，展示设备密钥（只此一次）。 */
function showSecret(deviceKey: string, value: string) {
  secret.deviceKey = deviceKey
  secret.value = value
  secretVisible.value = true
}

/**
 * 启用/禁用设备（操作列按钮）：切换为相反状态，成功后用服务端返回的状态回写该行；失败时提示，该行保持不变。
 * 同一设备操作进行中时忽略重复触发。
 */
async function toggleStatus(row: DeviceView) {
  if (busy.has(row.deviceKey)) {
    return
  }
  busy.add(row.deviceKey)
  const next: DeviceStatus = row.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
  try {
    const updated = await deviceApi.update(row.deviceKey, row.name, next)
    row.status = updated.status
    ElMessage.success(updated.status === 'ENABLED' ? '已启用' : '已禁用')
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    busy.delete(row.deviceKey)
  }
}

/** 重置设备密钥：先确认（旧密钥立即失效），确认后展示新密钥。用户取消则不请求。同一设备操作进行中时忽略重复触发。 */
async function resetSecret(row: DeviceView) {
  if (busy.has(row.deviceKey)) {
    return
  }
  busy.add(row.deviceKey)
  try {
    try {
      await ElMessageBox.confirm(`重置后设备「${row.name}」需要使用新密钥重新连接，确定重置？`, '重置密钥', {
        type: 'warning',
      })
    } catch {
      return
    }
    try {
      const res = await deviceApi.resetSecret(row.deviceKey)
      showSecret(res.deviceKey, res.deviceSecret)
    } catch (e) {
      ElMessage.error(problemMessage(e))
    }
  } finally {
    busy.delete(row.deviceKey)
  }
}

/** 删除设备：先确认再请求；成功后重新加载（删掉当前页最后一条时 useTable 自动退到上一页）。 */
async function remove(row: DeviceView) {
  if (busy.has(row.deviceKey)) {
    return
  }
  busy.add(row.deviceKey)
  try {
    try {
      await ElMessageBox.confirm(`确定删除设备「${row.name}」？历史数据会随保留策略过期。`, '删除设备', {
        type: 'warning',
      })
    } catch {
      return
    }
    try {
      await deviceApi.remove(row.deviceKey)
      ElMessage.success('已删除')
      await load()
    } catch (e) {
      ElMessage.error(problemMessage(e))
    }
  } finally {
    busy.delete(row.deviceKey)
  }
}

/** 若当前 URL 的 query 含被忽略的非法值/无关键，改写成规范形式（replace，不产生历史记录） */
function normalizeUrl() {
  if (!isCanonicalQuery(route.query)) {
    void router.replace({ query: toDeviceQuery(parseDeviceQuery(route.query)) })
  }
}

/**
 * 路由 query 在页面不卸载的情况下变化（如已在 /devices?online=true 时点击侧边栏「设备」）：
 * 解析结果与已应用的筛选不同就同步到输入与已应用并回到第 1 页查询。search() 自己写入 query 时解析结果与 applied 相同，
 * 所以不会重复加载，也不会循环；离开本页时（路由已不是设备列表）忽略。
 */
watch(
  () => route.query,
  () => {
    if (route.name !== 'devices') {
      return
    }
    const parsed = parseDeviceQuery(route.query)
    if (!sameDeviceFilters(parsed, applied)) {
      Object.assign(filters, parsed)
      Object.assign(applied, parsed)
      if (hiddenFilterCount(applied) > 0) {
        moreOpen.value = true
      }
      void searchPage()
    }
    normalizeUrl()
  },
)

/** 进入页面：产品列表与设备列表并行加载（产品名称只用于显示，晚到也会更新），并把非法 query 改写干净。 */
onMounted(() => {
  void loadProducts()
  void load()
  normalizeUrl()
})
</script>

<template>
  <div>
    <PageHeader title="设备" subtitle="接入平台的设备：查看在线状态与最后上报，管理启用状态与密钥。">
      <template #actions>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button v-if="canWrite" type="primary" :icon="Plus" :disabled="products.length === 0" @click="openCreate">新建设备</el-button>
      </template>
    </PageHeader>

    <div class="cd-panel">
      <FilterBar :expanded="moreExpanded" :more-count="moreCount" :locked="moreLocked" @search="search" @reset="reset" @toggle="moreOpen = !moreOpen">
        <el-form-item label="关键字">
          <el-input v-model="filters.keyword" class="keyword" placeholder="名称或设备 Key" clearable />
        </el-form-item>
        <el-form-item label="产品">
          <el-select v-model="filters.productKey" clearable placeholder="全部" class="product-select">
            <el-option v-for="p in products" :key="p.productKey" :label="p.name" :value="p.productKey" />
          </el-select>
        </el-form-item>
        <el-form-item label="在线状态">
          <el-select v-model="filters.online" class="short-select" data-test="filter-online">
            <el-option label="全部" value="" />
            <el-option label="在线" value="true" />
            <el-option label="离线" value="false" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用状态">
          <el-select v-model="filters.status" class="short-select" data-test="filter-status">
            <el-option label="全部" value="" />
            <el-option label="启用" value="ENABLED" />
            <el-option label="禁用" value="DISABLED" />
          </el-select>
        </el-form-item>
        <template #more>
          <el-form-item label="最后上报">
            <el-select v-model="filters.reported" class="reported-select" data-test="filter-reported">
              <el-option label="全部" value="" />
              <el-option label="1 小时内有上报" value="RECENT" />
              <el-option label="超过 1 小时未上报" value="STALE" />
              <el-option label="从未上报" value="NEVER" />
            </el-select>
          </el-form-item>
          <el-form-item label="创建时间">
            <el-date-picker
              v-model="createdRange"
              type="daterange"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              class="created-picker"
            />
          </el-form-item>
        </template>
      </FilterBar>

      <el-skeleton v-if="!loaded" :rows="5" animated />
      <template v-else>
        <el-table v-loading="loading" :data="items">
          <el-table-column label="名称" min-width="200">
            <template #default="{ row }">
              <router-link :to="{ name: 'device-detail', params: { deviceKey: row.deviceKey } }">{{ row.name }}</router-link>
              <div class="cd-muted">{{ row.deviceKey }}</div>
            </template>
          </el-table-column>
          <el-table-column label="产品" min-width="160">
            <template #default="{ row }">
              <router-link :to="{ name: 'product-detail', params: { productKey: row.productKey } }">
                {{ productNames.get(row.productKey) ?? row.productKey }}
              </router-link>
            </template>
          </el-table-column>
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
          <el-table-column label="操作" width="230">
            <template #default="{ row }">
              <el-button link type="primary" @click="router.push({ name: 'device-detail', params: { deviceKey: row.deviceKey } })">
                详情
              </el-button>
              <template v-if="canWrite || rowCan(row, 'device.secret.rotate')">
                <el-button v-if="rowCan(row, 'device.manage')" link :type="row.status === 'ENABLED' ? 'warning' : 'success'" :loading="busy.has(row.deviceKey)" @click="toggleStatus(row)">
                  {{ row.status === 'ENABLED' ? '禁用' : '启用' }}
                </el-button>
                <el-button v-if="rowCan(row, 'device.secret.rotate')" link type="warning" @click="resetSecret(row)">重置密钥</el-button>
                <el-button v-if="rowCan(row, 'device.manage')" link type="danger" @click="remove(row)">删除</el-button>
              </template>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty v-if="failed" description="加载失败，点击重试">
              <el-button type="primary" @click="load">重试</el-button>
            </el-empty>
            <el-empty v-else-if="filtered" description="没有符合条件的设备">
              <el-button @click="reset">重置筛选</el-button>
            </el-empty>
            <el-empty v-else :description="emptyDescription">
              <template v-if="canWrite">
                <el-button v-if="products.length === 0" type="primary" @click="router.push({ name: 'products' })">去创建产品</el-button>
                <el-button v-else type="primary" @click="openCreate">新建设备</el-button>
              </template>
            </el-empty>
          </template>
        </el-table>

        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          class="cd-pager"
          layout="total, sizes, prev, pager, next"
          :page-sizes="PAGE_SIZES"
          :total="total"
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </template>
    </div>

    <el-dialog v-model="createVisible" title="新建设备" width="min(480px, 92vw)" :close-on-click-modal="false">
      <el-form label-width="80px" @submit.prevent="create">
        <el-form-item label="产品" required>
          <el-select v-model="createForm.productKey" style="width: 100%">
            <el-option v-for="p in products" :key="p.productKey" :label="p.name" :value="p.productKey" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="createForm.name" maxlength="128" />
        </el-form-item>
        <el-form-item v-if="auth.user?.authorizationEnforced" label="站点" required>
          <el-select v-model="createForm.siteCode" placeholder="请选择可管理的站点">
            <el-option v-for="s in sites.filter((s) => s.permissions?.includes('device.manage'))" :key="s.code" :value="s.code" :label="s.name" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="create">创建</el-button>
      </template>
    </el-dialog>

    <SecretDialog v-model="secretVisible" :device-key="secret.deviceKey" :secret="secret.value" />
  </div>
</template>

<style scoped>
.product-select {
  width: 180px;
}
.short-select {
  width: 100px;
}
.reported-select {
  width: 180px;
}
.created-picker {
  width: 260px;
}
</style>
