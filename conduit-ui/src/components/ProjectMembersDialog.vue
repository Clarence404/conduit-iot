<script setup lang="ts">
/** 项目成员委派：仅编辑当前项目绑定，服务端禁止修改租户/其他项目权限。 */
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { authorizationApi, type Binding, type RoleDefinition } from '@/api/authorization'
import { siteApi } from '@/api/organization'
import type { SiteView } from '@/api/types'
import { problemMessage } from '@/api/errors'

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ projectCode: string; projectName: string }>()
const members = ref<{ username: string; display_name: string; status: string }[]>([])
const roles = ref<RoleDefinition[]>([])
const sites = ref<SiteView[]>([])
const username = ref('')
const bindings = ref<Binding[]>([])
const editing = ref(false)
const loading = ref(false)
const saving = ref(false)
const ready = ref(false)
const error = ref('')
let generation = 0
async function load() {
  const id = ++generation; ready.value = false; loading.value = true; error.value = ''; editing.value = false
  try {
    const [m, r, s] = await Promise.all([authorizationApi.projectMembers(props.projectCode), authorizationApi.projectRoles(props.projectCode), siteApi.list()])
    if (id !== generation) return
    members.value = m; roles.value = r; sites.value = s.filter((x) => x.projectCode === props.projectCode); ready.value = true
  } catch (e) { if (id === generation) error.value = problemMessage(e) }
  finally { if (id === generation) loading.value = false }
}
watch(() => [visible.value, props.projectCode], ([open]) => { if (open) void load(); else generation++ }, { immediate: true })
function newBinding(): Binding {
  const role = roles.value[0]
  return { roleKey: role?.role_key ?? '', scopeType: role?.scope_mask.includes('PROJECT') ? 'PROJECT' : 'SITE',
    projectCode: props.projectCode, siteCode: null }
}
function add() { username.value = ''; bindings.value = [newBinding()]; editing.value = true }
async function edit(target: string) {
  const id = ++generation; loading.value = true; ready.value = false; error.value = ''
  try {
    const rows = await authorizationApi.projectBindings(props.projectCode, target)
    if (id !== generation) return
    username.value = target
    bindings.value = rows.map((b) => ({ roleKey: b.role_key, scopeType: b.scope_type, projectCode: props.projectCode, siteCode: b.site_code ?? null }))
    editing.value = true; ready.value = true
  } catch (e) { if (id === generation) error.value = problemMessage(e) }
  finally { if (id === generation) loading.value = false }
}
async function save() {
  const target = username.value.trim()
  if (!target || !ready.value || saving.value) return
  saving.value = true; error.value = ''
  try {
    await authorizationApi.replaceProjectBindings(props.projectCode, target, bindings.value)
    ElMessage.success('项目成员授权已保存')
    await load()
  } catch (e) { error.value = problemMessage(e) }
  finally { saving.value = false }
}
</script>
<template>
  <el-dialog v-model="visible" :title="`项目成员 · ${projectName}`" width="780px" :close-on-click-modal="false">
    <el-alert title="仅修改此项目的绑定。给兼容账号首次添加项目授权，会接管其旧角色权限；空列表将撤销此项目授权。" type="warning" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" style="margin-top:8px" />
    <div v-loading="loading" style="min-height:100px;margin-top:12px">
      <template v-if="!editing">
        <el-button :disabled="!ready || roles.length === 0" type="primary" @click="add">添加现有用户</el-button>
        <el-table :data="members" style="margin-top:12px">
          <el-table-column prop="username" label="用户名" />
          <el-table-column prop="display_name" label="姓名" />
          <el-table-column prop="status" label="状态" width="100" />
          <el-table-column label="操作" width="110"><template #default="{row}"><el-button link type="primary" @click="edit(row.username)">管理授权</el-button></template></el-table-column>
        </el-table>
      </template>
      <template v-else>
        <el-input v-model="username" :disabled="members.some(m => m.username === username)" placeholder="输入租户内已有用户名" style="margin-bottom:12px" />
        <div v-for="(b, index) in bindings" :key="index" class="binding">
          <el-select v-model="b.roleKey" aria-label="角色" @change="b.scopeType = 'PROJECT'; b.siteCode = null"><el-option v-for="r in roles" :key="r.role_key" :value="r.role_key" :label="r.name" /></el-select>
          <el-select v-model="b.scopeType" aria-label="范围" @change="b.siteCode = null"><el-option v-for="s in (roles.find(r => r.role_key === b.roleKey)?.scope_mask.split(',').filter(s => s !== 'TENANT') ?? [])" :key="s" :value="s" :label="s === 'PROJECT' ? '整个项目' : '指定站点'" /></el-select>
          <el-select v-if="b.scopeType === 'SITE'" v-model="b.siteCode" aria-label="站点"><el-option v-for="s in sites" :key="s.code" :value="s.code" :label="s.name" /></el-select>
          <el-button link type="danger" @click="bindings.splice(index,1)">移除</el-button>
        </div>
        <el-button :disabled="roles.length === 0" @click="bindings.push(newBinding())">添加授权</el-button>
        <div class="actions"><el-button @click="editing = false">返回</el-button><el-button type="primary" :disabled="!ready" :loading="saving" @click="save">保存</el-button></div>
      </template>
    </div>
  </el-dialog>
</template>
<style scoped>
.binding { display:flex; gap:8px; margin:8px 0; }
.binding .el-select { width:170px; }
.actions { display:flex; justify-content:flex-end; gap:8px; margin-top:12px; }
</style>
