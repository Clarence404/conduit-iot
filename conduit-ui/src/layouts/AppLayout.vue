<script setup lang="ts">
/**
 * 登录后的整体布局：外壳恰好一屏高（100vh），侧栏固定在左侧、「收起菜单」钉在其底部，顶栏固定，只有右侧内容区（el-main）滚动。
 * 左侧侧栏：Logo、按身份给出的分组菜单（带图标，见 layouts/menu.ts），按路由 meta.menu 高亮；
 * 可折叠（偏好保存，窗口宽度 < 820px 时强制折叠），浅色/深色两种风格（偏好）。
 * 顶栏：左侧全局面包屑（路由 meta + 详情页对象名称），右侧外观设置、租户名称、用户头像下拉（显示名称、角色、租户、个人设置、退出登录）。
 * 顶栏下方：平台管理员代入租户时的提示条。页面渲染在 router-view；需要先改密码时弹出不可关闭的强制改密码对话框。
 * 会话身份变化（如代入的租户被停用、续期后退回平台管理员）时，按 router/access 的规则离开不匹配的页面。
 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown, Brush, Expand, Fold } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { useCurrentPage } from '@/composables/useBreadcrumb'
import { useMediaQuery } from '@/composables/useMediaQuery'
import { displayNameOf, permissionsOf, roleLabel } from '@/utils/permissions'
import { accessRedirect } from '@/router/access'
import ConduitLogo from '@/components/ConduitLogo.vue'
import AppearancePanel from '@/components/AppearancePanel.vue'
import ImpersonationBar from '@/components/ImpersonationBar.vue'
import ForcePasswordDialog from '@/components/ForcePasswordDialog.vue'
import PersonalSettingsDialog from '@/components/PersonalSettingsDialog.vue'
import { menuGroupsFor } from './menu'

const auth = useAuthStore()
const ui = useUiStore()
const route = useRoute()
const router = useRouter()
const { breadcrumb } = useCurrentPage()
const narrow = useMediaQuery('(max-width: 819px)')
/** 个人设置对话框是否打开 */
const profileVisible = ref(false)

/** 是否折叠：窄屏强制折叠，否则按偏好 */
const collapsed = computed(() => narrow.value || ui.sidebarCollapsed)
/** 高亮的菜单：列表页与详情页通过 meta.menu 归到同一项 */
const activeMenu = computed(() => route.meta.menu ?? '')
/** 当前身份可见的菜单分组 */
const menuGroups = computed(() => menuGroupsFor(permissionsOf(auth.user)))
/** 用户菜单与头像显示的名称：显示名称，未设置时用用户名 */
const userName = computed(() => displayNameOf(auth.user))
/** 当前角色的中文名称 */
const roleText = computed(() => {
  const user = auth.user
  if (!user) return ''
  if (!user.authorizationEnforced) return roleLabel(user.role)
  if (user.tenantPermissions?.includes('tenant.manage')) return '租户所有者'
  if (user.tenantPermissions?.includes('role.manage')) return '租户管理员'
  return user.permissions?.some((p) => p.endsWith('.manage')) ? '范围授权成员' : '只读成员'
})
/** 头像文字：名称首字（大写） */
const avatarText = computed(() => (userName.value || '?').slice(0, 1).toUpperCase())

// 会话身份变化后，当前页面可能已不属于新身份（例如续期时代入被取消）
watch(
  () => auth.user,
  (user) => {
    if (!user) return
    const target = accessRedirect(route, user)
    if (target) {
      void router.replace(target)
    }
  },
)

/** 退出登录：无论登出请求是否成功，都回到登录页。 */
async function logout() {
  try {
    await auth.logout()
  } catch {
    // 登出请求失败也要回到登录页
  } finally {
    await router.replace({ name: 'login' })
  }
}

/** 用户下拉菜单的命令：profile 打开个人设置，logout 退出登录 */
function onCommand(command: string) {
  if (command === 'profile') {
    profileVisible.value = true
  } else if (command === 'logout') {
    void logout()
  }
}
</script>

<template>
  <el-container class="layout">
    <el-aside :width="collapsed ? 'var(--cd-sidebar-collapsed-width)' : 'var(--cd-sidebar-width)'" :class="['sidebar', `cd-sidebar--${ui.sidebarStyle}`]">
      <div class="brand">
        <ConduitLogo :size="28" :tone="ui.sidebarStyle === 'dark' ? 'dark-bg' : 'auto'" />
        <span v-if="!collapsed" class="brand-name">Conduit</span>
      </div>
      <el-menu :default-active="activeMenu" :collapse="collapsed" :collapse-transition="false" router class="menu">
        <el-menu-item-group v-for="group in menuGroups" :key="group.title" :title="group.title">
          <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
            <el-icon><component :is="item.icon" /></el-icon>
            <template #title>
              <span>{{ item.label }}</span>
            </template>
          </el-menu-item>
        </el-menu-item-group>
      </el-menu>
      <div
        v-if="!narrow"
        class="collapse-toggle"
        role="button"
        tabindex="0"
        :title="collapsed ? '展开菜单' : '收起菜单'"
        :aria-label="collapsed ? '展开菜单' : '收起菜单'"
        @click="ui.toggleSidebar()"
        @keydown.enter="ui.toggleSidebar()"
        @keydown.space.prevent="ui.toggleSidebar()"
      >
        <el-icon><Expand v-if="collapsed" /><Fold v-else /></el-icon>
        <span v-if="!collapsed">收起菜单</span>
      </div>
    </el-aside>

    <el-container class="main">
      <el-header class="header">
        <el-breadcrumb separator="/" class="breadcrumb">
          <el-breadcrumb-item v-for="(item, i) in breadcrumb" :key="i" :to="item.to">{{ item.label }}</el-breadcrumb-item>
        </el-breadcrumb>
        <div class="header-right">
          <el-popover trigger="click" placement="bottom-end" :width="300">
            <template #reference>
              <el-button circle :icon="Brush" title="外观设置" aria-label="外观设置" />
            </template>
            <AppearancePanel />
          </el-popover>
          <el-tag v-if="auth.user?.tenantName" type="info" class="tenant">{{ auth.user.tenantName }}</el-tag>
          <el-dropdown trigger="click" @command="onCommand">
            <span class="user" role="button" tabindex="0" aria-label="用户菜单">
              <el-avatar :size="28">{{ avatarText }}</el-avatar>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>{{ userName }}</el-dropdown-item>
                <el-dropdown-item disabled>角色：{{ roleText }}</el-dropdown-item>
                <el-dropdown-item v-if="auth.user?.tenantName" disabled>租户：{{ auth.user.tenantName }}</el-dropdown-item>
                <el-dropdown-item divided command="profile">个人设置</el-dropdown-item>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <ImpersonationBar />
      <el-main class="content">
        <router-view />
      </el-main>
    </el-container>
    <ForcePasswordDialog />
    <PersonalSettingsDialog v-model="profileVisible" />
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
  height: 100dvh;
  overflow: hidden;
}
.sidebar {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--cd-sidebar-bg);
  border-right: 1px solid var(--cd-sidebar-border);
  transition: width 0.2s;
}
.cd-sidebar--dark {
  --cd-sidebar-bg: var(--cd-sidebar-dark-bg);
  --cd-sidebar-text: var(--cd-sidebar-dark-text);
  --cd-sidebar-muted: var(--cd-sidebar-dark-muted);
  --cd-sidebar-hover: var(--cd-sidebar-dark-hover);
  --cd-sidebar-active-bg: var(--cd-sidebar-dark-active-bg);
  --cd-sidebar-active-text: var(--cd-sidebar-dark-active-text);
  --cd-sidebar-border: var(--cd-sidebar-dark-border);
}
.cd-sidebar--light {
  --cd-sidebar-bg: var(--cd-sidebar-light-bg);
  --cd-sidebar-text: var(--cd-sidebar-light-text);
  --cd-sidebar-muted: var(--cd-sidebar-light-muted);
  --cd-sidebar-hover: var(--cd-sidebar-light-hover);
  --cd-sidebar-active-bg: var(--cd-sidebar-light-active-bg);
  --cd-sidebar-active-text: var(--cd-sidebar-light-active-text);
  --cd-sidebar-border: var(--cd-sidebar-light-border);
}
.brand {
  height: var(--cd-header-height);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: var(--cd-space-2);
  padding: 0 var(--cd-space-5);
  color: var(--cd-sidebar-text);
  font-size: 18px;
  font-weight: 600;
}
.menu {
  flex: 1;
  min-height: 0;
  border-right: none;
  overflow-y: auto;
  --el-menu-bg-color: var(--cd-sidebar-bg);
  --el-menu-text-color: var(--cd-sidebar-text);
  --el-menu-hover-bg-color: var(--cd-sidebar-hover);
  --el-menu-active-color: var(--cd-sidebar-active-text);
  --el-menu-item-height: 44px;
}
.menu :deep(.el-menu-item.is-active) {
  background: var(--cd-sidebar-active-bg);
  color: var(--cd-sidebar-active-text);
}
.menu :deep(.el-menu-item-group__title) {
  padding-top: var(--cd-space-3);
  color: var(--cd-sidebar-muted);
  font-size: 12px;
}
.menu.el-menu--collapse :deep(.el-menu-item-group__title) {
  display: none;
}
.collapse-toggle {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: var(--cd-space-2);
  padding: var(--cd-space-3) var(--cd-space-5);
  color: var(--cd-sidebar-muted);
  border-top: 1px solid var(--cd-sidebar-border);
  cursor: pointer;
}
.collapse-toggle:hover {
  color: var(--cd-sidebar-text);
  background: var(--cd-sidebar-hover);
}
.main {
  min-width: 0;
  min-height: 0;
}
.header {
  height: var(--cd-header-height);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--cd-space-3);
  background: var(--cd-panel-bg);
  border-bottom: 1px solid var(--el-border-color-light);
}
.breadcrumb {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
}
.header-right {
  display: flex;
  align-items: center;
  gap: var(--cd-space-3);
  flex-shrink: 0;
}
.user {
  display: flex;
  align-items: center;
  gap: var(--cd-space-1);
  color: var(--el-text-color-regular);
  cursor: pointer;
}
.content {
  min-width: 0;
  min-height: 0;
  padding: var(--cd-space-4);
  overflow: auto;
  background: var(--cd-page-bg);
}
@media (max-width: 819px) {
  .tenant {
    display: none;
  }
  .content {
    padding: var(--cd-space-3);
  }
}
</style>
