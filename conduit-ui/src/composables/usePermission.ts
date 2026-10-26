/** 当前用户的权限（响应式）：isPlatformAdmin、isImpersonating、canWrite、canManageUsers，规则见 utils/permissions。 */
import { computed, type ComputedRef } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { permissionsOf, hasPermission } from '@/utils/permissions'

/** usePermission 的返回值；解构后在模板中直接使用（computed 会自动解包） */
export interface PermissionRefs {
  isPlatformAdmin: ComputedRef<boolean>
  isImpersonating: ComputedRef<boolean>
  canWrite: ComputedRef<boolean>
  canManageUsers: ComputedRef<boolean>
  can: (permission: string, tenant?: boolean) => boolean
}

/** 读取当前会话的权限；随 auth store 的用户变化实时更新 */
export function usePermission(): PermissionRefs {
  const auth = useAuthStore()
  const perms = computed(() => permissionsOf(auth.user))
  return {
    can: (permission, tenant = false) => hasPermission(auth.user, permission, tenant),
    isPlatformAdmin: computed(() => perms.value.isPlatformAdmin),
    isImpersonating: computed(() => perms.value.isImpersonating),
    canWrite: computed(() => perms.value.canWrite),
    canManageUsers: computed(() => perms.value.canManageUsers),
  }
}
