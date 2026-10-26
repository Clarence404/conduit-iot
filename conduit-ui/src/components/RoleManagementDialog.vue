<script setup lang="ts">
/** 自定义角色管理：预置角色只读，权限码来自服务端目录，绑定中的角色由服务端保护。 */
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { authorizationApi, type RoleDefinition, type RoleInput } from '@/api/authorization'
import { problemMessage } from '@/api/errors'

const visible = defineModel<boolean>({ required: true })
const roles = ref<RoleDefinition[]>([])
const catalog = ref<string[]>([])
const loading = ref(false)
const saving = ref(false)
const ready = ref(false)
const error = ref('')
const editing = ref(false)
const editingKey = ref<string | null>(null)
const form = ref<RoleInput>({ roleKey: '', name: '', scopeMask: 'PROJECT', status: 'ACTIVE', permissions: [] })
let generation = 0
async function load() {
  const id = ++generation
  loading.value = true; ready.value = false; error.value = ''
  try {
    const [r, p] = await Promise.all([authorizationApi.roles(), authorizationApi.permissionCatalog()])
    if (id !== generation) return
    roles.value = r; catalog.value = [...p].sort(); ready.value = true
  } catch (e) { if (id === generation) error.value = problemMessage(e) }
  finally { if (id === generation) loading.value = false }
}
watch(visible, (open) => { if (open) void load(); else generation++ }, { immediate: true })
function create() {
  editing.value = true
  editingKey.value = null
  form.value = { roleKey: 'CUSTOM_', name: '', scopeMask: 'PROJECT', status: 'ACTIVE', permissions: [] }
}
function edit(row: RoleDefinition) {
  editing.value = true
  editingKey.value = row.role_key
  form.value = { roleKey: row.role_key, name: row.name, scopeMask: row.scope_mask,
    status: row.status as RoleInput['status'], permissions: [...row.permissions] }
}
async function save() {
  if (!ready.value || saving.value) return
  saving.value = true; error.value = ''
  try {
    if (editingKey.value) await authorizationApi.updateRole(editingKey.value, form.value)
    else await authorizationApi.createRole(form.value)
    ElMessage.success('角色已保存')
    editing.value = false
    await load()
  } catch (e) { error.value = problemMessage(e) }
  finally { saving.value = false }
}
async function remove(row: RoleDefinition) {
  try { await ElMessageBox.confirm(`删除角色「${row.name}」？已有绑定的角色无法删除。`, '删除角色', { type: 'warning' }) }
  catch { return }
  try { await authorizationApi.deleteRole(row.role_key); ElMessage.success('已删除'); await load() }
  catch (e) { error.value = problemMessage(e) }
}
</script>
<template>
  <el-dialog v-model="visible" title="角色管理" width="780px" :close-on-click-modal="false">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-loading="loading">
      <el-button v-if="ready && !editing" type="primary" @click="create">新建自定义角色</el-button>
      <el-table v-if="!editing" :data="roles" style="margin-top:12px">
        <el-table-column prop="name" label="角色" min-width="150"><template #default="{row}">{{ row.name }}<div class="cd-muted">{{ row.role_key }}</div></template></el-table-column>
        <el-table-column prop="scope_mask" label="可绑定范围" min-width="135" />
        <el-table-column label="权限数" width="90"><template #default="{row}">{{ row.permissions.length }}</template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="{row}">{{ row.status === 'ACTIVE' ? '启用' : '停用' }}</template></el-table-column>
        <el-table-column label="操作" width="130"><template #default="{row}"><template v-if="!row.built_in"><el-button link type="primary" @click="edit(row)">编辑</el-button><el-button link type="danger" @click="remove(row)">删除</el-button></template><span v-else class="cd-muted">内置</span></template></el-table-column>
      </el-table>
      <el-form v-else label-width="90px" style="margin-top:16px">
        <el-form-item label="角色键" required><el-input v-model="form.roleKey" :disabled="!!editingKey" placeholder="CUSTOM_INSPECTOR" /></el-form-item>
        <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="范围" required><el-select v-model="form.scopeMask"><el-option label="租户" value="TENANT"/><el-option label="项目" value="PROJECT"/><el-option label="站点" value="SITE"/><el-option label="项目或站点" value="PROJECT,SITE"/><el-option label="全部范围" value="TENANT,PROJECT,SITE"/></el-select></el-form-item>
        <el-form-item label="状态"><el-select v-model="form.status"><el-option label="启用" value="ACTIVE"/><el-option label="停用" value="DISABLED"/></el-select></el-form-item>
        <el-form-item label="权限" required><el-checkbox-group v-model="form.permissions" class="permission-grid"><el-checkbox v-for="p in catalog" :key="p" :value="p">{{ p }}</el-checkbox></el-checkbox-group></el-form-item>
        <div class="form-actions"><el-button @click="editing = false">返回</el-button><el-button type="primary" :loading="saving" :disabled="!ready" @click="save">保存角色</el-button></div>
      </el-form>
    </div>
  </el-dialog>
</template>
<style scoped>
.permission-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:4px 12px; }
.form-actions { display:flex; justify-content:flex-end; gap:8px; }
</style>
