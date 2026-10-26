/**
 * 页面组件测试的公共工具：与真实路由同名、同 meta 的内存路由（含租户管理与用户管理），带登录用户的 Pinia，
 * 以及等待异步渲染完成。
 */

import { flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent } from 'vue'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import { ROUTE_META } from '@/router/meta'
import { useAuthStore } from '@/stores/auth'
import type { UserView } from '@/api/types'

const Empty = defineComponent({ template: '<div />' })

/** 与真实路由同名、同 meta 的内存路由，组件全部用空壳，只用来断言跳转、query、面包屑与菜单高亮 */
export function createTestRouter(): Router {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', component: Empty, meta: ROUTE_META.login },
      { path: '/home', name: 'home', component: Empty, meta: ROUTE_META.home },
      { path: '/products', name: 'products', component: Empty, meta: ROUTE_META.products },
      {
        path: '/products/:productKey',
        name: 'product-detail',
        component: Empty,
        props: true,
        meta: ROUTE_META['product-detail'],
      },
      { path: '/devices', name: 'devices', component: Empty, meta: ROUTE_META.devices },
      { path: '/organization', name: 'organization', component: Empty, meta: ROUTE_META.organization },
      {
        path: '/devices/:deviceKey',
        name: 'device-detail',
        component: Empty,
        props: true,
        meta: ROUTE_META['device-detail'],
      },
      { path: '/tenants', name: 'tenants', component: Empty, meta: ROUTE_META.tenants },
      {
        path: '/telemetry-replay',
        name: 'telemetry-replay',
        component: Empty,
        meta: ROUTE_META['telemetry-replay'],
      },
      { path: '/users', name: 'users', component: Empty, meta: ROUTE_META.users },
      { path: '/:pathMatch(.*)*', name: 'not-found', component: Empty, meta: ROUTE_META['not-found'] },
    ],
  })
}

/**
 * 创建并激活一个已登录的 Pinia：默认是默认租户的租户管理员 admin，可用 user 覆盖任意字段（如角色、代入者、需改密码）。
 */
export function createTestPinia(user: Partial<UserView> = {}): Pinia {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.token = 'token'
  auth.user = {
    username: 'admin',
    displayName: null,
    role: 'TENANT_ADMIN',
    tenantCode: 'default',
    tenantName: '默认租户',
    mustChangePassword: false,
    impersonatedBy: null,
    ...user,
  }
  return pinia
}

/** 等待挂载后的异步加载和渲染都完成 */
export async function settle() {
  await flushPromises()
  await flushPromises()
}
