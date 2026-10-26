<script setup lang="ts">
/**
 * 产品列表页：服务端分页（useTable，默认每页 10 条）；支持新建、编辑、删除产品，行内按钮可直接进入产品详情的各个标签页，
 * 「设备（在线/总数）」点击进入产品详情的设备标签。首次加载显示骨架屏；没有产品时引导新建，加载失败时改为「加载失败，点击重试」。新建时可选解码器，编辑时不能改。只读身份（usePermission().canWrite 为 false）看不到新建、编辑、删除。
 */

import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { productApi } from '@/api/products'
import { codecApi } from '@/api/codecs'
import { problemMessage } from '@/api/errors'
import type { CodecView, ProductView } from '@/api/types'
import { formatCount, formatDateTime } from '@/utils/format'
import { codecLabel } from '@/utils/thingModel'
import { PAGE_SIZES } from '@/utils/pagination'
import { tabQuery, type ProductTab } from '@/utils/productTabs'
import { useTable } from '@/composables/useTable'
import { usePermission } from '@/composables/usePermission'
import PageHeader from '@/components/PageHeader.vue'

const router = useRouter()
const { can } = usePermission()
const canWrite = computed(() => can('product.manage', true))

const codecs = ref<CodecView[]>([])
const { items, total, page, size, loading, loaded, failed, load, onSizeChange, onPageChange } = useTable<ProductView>(
  (p, s) => productApi.list(p, s),
)

const dialogVisible = ref(false)
const editingKey = ref<string | null>(null)
const saving = ref(false)
const removing = ref(false)
const form = reactive({ name: '', description: '', codec: 'standard-json' })

/** 加载解码器列表，用于新建表单的下拉和列表里的显示名称。 */
async function loadCodecs() {
  try {
    codecs.value = await codecApi.list()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

/** 进入产品详情页并定位到指定标签（默认「基本信息」；默认标签不写入 ?tab=）。 */
function openDetail(row: ProductView, tab: ProductTab = 'basic') {
  void router.push({ name: 'product-detail', params: { productKey: row.productKey }, query: tabQuery({}, tab) })
}

/** 打开新建对话框，表单重置为默认值（解码器默认 standard-json）。 */
function openCreate() {
  editingKey.value = null
  form.name = ''
  form.description = ''
  form.codec = 'standard-json'
  dialogVisible.value = true
}

/** 打开编辑对话框并回填该产品；编辑时请求里不带 codec，保持不变。 */
function openEdit(row: ProductView) {
  editingKey.value = row.productKey
  form.name = row.name
  form.description = row.description ?? ''
  form.codec = row.codec
  dialogVisible.value = true
}

/** 保存新建或编辑（由 editingKey 区分）。名称必填；保存中忽略重复点击；成功后关闭对话框并刷新列表。 */
async function save() {
  if (saving.value) return
  if (!form.name.trim()) {
    ElMessage.warning('请输入产品名称')
    return
  }
  saving.value = true
  const req = { name: form.name.trim(), description: form.description.trim() || null }
  try {
    if (editingKey.value) {
      await productApi.update(editingKey.value, req)
    } else {
      await productApi.create({ ...req, codec: form.codec })
    }
    dialogVisible.value = false
    ElMessage.success('已保存')
    await load()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    saving.value = false
  }
}

/** 删除产品：先确认再请求；成功后重新加载（删掉当前页最后一条时 useTable 自动退到上一页）。 */
async function remove(row: ProductView) {
  if (removing.value) return
  try {
    await ElMessageBox.confirm(`确定删除产品「${row.name}」？`, '删除产品', { type: 'warning' })
  } catch {
    return
  }
  removing.value = true
  try {
    await productApi.remove(row.productKey)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    removing.value = false
  }
}

onMounted(() => {
  void load()
  void loadCodecs()
})
</script>

<template>
  <div>
    <PageHeader title="产品" subtitle="一类设备的模板：定义物模型（属性）与解码器，设备归属于产品。">
      <template v-if="canWrite" #actions>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建产品</el-button>
      </template>
    </PageHeader>

    <div class="cd-panel">
      <el-skeleton v-if="!loaded" :rows="5" animated />
      <template v-else>
        <el-table v-loading="loading" :data="items">
          <el-table-column label="名称" min-width="200">
            <template #default="{ row }">
              <router-link :to="{ name: 'product-detail', params: { productKey: row.productKey } }">{{ row.name }}</router-link>
              <div v-if="row.description" class="cd-muted">{{ row.description }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="productKey" label="产品 Key" width="190" />
          <el-table-column label="解码器" width="120">
            <template #default="{ row }">{{ codecLabel(codecs, row.codec) }}</template>
          </el-table-column>
          <el-table-column prop="propertyCount" label="属性数" width="90" />
          <el-table-column label="设备（在线/总数）" width="150">
            <template #default="{ row }">
              <router-link
                class="device-count"
                :to="{ name: 'product-detail', params: { productKey: row.productKey }, query: tabQuery({}, 'devices') }"
              >{{ formatCount(row.onlineCount) }} / {{ row.deviceCount }}</router-link>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="180">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="230">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDetail(row)">详情</el-button>
              <el-button link type="primary" @click="openDetail(row, 'model')">物模型</el-button>
              <el-button v-if="canWrite" link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button v-if="canWrite" link type="danger" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty v-if="failed" description="加载失败，点击重试">
              <el-button type="primary" @click="load">重试</el-button>
            </el-empty>
            <el-empty v-else :description="canWrite ? '还没有产品，先创建一个产品' : '还没有产品'">
              <el-button v-if="canWrite" type="primary" @click="openCreate">新建产品</el-button>
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

    <el-dialog
      v-model="dialogVisible"
      :title="editingKey ? '编辑产品' : '新建产品'"
      width="min(480px, 92vw)"
      :close-on-click-modal="false"
    >
      <el-form label-width="80px" @submit.prevent="save">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="128" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" maxlength="512" />
        </el-form-item>
        <el-form-item v-if="!editingKey" label="解码器">
          <el-select v-model="form.codec">
            <el-option v-for="c in codecs" :key="c.name" :label="c.displayName" :value="c.name" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
