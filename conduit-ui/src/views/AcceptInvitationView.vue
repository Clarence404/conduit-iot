<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { invitationsApi } from '@/api/invitations'
import { problemMessage } from '@/api/errors'
import { passwordProblem } from '@/utils/accountRules'

const route = useRoute()
const router = useRouter()
const token = typeof route.query.token === 'string' ? route.query.token : ''
const invitation = ref<{ username: string; email: string; tenant_name: string } | null>(null)
const password = ref('')
const confirm = ref('')
const error = ref('')
const busy = ref(false)

onMounted(async () => {
  try { invitation.value = await invitationsApi.preview(token) }
  catch (e) { error.value = problemMessage(e) }
})

async function accept() {
  error.value = passwordProblem(password.value) ?? ''
  if (!error.value && password.value !== confirm.value) error.value = '两次密码不一致'
  if (error.value) return
  busy.value = true
  try {
    await invitationsApi.accept(token, password.value)
    ElMessage.success('账号已启用，请登录')
    await router.replace('/login')
  } catch (e) { error.value = problemMessage(e) }
  finally { busy.value = false }
}
</script>

<template>
  <main class="invitation-page">
    <el-card class="invitation-card">
      <h1>接受 Conduit IoT 邀请</h1>
      <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />
      <template v-if="invitation">
        <p>租户：{{ invitation.tenant_name }}</p>
        <p>账号：{{ invitation.username }}（{{ invitation.email }}）</p>
        <el-form @submit.prevent="accept">
          <el-form-item label="设置密码"><el-input v-model="password" type="password" show-password autocomplete="new-password" /></el-form-item>
          <el-form-item label="确认密码"><el-input v-model="confirm" type="password" show-password autocomplete="new-password" /></el-form-item>
          <el-button type="primary" :loading="busy" @click="accept">接受邀请</el-button>
        </el-form>
      </template>
    </el-card>
  </main>
</template>

<style scoped>
.invitation-page { min-height: 100vh; display: grid; place-items: center; padding: 24px; }
.invitation-card { width: min(480px, 100%); }
</style>
