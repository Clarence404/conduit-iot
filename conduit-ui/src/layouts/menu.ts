/**
 * 侧栏菜单定义：按身份给出分组与菜单项（路径、名称、图标）。
 * 平台管理员：概览、系统管理（租户、数据重放）；租户管理员（含代入的平台管理员）：概览、设备接入、系统管理（用户）；只读用户：概览、设备接入。
 */
import type { Component } from 'vue'
import { Box, Cpu, House, OfficeBuilding, RefreshRight, Share, User } from '@element-plus/icons-vue'
import type { Permissions } from '@/utils/permissions'

/** 菜单项；path 同时是 el-menu 的 index 与跳转地址 */
export interface MenuItem {
  path: string
  label: string
  icon: Component
}

/** 菜单分组 */
export interface MenuGroup {
  title: string
  items: MenuItem[]
}

const OVERVIEW: MenuGroup = { title: '概览', items: [{ path: '/home', label: '首页', icon: House }] }

const DEVICE_ACCESS: MenuGroup = {
  title: '设备接入',
  items: [
    { path: '/products', label: '产品', icon: Box },
    { path: '/devices', label: '设备', icon: Cpu },
  ],
}

/**
 * 按权限给出侧栏分组（按显示顺序）。菜单项 path 与路由 meta.menu 对应，用来在详情页高亮所属菜单。
 */
export function menuGroupsFor(p: Permissions): MenuGroup[] {
  if (p.isPlatformAdmin) {
    return [
      OVERVIEW,
      {
        title: '系统管理',
        items: [
          { path: '/tenants', label: '租户', icon: OfficeBuilding },
          { path: '/telemetry-replay', label: '数据重放', icon: RefreshRight },
        ],
      },
    ]
  }
  const groups = [OVERVIEW, { title: '组织管理', items: [{ path: '/organization', label: '组织', icon: Share }] }, DEVICE_ACCESS]
  if (p.canManageUsers) {
    groups.push({ title: '系统管理', items: [{ path: '/users', label: '用户', icon: User }] })
  }
  return groups
}
