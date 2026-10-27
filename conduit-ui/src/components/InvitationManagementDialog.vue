<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { invitationsApi, type Invitation } from '@/api/invitations'
import { problemMessage } from '@/api/errors'

const visible = defineModel<boolean>({ required: true })
const rows = ref<Invitation[]>([])
const email = ref('')
const username = ref('')
const role = ref<Invitation['role']>('TENANT_VIEWER')
const busy = ref(false)
const error = ref('')

watch(visible, (open) => { if (open) void load() })
async function load() {
  try { rows.value = await invitationsApi.list() }
  catch (e) { error.value = problemMessage(e) }
}
async function send() {
  busy.value = true
  error.value = ''
  try {
    await invitationsApi.create({ email: email.value, username: username.value, role: role.value })
    ElMessage.success('邀请邮件已发送')
    email.value = ''; username.value = ''
    await load()
  } catch (e) { error.value = problemMessage(e) }
  finally { busy.value = false }
}
async function revoke(id: string) {
  try { await invitationsApi.revoke(id); await load(); ElMessage.success('邀请已撤销') }
  catch (e) { error.value = problemMessage(e) }
}
function state(row: Invitation) {
  if (row.accepted_at) return '已接受'
  if (row.revoked_at) return '已撤销'
  if (new Date(row.expires_at) < new Date()) return '已过期'
  return '待接受'
}
</script>

<template>
  <el-dialog v-model="visible" title="邮件邀请" width="min(760px, 96vw)" append-to-body>
    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />
    <el-form inline @submit.prevent="send">
      <el-form-item label="邮箱"><el-input v-model="email" type="email" placeholder="收件邮箱" /></el-form-item>
      <el-form-item label="用户名"><el-input v-model="username" placeholder="登录用户名" /></el-form-item>
      <el-form-item label="角色">
        <el-select v-model="role" style="width: 140px">
          <el-option label="只读成员" value="TENANT_VIEWER" />
          <el-option label="租户管理员" value="TENANT_ADMIN" />
        </el-select>
      </el-form-item>
      <el-form-item><el-button type="primary" :loading="busy" @click="send">发送邀请</el-button></el-form-item>
    </el-form>
    <el-table :data="rows" max-height="360">
      <el-table-column prop="username" label="用户名" />
      <el-table-column prop="email" label="邮箱" />
      <el-table-column label="状态" width="90"><template #default="{ row }">{{ state(row) }}</template></el-table-column>
      <el-table-column label="操作" width="90">
        <template #default="{ row }"><el-button v-if="state(row) === '待接受'" link type="danger" @click="revoke(row.id)">撤销</el-button></template>
      </el-table-column>
    </el-table>
  </el-dialog>
</template>
