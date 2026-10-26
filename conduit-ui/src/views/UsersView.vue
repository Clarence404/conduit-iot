<script setup lang="ts">
/**
 * 用户管理（租户管理员与代入的平台管理员）：筛选栏（关键字、角色、状态）+ 服务端分页（useTable，默认每页 10 条）；
 * 角色、状态与「待改密码」用标签。操作列：编辑（显示名称与角色）、重置密码、禁用/启用、删除；自己那一行不显示禁用与删除
 * （代入的平台管理员不是本租户用户，没有「自己」那一行）。新建用户须设置初始密码，对方首次登录时修改。
 * 首次加载显示骨架屏，加载失败时可重试；「最后一个管理员」等保护规则由服务端返回 409 并提示。
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { userApi } from '@/api/users'
import { problemMessage } from '@/api/errors'
import type { AccountStatus, TenantRole, TenantUserView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { usePermission } from '@/composables/usePermission'
import { useTable } from '@/composables/useTable'
import { formatDateTime } from '@/utils/format'
import { PAGE_SIZES } from '@/utils/pagination'
import { roleLabel, statusLabel } from '@/utils/permissions'
import PageHeader from '@/components/PageHeader.vue'
import FilterBar from '@/components/FilterBar.vue'
import UserFormDialog from '@/components/UserFormDialog.vue'
import ResetPasswordDialog from '@/components/ResetPasswordDialog.vue'
import AuthorizationDialog from '@/components/AuthorizationDialog.vue'
import RoleManagementDialog from '@/components/RoleManagementDialog.vue'

const auth = useAuthStore()
const { isImpersonating, can } = usePermission()
const authorizationVisible = ref(false)
const rolesVisible = ref(false)
const authorizing = ref('')

const filters = reactive({ keyword: '', role: '' as '' | TenantRole, status: '' as '' | AccountStatus })
/** 已应用的筛选（点查询/回车时才从输入框复制），翻页与刷新都以它为准 */
const applied = ref({ ...filters })

const { items, total, page, size, loading, loaded, failed, load, search: searchTable, onSizeChange, onPageChange } =
  useTable<TenantUserView>((p, s) =>
    userApi.list({
      keyword: applied.value.keyword.trim() || undefined,
      role: applied.value.role || undefined,
      status: applied.value.status || undefined,
      page: p,
      size: s,
    }),
  )

const formVisible = ref(false)
const editing = ref<TenantUserView | null>(null)
const resetVisible = ref(false)
const resetting = ref<TenantUserView | null>(null)
const busy = ref(new Set<string>())

/** 应用筛选并回到第 1 页 */
function search() {
  applied.value = { ...filters }
  void searchTable()
}

/** 清空筛选并回到第 1 页 */
function reset() {
  filters.keyword = ''
  filters.role = ''
  filters.status = ''
  search()
}

/** 是否是当前登录用户自己（代入的平台管理员没有「自己」） */
function isSelf(row: TenantUserView): boolean {
  return !isImpersonating.value && row.username === auth.user?.username
}

/** 打开新建对话框 */
function openCreate() {
  editing.value = null
  formVisible.value = true
}

/** 打开编辑对话框 */
function openEdit(row: TenantUserView) {
  editing.value = row
  formVisible.value = true
}

/** 新建或编辑成功：提示并刷新当前页 */
function onSaved() {
  ElMessage.success('已保存')
  void load()
}

/** 打开重置密码对话框 */
function openReset(row: TenantUserView) {
  resetting.value = row
  resetVisible.value = true
}

/** 重置所选用户的密码（由 ResetPasswordDialog 调用；失败时异常交给对话框显示），成功后刷新以显示「待改密码」 */
async function savePassword(password: string) {
  if (!resetting.value) return
  await userApi.resetPassword(resetting.value.username, password)
  await load()
}

/** 禁用（先确认）或启用用户，成功后刷新当前页 */
async function toggleStatus(row: TenantUserView) {
  const disabling = row.status === 'ACTIVE'
  if (disabling) {
    try {
      await ElMessageBox.confirm(
        `禁用后，用户「${row.username}」将无法登录，已登录的会话最多 15 分钟后失效。确定禁用？`,
        '禁用用户',
        { type: 'warning' },
      )
    } catch {
      return
    }
  }
  busy.value.add(row.username)
  try {
    await (disabling ? userApi.disable(row.username) : userApi.enable(row.username))
    ElMessage.success(disabling ? '已禁用' : '已启用')
    await load()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  } finally {
    busy.value.delete(row.username)
  }
}

/** 删除用户（先确认），成功后刷新（删掉当前页最后一条时 useTable 自动退到上一页） */
async function remove(row: TenantUserView) {
  try {
    await ElMessageBox.confirm(`确定删除用户「${row.username}」？`, '删除用户', { type: 'warning' })
  } catch {
    return
  }
  try {
    await userApi.remove(row.username)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(problemMessage(e))
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div>
    <PageHeader title="用户" subtitle="维护租户账号与资源授权；项目和站点权限在「资源权限」中分配。">
      <template #actions>
        <el-button v-if="can('role.manage', true)" @click="rolesVisible = true">角色管理</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建用户</el-button>
      </template>
    </PageHeader>

    <div class="cd-panel">
      <FilterBar @search="search" @reset="reset">
        <el-form-item label="关键字">
          <el-input v-model="filters.keyword" placeholder="用户名或显示名称" clearable />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="filters.role" placeholder="全部" clearable class="filter-select">
            <el-option label="租户管理员" value="TENANT_ADMIN" />
            <el-option label="只读用户" value="TENANT_VIEWER" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="filters.status" placeholder="全部" clearable class="filter-select">
            <el-option label="启用" value="ACTIVE" />
            <el-option label="禁用" value="DISABLED" />
          </el-select>
        </el-form-item>
      </FilterBar>

      <el-skeleton v-if="!loaded" :rows="5" animated />
      <template v-else>
        <el-table v-loading="loading" :data="items">
          <el-table-column label="用户名" min-width="180">
            <template #default="{ row }">
              {{ row.username }}
              <div v-if="row.displayName" class="cd-muted">{{ row.displayName }}</div>
            </template>
          </el-table-column>
          <el-table-column label="兼容角色" width="120">
            <template #default="{ row }">
              <el-tag :type="row.role === 'TENANT_ADMIN' ? 'primary' : 'info'" size="small">{{ roleLabel(row.role) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="160">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">{{ statusLabel(row.status) }}</el-tag>
              <el-tag v-if="row.mustChangePassword" type="warning" size="small" class="flag">待改密码</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="180">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="350">
            <template #default="{ row }">
              <el-button v-if="can('role.manage', true)" link type="primary" @click="authorizing = row.username; authorizationVisible = true">资源权限</el-button>
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="primary" @click="openReset(row)">重置密码</el-button>
              <template v-if="!isSelf(row)">
                <el-button
                  link
                  :type="row.status === 'ACTIVE' ? 'warning' : 'success'"
                  :loading="busy.has(row.username)"
                  @click="toggleStatus(row)"
                >
                  {{ row.status === 'ACTIVE' ? '禁用' : '启用' }}
                </el-button>
                <el-button link type="danger" @click="remove(row)">删除</el-button>
              </template>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty v-if="failed" description="加载失败，点击重试">
              <el-button type="primary" @click="load">重试</el-button>
            </el-empty>
            <el-empty v-else-if="applied.keyword || applied.role || applied.status" description="没有符合条件的用户">
              <el-button @click="reset">重置筛选</el-button>
            </el-empty>
            <el-empty v-else description="还没有其他用户，新建一个只读用户试试">
              <el-button type="primary" @click="openCreate">新建用户</el-button>
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

    <UserFormDialog
      v-model="formVisible"
      :user="editing"
      :editing-self="editing !== null && isSelf(editing)"
      @saved="onSaved"
    />
    <ResetPasswordDialog v-model="resetVisible" :username="resetting?.username ?? ''" :save="savePassword" />
    <AuthorizationDialog v-model="authorizationVisible" :username="authorizing" />
    <RoleManagementDialog v-model="rolesVisible" />
  </div>
</template>

<style scoped>
.filter-select {
  width: 130px;
}
.flag {
  margin-left: var(--cd-space-1);
}
</style>
