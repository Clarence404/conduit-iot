<script setup lang="ts">
/**
 * 平台首页（未代入的平台管理员）：4 个指标卡（租户、用户、设备、在线率）、「实时吞吐」一行
 * （入库 TPS、峰值、累计入库、累计死信，均为 server 本进程口径，重启归零）与「设备最多的租户」列表，
 * 点击租户卡（Enter / 空格）进入租户管理，点击某个租户进入租户管理并按该租户筛选。数据来自 GET /api/v1/platform/overview，
 * 每 30 秒自动刷新（页面隐藏时暂停）；首次加载失败时显示「加载失败，点击重试」；服务端 Redis 不可用时在线相关数值为 null，显示「—」。
 * 刷新失败时保留上次数据，同一段连续失败只提示一次。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Connection, Cpu, OfficeBuilding, TrendCharts, User, WarningFilled } from '@element-plus/icons-vue'
import { platformApi } from '@/api/platform'
import { problemMessage } from '@/api/errors'
import type { PlatformOverview } from '@/api/types'
import { createVisiblePoller } from '@/composables/usePolling'
import { createRequestSeq } from '@/utils/requestSeq'
import { formatCount } from '@/utils/format'
import { onlineRate } from '@/utils/overview'
import PageHeader from '@/components/PageHeader.vue'

const REFRESH_MS = 30_000

const router = useRouter()
const data = ref<PlatformOverview | null>(null)
/** 首次加载失败（还没有任何数据）：显示可重试的失败状态而不是一直骨架屏 */
const failed = ref(false)
const seq = createRequestSeq()
let errorShown = false

/** 在线率；在线数未知时为 null */
const rate = computed(() => (data.value ? onlineRate(data.value.onlineCount, data.value.deviceCount) : null))

/**
 * 速率显示：null 表示采样还不足、取不到速率（显示「—」），
 * 0 是真实的零流量（照常显示 0），两者不能混为一谈。
 */
function formatRate(v: number | null): string {
  return v === null ? '—' : v.toLocaleString('zh-CN', { maximumFractionDigits: 1 })
}

/** 累计量用千分位，便于一眼看出数量级 */
function formatTotal(v: number): string {
  return v.toLocaleString('zh-CN')
}

/** 速率窗口的中文说明，如「近 60 秒平均」 */
const windowLabel = computed(() => (data.value ? `近 ${data.value.throughput.windowSeconds} 秒平均` : ''))

/** 加载平台概况：只有最后发出的请求能写入结果；失败时保留上次数据，连续失败只提示一次 */
async function load() {
  const id = seq.next()
  try {
    const res = await platformApi.overview()
    if (seq.isLatest(id)) {
      data.value = res
      failed.value = false
      errorShown = false
    }
  } catch (e) {
    if (!seq.isLatest(id)) return
    if (!data.value) failed.value = true
    if (!errorShown) {
      errorShown = true
      ElMessage.error(problemMessage(e))
    }
  }
}

/** 点击「重试」：回到骨架屏并重新加载 */
function retry() {
  failed.value = false
  void load()
}

const poller = createVisiblePoller(load, REFRESH_MS)

/** 进入租户管理；给出 code 时按该租户筛选 */
function openTenants(code?: string) {
  void router.push(code ? { name: 'tenants', query: { keyword: code } } : { name: 'tenants' })
}

onMounted(() => poller.start())
onBeforeUnmount(() => {
  poller.dispose()
  seq.next()
})
</script>

<template>
  <div class="home">
    <PageHeader title="平台首页" subtitle="全平台运行概况，每 30 秒自动刷新。" />

    <el-empty v-if="!data && failed" description="加载失败，点击重试">
      <el-button type="primary" @click="retry">重试</el-button>
    </el-empty>
    <el-skeleton v-else-if="!data" :rows="6" animated />
    <template v-else>
      <div class="tiles">
        <div class="tile cd-panel clickable" data-test="tile-tenants" role="button" tabindex="0" @click="openTenants()" @keydown.enter="openTenants()" @keydown.space.prevent="openTenants()">
          <div class="tile-head"><el-icon><OfficeBuilding /></el-icon>租户</div>
          <div class="tile-value">{{ data.tenantCount }}</div>
          <div class="tile-sub">启用 {{ data.activeTenantCount }} · 停用 {{ data.disabledTenantCount }}</div>
        </div>
        <div class="tile cd-panel" data-test="tile-users">
          <div class="tile-head"><el-icon><User /></el-icon>用户</div>
          <div class="tile-value">{{ data.userCount }}</div>
          <div class="tile-sub">各租户的管理员与只读用户</div>
        </div>
        <div class="tile cd-panel" data-test="tile-devices">
          <div class="tile-head"><el-icon><Cpu /></el-icon>设备</div>
          <div class="tile-value">{{ data.deviceCount }}</div>
          <div class="tile-sub">共 {{ data.productCount }} 个产品</div>
        </div>
        <div class="tile cd-panel" data-test="tile-online">
          <div class="tile-head"><el-icon><Connection /></el-icon>在线率</div>
          <div class="tile-value">{{ rate === null ? '—' : `${rate}%` }}</div>
          <el-progress v-if="rate !== null" :percentage="rate" :show-text="false" :stroke-width="6" status="success" />
          <div class="tile-sub">
            {{ rate === null ? '在线状态暂不可用' : `在线 ${formatCount(data.onlineCount)} / 设备 ${data.deviceCount}` }}
          </div>
        </div>
      </div>

      <div class="section-head">
        实时吞吐
        <span class="cd-muted">server 本进程口径，进程重启后归零；跨重启与多实例的口径请看监控面板。</span>
      </div>
      <div class="tiles">
        <div class="tile cd-panel" data-test="tile-tps">
          <div class="tile-head"><el-icon><TrendCharts /></el-icon>入库 TPS</div>
          <div class="tile-value">{{ formatRate(data.throughput.ingestTps) }}</div>
          <div class="tile-sub">
            {{ data.throughput.ingestTps === null ? '采样中，还取不到速率' : `${windowLabel}，条/秒` }}
          </div>
        </div>
        <div class="tile cd-panel" data-test="tile-peak">
          <div class="tile-head"><el-icon><TrendCharts /></el-icon>峰值 TPS</div>
          <div class="tile-value">{{ formatRate(data.throughput.peakTps) }}</div>
          <div class="tile-sub">本进程启动以来的最高值</div>
        </div>
        <div class="tile cd-panel" data-test="tile-ingested">
          <div class="tile-head"><el-icon><Cpu /></el-icon>累计入库</div>
          <div class="tile-value">{{ formatTotal(data.throughput.ingestTotal) }}</div>
          <div class="tile-sub">写入时序库的属性消息条数</div>
        </div>
        <div class="tile cd-panel" data-test="tile-dead">
          <div class="tile-head"><el-icon><WarningFilled /></el-icon>累计死信</div>
          <div class="tile-value" :class="{ warn: data.throughput.deadTotal > 0 }">
            {{ formatTotal(data.throughput.deadTotal) }}
          </div>
          <div class="tile-sub">处理失败、超过投递次数或租户停用</div>
        </div>
      </div>

      <div class="list cd-panel top-tenants">
        <div class="list-head">设备最多的租户</div>
        <el-empty v-if="data.topTenants.length === 0" description="还没有租户" :image-size="64">
          <el-button type="primary" @click="openTenants()">去新建租户</el-button>
        </el-empty>
        <ul v-else>
          <li v-for="t in data.topTenants" :key="t.code">
            <!-- 原生按钮：键盘 Enter / 空格都能触发点击，保持 ul/li 列表语义 -->
            <button type="button" class="row" @click="openTenants(t.code)">
              <span class="row-main">
                <span class="row-title">{{ t.name }}</span>
                <span class="cd-muted">{{ t.code }} · 在线 / 设备</span>
              </span>
              <span class="row-count">{{ formatCount(t.onlineCount) }} / {{ t.deviceCount }}</span>
            </button>
          </li>
        </ul>
      </div>
    </template>
  </div>
</template>

<style scoped>
.tiles {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--cd-space-4);
  margin-bottom: var(--cd-space-4);
}
.tile {
  display: flex;
  flex-direction: column;
  gap: var(--cd-space-2);
}
.tile.clickable {
  cursor: pointer;
}
.tile.clickable:hover,
.tile.clickable:focus-visible {
  outline: 1px solid var(--el-color-primary);
}
.tile-head {
  display: flex;
  align-items: center;
  gap: var(--cd-space-2);
  color: var(--cd-text-muted);
  font-size: 13px;
}
.tile-value {
  color: var(--el-text-color-primary);
  font-size: 28px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.tile-sub {
  color: var(--cd-text-muted);
  font-size: 12px;
}
.section-head {
  display: flex;
  align-items: baseline;
  gap: var(--cd-space-2);
  margin-bottom: var(--cd-space-2);
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.section-head .cd-muted {
  font-size: 12px;
  font-weight: 400;
}
.tile-value.warn {
  color: var(--el-color-warning);
}
.list-head {
  margin-bottom: var(--cd-space-2);
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.list ul {
  margin: 0;
  padding: 0;
  list-style: none;
}
.row {
  width: 100%;
  border: none;
  background: none;
  color: inherit;
  font: inherit;
  text-align: left;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--cd-space-3);
  padding: var(--cd-space-2) var(--cd-space-2);
  border-radius: var(--cd-radius-sm);
  cursor: pointer;
}
.row:hover,
.row:focus-visible {
  background: var(--el-fill-color-light);
  outline: none;
}
.row-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.row-title {
  overflow: hidden;
  color: var(--el-text-color-primary);
  white-space: nowrap;
  text-overflow: ellipsis;
}
.row-count {
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}
</style>
