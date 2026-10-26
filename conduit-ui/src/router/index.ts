/**
 * 路由表与守卫。除 meta.public 的登录页外，所有页面都挂在 AppLayout 下并要求已登录；
 * 每个路由带 meta（页面名称、面包屑、菜单归属、访问范围，见 router/meta.ts）；身份与页面不匹配时按 router/access.ts 改去合适的页面；
 * 未匹配的地址在布局内显示 404 页。
 */

import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { ROUTE_META } from './meta'
import { accessRedirect } from './access'

/** 路由表；导出供测试使用。路由名称与 meta 与 test/viewTestUtils.ts 的内存路由保持一致。 */
export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: ROUTE_META.login,
  },
  {
    path: '/',
    component: () => import('@/layouts/AppLayout.vue'),
    redirect: '/home',
    children: [
      {
        path: 'home',
        name: 'home',
        component: () => import('@/views/HomeEntry.vue'),
        meta: ROUTE_META.home,
      },
      {
        path: 'products',
        name: 'products',
        component: () => import('@/views/ProductsView.vue'),
        meta: ROUTE_META.products,
      },
      {
        path: 'products/:productKey',
        name: 'product-detail',
        component: () => import('@/views/ProductDetailView.vue'),
        props: true,
        meta: ROUTE_META['product-detail'],
      },
      {
        path: 'organization',
        name: 'organization',
        component: () => import('@/views/OrganizationView.vue'),
        meta: ROUTE_META.organization,
      },
      {
        path: 'devices',
        name: 'devices',
        component: () => import('@/views/DevicesView.vue'),
        meta: ROUTE_META.devices,
      },
      {
        path: 'devices/:deviceKey',
        name: 'device-detail',
        component: () => import('@/views/DeviceDetailView.vue'),
        props: true,
        meta: ROUTE_META['device-detail'],
      },
      {
        path: 'tenants',
        name: 'tenants',
        component: () => import('@/views/TenantsView.vue'),
        meta: ROUTE_META.tenants,
      },
      {
        path: 'telemetry-replay',
        name: 'telemetry-replay',
        component: () => import('@/views/TelemetryReplayView.vue'),
        meta: ROUTE_META['telemetry-replay'],
      },
      {
        path: 'users',
        name: 'users',
        component: () => import('@/views/UsersView.vue'),
        meta: ROUTE_META.users,
      },
      {
        path: ':pathMatch(.*)*',
        name: 'not-found',
        component: () => import('@/views/NotFoundView.vue'),
        meta: ROUTE_META['not-found'],
      },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

/**
 * 登录与身份守卫：公开页直接放行；未登录时先尝试用 refresh Cookie 恢复会话（页面刷新场景），
 * 恢复失败再跳转登录页并带上 redirect；已登录时把身份与页面不匹配的访问改去合适的页面（见 router/access.ts）。
 */
router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }
  const auth = useAuthStore()
  if (!auth.isAuthenticated && !(await auth.restore())) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return accessRedirect(to, auth.user) ?? true
})

export default router
