<script setup lang="ts">
/**
 * 个人设置对话框（用户菜单「个人设置」）：「基本信息」显示用户名、角色与租户，可修改显示名称（留空即显示用户名）；
 * 「修改密码」输入原密码与两次新密码（规则同强制改密码）；平台管理员代入租户期间不能修改密码，提示先退出租户。
 * 保存成功后更新会话、提示并关闭；错误显示在对话框内。
 */
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { usePermission } from '@/composables/usePermission'
import { problemMessage } from '@/api/errors'
import { passwordProblem } from '@/utils/accountRules'
import { roleLabel } from '@/utils/permissions'
import { sessionsApi, type UserSession } from '@/api/sessions'

const visible = defineModel<boolean>({ required: true })
const auth = useAuthStore()
const { isImpersonating } = usePermission()

const tab = ref<'profile' | 'password' | 'sessions'>('profile')
const userSessions = ref<UserSession[]>([])
const sessionsLoading = ref(false)
const displayName = ref('')
const pw = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const error = ref('')
const saving = ref(false)

// 每次打开都从当前会话重新填充，并回到「基本信息」；关闭时清掉密码与错误提示，避免残留
watch(
  visible,
  (open) => {
    if (!open) {
      pw.oldPassword = ''
      pw.newPassword = ''
      pw.confirm = ''
      error.value = ''
      return
    }
    tab.value = 'profile'
    displayName.value = auth.user?.displayName ?? ''
    pw.oldPassword = ''
    pw.newPassword = ''
    pw.confirm = ''
    error.value = ''
  },
  { immediate: true },
)

watch(tab, () => {
  error.value = ''
  if (tab.value === 'sessions') void loadSessions()
})

async function loadSessions() {
  sessionsLoading.value = true
  try {
    userSessions.value = await sessionsApi.list()
  } catch (e) {
    error.value = problemMessage(e)
  } finally {
    sessionsLoading.value = false
  }
}

async function revokeSession(session: UserSession) {
  try {
    await sessionsApi.revoke(session.id)
    if (session.current) {
      auth.clear()
      visible.value = false
      window.location.assign('/login')
      return
    }
    await loadSessions()
    ElMessage.success('会话已退出')
  } catch (e) {
    error.value = problemMessage(e)
  }
}

async function revokeAllSessions() {
  try {
    await sessionsApi.revokeAll()
    auth.clear()
    visible.value = false
    window.location.assign('/login')
  } catch (e) {
    error.value = problemMessage(e)
  }
}

/** 保存显示名称（去首尾空白，空白即清除），成功后关闭 */
async function saveProfile() {
  if (saving.value) return
  const name = displayName.value.trim()
  if (name.length > 64) {
    error.value = '显示名称最多 64 个字符'
    return
  }
  saving.value = true
  error.value = ''
  try {
    await auth.updateProfile(name || null)
    ElMessage.success('已保存')
    visible.value = false
  } catch (e) {
    error.value = problemMessage(e)
  } finally {
    saving.value = false
  }
}

/** 修改密码：原密码必填、新密码合规、两次一致；成功后换用新令牌并关闭 */
async function savePassword() {
  if (saving.value) return
  error.value = ''
  if (!pw.oldPassword) {
    error.value = '请输入原密码'
    return
  }
  const problem = passwordProblem(pw.newPassword)
  if (problem) {
    error.value = problem
    return
  }
  if (pw.newPassword !== pw.confirm) {
    error.value = '两次输入的新密码不一致'
    return
  }
  saving.value = true
  try {
    await auth.changePassword(pw.oldPassword, pw.newPassword)
    ElMessage.success('密码已修改')
    visible.value = false
  } catch (e) {
    error.value = problemMessage(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="个人设置" width="min(480px, 92vw)" :close-on-click-modal="false" append-to-body>
    <el-alert v-if="error" class="error" type="error" :title="error" show-icon :closable="false" />
    <el-tabs v-model="tab">
      <el-tab-pane label="基本信息" name="profile">
        <el-form label-width="80px" @submit.prevent="saveProfile">
          <el-form-item label="用户名">{{ auth.user?.username }}</el-form-item>
          <el-form-item label="角色">{{ auth.user ? roleLabel(auth.user.role) : '' }}</el-form-item>
          <el-form-item v-if="auth.user?.tenantName" label="租户">{{ auth.user.tenantName }}</el-form-item>
          <el-form-item label="显示名称">
            <el-input v-model="displayName" maxlength="64" placeholder="不填则显示用户名" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="saveProfile">保存</el-button>
          </el-form-item>
        </el-form>
      </el-tab-pane>
      <el-tab-pane label="修改密码" name="password">
        <el-alert
          v-if="isImpersonating"
          type="info"
          show-icon
          :closable="false"
          title="代入租户期间不能修改密码，请先退出租户后修改自己的密码。"
        />
        <el-form v-else label-width="96px" @submit.prevent="savePassword">
          <el-form-item label="原密码">
            <el-input v-model="pw.oldPassword" type="password" show-password autocomplete="current-password" />
          </el-form-item>
          <el-form-item label="新密码">
            <el-input v-model="pw.newPassword" type="password" show-password autocomplete="new-password" placeholder="8～64 位，同时包含字母和数字" />
          </el-form-item>
          <el-form-item label="确认新密码">
            <el-input v-model="pw.confirm" type="password" show-password autocomplete="new-password" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="savePassword">修改密码</el-button>
          </el-form-item>
        </el-form>
      </el-tab-pane>
      <el-tab-pane label="登录会话" name="sessions">
        <div class="session-actions">
          <el-button :loading="sessionsLoading" @click="loadSessions">刷新</el-button>
          <el-button type="danger" plain @click="revokeAllSessions">退出全部设备</el-button>
        </div>
        <el-empty v-if="!sessionsLoading && !userSessions.length" description="暂无会话" />
        <div v-for="session in userSessions" :key="session.id" class="session-row">
          <div>
            <strong>{{ session.current ? '当前会话' : '其他会话' }}</strong>
            <div>登录：{{ new Date(session.createdAt).toLocaleString() }}</div>
            <small>{{ session.revokedAt ? '已退出' : new Date(session.expiresAt) < new Date() ? '已过期' : '有效' }}</small>
          </div>
          <el-button v-if="!session.revokedAt" size="small" @click="revokeSession(session)">退出</el-button>
        </div>
      </el-tab-pane>
    </el-tabs>
  </el-dialog>
</template>

<style scoped>
.error {
  margin-bottom: var(--cd-space-3);
}
.session-actions { display: flex; justify-content: space-between; margin-bottom: 12px; }
.session-row { display: flex; justify-content: space-between; align-items: center; padding: 12px 0; border-bottom: 1px solid var(--el-border-color-light); }
</style>
