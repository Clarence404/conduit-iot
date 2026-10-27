/**
 * 路由元信息：页面名称（浏览器标题）、面包屑、侧栏菜单归属，以及访问范围（租户页面 / 平台页面）与额外要求（管理用户）。
 * 真实路由表（router/index.ts）与测试用内存路由（test/viewTestUtils.ts）共用 ROUTE_META，保证两边一致。
 */
import type { RouteLocationRaw, RouteMeta } from 'vue-router'

/** 面包屑的一级；有 to 时可点击跳转 */
export interface BreadcrumbItem {
  label: string
  to?: RouteLocationRaw
}

declare module 'vue-router' {
  interface RouteMeta {
    /** 不需要登录即可访问（登录页） */
    public?: boolean
    /** 页面名称，用于浏览器标签标题「<页面名> · Conduit」 */
    title?: string
    /** 面包屑（详情页的对象名称不在这里，由 useBreadcrumb 追加为最后一级） */
    breadcrumb?: BreadcrumbItem[]
    /** 侧栏中高亮的菜单路径：列表页与详情页归同一个菜单 */
    menu?: string
    /**
     * 访问范围：tenant 为租户页面（未代入的平台管理员会被带到租户管理）；platform 为平台页面（其他身份会被带到首页）；
     * 不设表示所有登录用户（首页、404）。规则见 router/access.ts。
     */
    scope?: 'tenant' | 'platform'
    /** 额外要求：manageUsers 只有租户管理员（含代入的平台管理员）可以进入 */
    requires?: 'manageUsers'
  }
}

/** 各路由的元信息，键为路由名称 */
export const ROUTE_META = {
  home: { title: '首页', menu: '/home', breadcrumb: [{ label: '概览' }, { label: '首页' }] },
  login: { public: true, title: '登录' },
  'accept-invitation': { public: true, title: '接受邀请' },
  products: {
    title: '产品',
    menu: '/products',
    scope: 'tenant',
    breadcrumb: [{ label: '设备接入' }, { label: '产品' }],
  },
  'product-detail': {
    title: '产品详情',
    menu: '/products',
    scope: 'tenant',
    breadcrumb: [{ label: '设备接入' }, { label: '产品', to: { name: 'products' } }],
  },
  devices: {
    title: '设备',
    menu: '/devices',
    scope: 'tenant',
    breadcrumb: [{ label: '设备接入' }, { label: '设备' }],
  },
  organization: {
    title: '组织',
    menu: '/organization',
    scope: 'tenant',
    breadcrumb: [{ label: '组织管理' }, { label: '组织' }],
  },
  'device-detail': {
    title: '设备详情',
    menu: '/devices',
    scope: 'tenant',
    breadcrumb: [{ label: '设备接入' }, { label: '设备', to: { name: 'devices' } }],
  },
  tenants: {
    title: '租户',
    menu: '/tenants',
    scope: 'platform',
    breadcrumb: [{ label: '系统管理' }, { label: '租户' }],
  },
  'telemetry-replay': {
    title: '数据重放',
    menu: '/telemetry-replay',
    scope: 'platform',
    breadcrumb: [{ label: '系统管理' }, { label: '数据重放' }],
  },
  users: {
    title: '用户',
    menu: '/users',
    scope: 'tenant',
    requires: 'manageUsers',
    breadcrumb: [{ label: '系统管理' }, { label: '用户' }],
  },
  'not-found': { title: '页面不存在', breadcrumb: [{ label: '页面不存在' }] },
} satisfies Record<string, RouteMeta>
