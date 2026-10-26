<script setup lang="ts">
/** 用户资源授权：多条角色/范围取并集；切换目标丢弃旧响应，加载成功之前禁止保存。 */
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { authorizationApi, type Binding, type RoleDefinition } from '@/api/authorization'
import { projectApi, siteApi } from '@/api/organization'
import type { ProjectView, SiteView } from '@/api/types'
import { problemMessage } from '@/api/errors'
const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ username: string }>()
const roles = ref<RoleDefinition[]>([])
const projects = ref<ProjectView[]>([])
const sites = ref<SiteView[]>([])
const bindings = ref<Binding[]>([])
const loading = ref(false)
const ready = ref(false)
const saving = ref(false)
const error = ref('')
let generation = 0
watch(() => [visible.value, props.username] as const, async ([open, username]) => {
  const id = ++generation
  ready.value = false
  if (!open) return
  loading.value = true
  error.value = ''
  try {
    const [r, p, s, b] = await Promise.all([authorizationApi.roles(), projectApi.list(), siteApi.list(), authorizationApi.bindings(username)])
    if (id !== generation) return
    roles.value = r; projects.value = p; sites.value = s
    bindings.value = b.map((v) => ({ roleKey: v.role_key, scopeType: v.scope_type, projectCode: v.project_code ?? null, siteCode: v.site_code ?? null }))
    ready.value = true
  } catch (e) { if (id === generation) error.value = problemMessage(e) }
  finally { if (id === generation) loading.value = false }
}, { immediate: true })
function resetScope(b: Binding) { b.projectCode = null; b.siteCode = null }
async function save() {
  if (!ready.value || saving.value) return
  saving.value = true
  try {
    await authorizationApi.replace(props.username, bindings.value)
    ElMessage.success('授权已保存，下一次请求生效')
    visible.value = false
  } catch (e) { error.value = problemMessage(e) }
  finally { saving.value = false }
}
</script>
<template>
  <el-dialog v-model="visible" :title="`资源权限 · ${username}`" width="760px" :close-on-click-modal="false">
    <el-alert title="保存后将使用下方授权替代账号的兼容角色。空列表表示撤销全部资源权限；多个范围取并集。" type="warning" :closable="false" show-icon />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-loading="loading" class="bindings">
      <div v-for="(b, index) in bindings" :key="index" class="binding">
        <el-select v-model="b.roleKey" placeholder="角色" aria-label="角色" @change="b.scopeType = ''; resetScope(b)">
          <el-option v-for="r in roles.filter((r) => r.status === 'ACTIVE')" :key="r.role_key" :value="r.role_key" :label="r.name" />
        </el-select>
        <el-select v-model="b.scopeType" placeholder="范围" aria-label="范围" @change="resetScope(b)">
          <el-option v-for="s in (roles.find((r) => r.role_key === b.roleKey)?.scope_mask.split(',') ?? [])" :key="s" :value="s" :label="({TENANT:'整个租户',PROJECT:'指定项目',SITE:'指定站点'} as Record<string,string>)[s]" />
        </el-select>
        <el-select v-if="b.scopeType !== 'TENANT'" v-model="b.projectCode" placeholder="项目" aria-label="项目" @change="b.siteCode = null">
          <el-option v-for="p in projects" :key="p.code" :value="p.code" :label="p.name" />
        </el-select>
        <el-select v-if="b.scopeType === 'SITE'" v-model="b.siteCode" placeholder="站点" aria-label="站点">
          <el-option v-for="s in sites.filter((s) => s.projectCode === b.projectCode)" :key="s.code" :value="s.code" :label="s.name" />
        </el-select>
        <el-button type="danger" link @click="bindings.splice(index, 1)">移除</el-button>
      </div>
      <el-button :disabled="!ready" @click="bindings.push({ roleKey: 'VIEWER', scopeType: 'PROJECT', projectCode: null, siteCode: null })">添加授权</el-button>
    </div>
    <template #footer><el-button @click="visible = false">取消</el-button><el-button type="primary" :disabled="!ready" :loading="saving" @click="save">保存授权</el-button></template>
  </el-dialog>
</template>
<style scoped>
.bindings { padding: 16px 0; min-height: 80px; }
.binding { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.binding .el-select { width: 150px; }
.el-alert + .el-alert { margin-top: 12px; }
</style>
