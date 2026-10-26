<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { assetApi, customerApi, projectApi, siteApi } from '@/api/organization'
import { problemMessage } from '@/api/errors'
import type { AssetView, CustomerView, OrganizationStatus, ProjectView, SiteView } from '@/api/types'
import { usePermission } from '@/composables/usePermission'
import PageHeader from '@/components/PageHeader.vue'

type Kind = 'customer' | 'project' | 'site' | 'asset'
type Row = CustomerView | ProjectView | SiteView | AssetView
const { canWrite } = usePermission()
const active = ref<Kind>('project')
const loading = ref(false)
const customers = ref<CustomerView[]>([])
const projects = ref<ProjectView[]>([])
const sites = ref<SiteView[]>([])
const assets = ref<AssetView[]>([])
const dialog = ref(false)
const editing = ref<Row | null>(null)
const saving = ref(false)
const form = reactive({ code: '', name: '', status: 'ACTIVE' as OrganizationStatus, customerCode: '', projectCode: '', timezone: 'Asia/Shanghai', siteCode: '', parentCode: '', type: '' })

const rows = computed<Row[]>(() => ({ customer: customers.value, project: projects.value, site: sites.value, asset: assets.value })[active.value])
const titles: Record<Kind, string> = { customer: '客户', project: '项目', site: '站点', asset: '资产' }

async function load() {
  loading.value = true
  try {
    ;[customers.value, projects.value, sites.value, assets.value] = await Promise.all([
      customerApi.list(), projectApi.list(), siteApi.list(), assetApi.list(),
    ])
  } catch (e) { ElMessage.error(problemMessage(e)) } finally { loading.value = false }
}

function openCreate() {
  editing.value = null
  Object.assign(form, { code: '', name: '', status: 'ACTIVE', customerCode: '', projectCode: projects.value[0]?.code ?? '', timezone: 'Asia/Shanghai', siteCode: sites.value[0]?.code ?? '', parentCode: '', type: '' })
  dialog.value = true
}

function openEdit(row: Row) {
  editing.value = row
  Object.assign(form, { code: row.code, name: row.name, status: row.status, customerCode: 'customerCode' in row ? row.customerCode ?? '' : '', projectCode: 'projectCode' in row ? row.projectCode : '', timezone: 'timezone' in row ? row.timezone : 'Asia/Shanghai', siteCode: 'siteCode' in row ? row.siteCode : '', parentCode: 'parentCode' in row ? row.parentCode ?? '' : '', type: 'type' in row ? row.type ?? '' : '' })
  dialog.value = true
}

async function save() {
  if (!form.code.trim() || !form.name.trim()) return ElMessage.warning('请填写编码和名称')
  saving.value = true
  try {
    const common = { code: form.code.trim(), name: form.name.trim(), status: form.status }
    const input = active.value === 'customer' ? common
      : active.value === 'project' ? { ...common, customerCode: form.customerCode || null }
      : active.value === 'site' ? { ...common, projectCode: form.projectCode, timezone: form.timezone }
      : { ...common, siteCode: form.siteCode, parentCode: form.parentCode || null, type: form.type || null }
    const api = { customer: customerApi, project: projectApi, site: siteApi, asset: assetApi }[active.value] as any
    await (editing.value ? api.update(editing.value.code, input) : api.create(input))
    dialog.value = false
    ElMessage.success('已保存')
    await load()
  } catch (e) { ElMessage.error(problemMessage(e)) } finally { saving.value = false }
}

async function remove(row: Row) {
  try { await ElMessageBox.confirm(`确定删除${titles[active.value]}「${row.name}」？`, '删除确认', { type: 'warning' }) } catch { return }
  try {
    const api = { customer: customerApi, project: projectApi, site: siteApi, asset: assetApi }[active.value] as any
    await api.remove(row.code); ElMessage.success('已删除'); await load()
  } catch (e) { ElMessage.error(problemMessage(e)) }
}

function relation(row: Row) {
  if ('siteCode' in row) return row.parentCode ? `${row.siteCode} / ${row.parentCode}` : row.siteCode
  if ('projectCode' in row) return row.projectCode
  if ('customerCode' in row) return row.customerCode ?? '租户内部项目'
  return '—'
}
function isDefault(row: Row) { return 'systemDefault' in row && row.systemDefault }
onMounted(load)
</script>

<template>
  <div>
    <PageHeader title="组织" subtitle="管理租户内的客户、项目、站点和资产；设备在详情页分配到站点或资产。">
      <template #actions><el-button :icon="Refresh" @click="load">刷新</el-button><el-button v-if="canWrite" type="primary" :icon="Plus" @click="openCreate">新建{{ titles[active] }}</el-button></template>
    </PageHeader>
    <div class="cd-panel">
      <el-tabs v-model="active">
        <el-tab-pane label="客户" name="customer"/><el-tab-pane label="项目" name="project"/><el-tab-pane label="站点" name="site"/><el-tab-pane label="资产" name="asset"/>
      </el-tabs>
      <el-table v-loading="loading" :data="rows">
        <el-table-column prop="name" label="名称" min-width="180"><template #default="{ row }"><strong>{{ row.name }}</strong><div class="cd-muted">{{ row.code }}</div></template></el-table-column>
        <el-table-column v-if="active !== 'customer'" label="归属" min-width="180"><template #default="{ row }">{{ relation(row) }}</template></el-table-column>
        <el-table-column v-if="active === 'site'" prop="timezone" label="时区" width="150"/>
        <el-table-column v-if="active === 'asset'" prop="type" label="类型" width="140"><template #default="{ row }">{{ row.type || '—' }}</template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status === 'ACTIVE' ? '启用' : '禁用' }}</el-tag><el-tag v-if="isDefault(row)" class="default-tag" type="warning">默认</el-tag></template></el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="150"><template #default="{ row }"><el-button link type="primary" @click="openEdit(row)">编辑</el-button><el-button v-if="!isDefault(row)" link type="danger" @click="remove(row)">删除</el-button></template></el-table-column>
        <template #empty><el-empty :description="`暂无${titles[active]}`"><el-button v-if="canWrite" type="primary" @click="openCreate">新建{{ titles[active] }}</el-button></el-empty></template>
      </el-table>
    </div>
    <el-dialog v-model="dialog" :title="`${editing ? '编辑' : '新建'}${titles[active]}`" width="520px">
      <el-form label-width="90px">
        <el-form-item label="编码" required><el-input v-model="form.code" :disabled="!!editing" placeholder="小写字母、数字和连字符"/></el-form-item>
        <el-form-item label="名称" required><el-input v-model="form.name"/></el-form-item>
        <el-form-item v-if="active === 'project'" label="客户"><el-select v-model="form.customerCode" clearable placeholder="租户内部项目"><el-option v-for="x in customers" :key="x.code" :label="x.name" :value="x.code"/></el-select></el-form-item>
        <el-form-item v-if="active === 'site'" label="项目" required><el-select v-model="form.projectCode"><el-option v-for="x in projects" :key="x.code" :label="x.name" :value="x.code"/></el-select></el-form-item>
        <el-form-item v-if="active === 'site'" label="时区" required><el-input v-model="form.timezone"/></el-form-item>
        <el-form-item v-if="active === 'asset'" label="站点" required><el-select v-model="form.siteCode"><el-option v-for="x in sites" :key="x.code" :label="x.name" :value="x.code"/></el-select></el-form-item>
        <el-form-item v-if="active === 'asset'" label="父资产"><el-select v-model="form.parentCode" clearable><el-option v-for="x in assets.filter(a => a.code !== editing?.code && a.siteCode === form.siteCode)" :key="x.code" :label="x.name" :value="x.code"/></el-select></el-form-item>
        <el-form-item v-if="active === 'asset'" label="类型"><el-input v-model="form.type"/></el-form-item>
        <el-form-item label="状态"><el-radio-group v-model="form.status"><el-radio value="ACTIVE">启用</el-radio><el-radio value="DISABLED">禁用</el-radio></el-radio-group></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.default-tag{margin-left:var(--cd-space-1)}
.el-select{width:100%}
</style>
