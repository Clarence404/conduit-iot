/** 与服务端 REST 接口对应的数据类型（响应视图与请求体），字段含义以服务端 DTO 为准。 */

export interface PageView<T> {
  items: T[]
  total: number
  page: number
  size: number
}

/** 角色：平台管理员（不属于任何租户）、租户管理员、只读用户 */
export type Role = 'PLATFORM_ADMIN' | 'TENANT_ADMIN' | 'TENANT_VIEWER'

/** 租户内用户可以使用的角色 */
export type TenantRole = 'TENANT_ADMIN' | 'TENANT_VIEWER'

/** 用户与租户的状态：ACTIVE 启用；DISABLED 对用户是禁用、对租户是停用 */
export type AccountStatus = 'ACTIVE' | 'DISABLED'

/**
 * 当前用户（登录、刷新、进入/退出租户、修改密码的响应，以及 GET /auth/me）。
 * role 为有效角色（平台管理员代入租户时为 TENANT_ADMIN）；tenantCode/tenantName 对未代入的平台管理员为 null；
 * impersonatedBy 为代入的平台管理员用户名，未代入为 null。
 */
export interface UserView {
  username: string
  displayName: string | null
  role: Role
  tenantCode: string | null
  tenantName: string | null
  mustChangePassword: boolean
  impersonatedBy: string | null
}

export interface TokenResponse {
  accessToken: string
  expiresIn: number
  user: UserView
}

/** 产品；deviceCount 为设备数，onlineCount 为在线设备数（服务端 Redis 不可用时为 null，显示「—」） */
export interface ProductView {
  productKey: string
  name: string
  description: string | null
  codec: string
  propertyCount: number
  deviceCount: number
  onlineCount: number | null
  createdAt: string
}

export interface SaveProductRequest {
  name: string
  description: string | null
  /** 新建时缺省为 standard-json；修改时不传则保持不变 */
  codec?: string
}

export interface CodecView {
  name: string
  displayName: string
}

export type DataType = 'DOUBLE' | 'INT' | 'BOOL' | 'TEXT' | 'ENUM'

export interface EnumItem {
  value: number
  label: string
}

export interface PropertyRequest {
  identifier: string
  name: string
  dataType: DataType
  unit: string | null
  minValue: number | null
  maxValue: number | null
  decimals: number | null
  enumItems: EnumItem[] | null
  description: string | null
}

export interface PropertyView extends PropertyRequest {
  createdAt: string
  updatedAt: string
}

export interface ThingModelDocument {
  properties: PropertyRequest[]
}

export type ImportMode = 'merge' | 'replace'

export type DeviceStatus = 'ENABLED' | 'DISABLED'

export interface DeviceView {
  deviceKey: string
  name: string
  productKey: string
  status: DeviceStatus
  online: boolean
  lastReportAt: number | null
  createdAt: string
}

export interface DeviceCreated {
  device: DeviceView
  deviceSecret: string
}

export interface SecretView {
  deviceKey: string
  deviceSecret: string
}

export interface PointView {
  identifier: string
  /** 物模型中的显示名称；未定义时为 null */
  name: string | null
  value: number | string | boolean
  ts: number
  unit: string | null
  decimals: number | null
  /** 枚举值对应的名称；非枚举或未定义时为 null */
  enumLabel: string | null
}

export interface LatestView {
  deviceKey: string
  values: PointView[]
}

/** 历史曲线模式：RAW 原始点；AGG 每桶平均/最小/最大；LAST 每桶最后一个值 */
export type HistoryMode = 'RAW' | 'AGG' | 'LAST'

/** 一个点：RAW/LAST 为 [ts, value]，AGG 为 [ts, avg, min, max]；ts 为毫秒（聚合时为桶起点） */
export type HistoryPoint = (number | boolean | null)[]

/** 一个测点的历史曲线；name/unit/decimals/enumItems 在物模型未定义时为 null */
export interface SeriesView {
  identifier: string
  name: string | null
  /** 物模型类型；无物模型时按列类型；列也不存在时为 null */
  dataType: DataType | null
  unit: string | null
  decimals: number | null
  enumItems: EnumItem[] | null
  mode: HistoryMode
  points: HistoryPoint[]
}

/** 历史查询结果；所有曲线同一模式，series 顺序与请求一致 */
export interface HistoryView {
  deviceKey: string
  from: number
  to: number
  /** 聚合桶宽（毫秒），原始模式为 null */
  bucketMs: number | null
  series: SeriesView[]
}

/** 死信统计：条数与按原因（服务端 DeadReason 名称）分组的条数 */
export interface DeadSummary {
  total: number
  byReason: Record<string, number>
}

/** 最近上报的设备；lastReportAt 为毫秒 */
export interface RecentDevice {
  deviceKey: string
  name: string
  productKey: string | null
  productName: string | null
  lastReportAt: number
}

/** 单个产品的设备统计；onlineCount 在服务端 Redis 不可用时为 null */
export interface ProductStat {
  productKey: string
  name: string
  deviceCount: number
  onlineCount: number | null
}

/** 首页总览（按当前租户）；onlineCount、deadLastHour 在服务端 Redis 不可用时为 null，recentDevices 此时为空 */
export interface OverviewView {
  productCount: number
  propertyCount: number
  deviceCount: number
  enabledCount: number
  disabledCount: number
  onlineCount: number | null
  deadLastHour: DeadSummary | null
  recentDevices: RecentDevice[]
  products: ProductStat[]
}

/** 平台概况里「设备最多的租户」的一项；onlineCount 在服务端 Redis 不可用时为 null */
export interface TenantStat {
  code: string
  name: string
  deviceCount: number
  onlineCount: number | null
}

/** 平台概况（GET /platform/overview）；userCount 为租户用户数（不含平台管理员），topTenants 最多 5 个 */
export interface PlatformOverview {
  tenantCount: number
  activeTenantCount: number
  disabledTenantCount: number
  userCount: number
  productCount: number
  deviceCount: number
  onlineCount: number | null
  topTenants: TenantStat[]
}

/** 租户（平台租户列表项与操作结果）；adminCount 含已禁用的租户管理员 */
export interface TenantView {
  code: string
  name: string
  status: AccountStatus
  adminCount: number
  userCount: number
  productCount: number
  deviceCount: number
  onlineCount: number | null
  createdAt: string
}

/** 租户列表查询：分页 + 可选的关键字（名称或编码）与状态 */
export interface TenantQuery {
  keyword?: string
  status?: AccountStatus
  page: number
  size: number
}

/** 新租户的首个管理员（首次登录须修改密码） */
export interface TenantAdminAccount {
  username: string
  displayName: string | null
  password: string
}

/** 新建租户：编码（创建后不可改）、名称与首个管理员 */
export interface CreateTenantRequest {
  code: string
  name: string
  admin: TenantAdminAccount
}

/** 修改租户名称 */
export interface UpdateTenantRequest {
  name: string
}

/** 租户用户（用户管理列表项，以及平台查看某租户的用户） */
export interface TenantUserView {
  username: string
  displayName: string | null
  role: TenantRole
  status: AccountStatus
  mustChangePassword: boolean
  createdAt: string
}

/** 用户列表查询：分页 + 可选的关键字（用户名或显示名称）、角色与状态 */
export interface UserQuery {
  keyword?: string
  role?: TenantRole
  status?: AccountStatus
  page: number
  size: number
}

/** 新建租户用户；password 为初始密码（首次登录须修改） */
export interface CreateUserRequest {
  username: string
  displayName: string | null
  role: TenantRole
  password: string
}

/** 修改租户用户的显示名称与角色 */
export interface UpdateUserRequest {
  displayName: string | null
  role: TenantRole
}

/** 修改自己的显示名称（null 或空白即清除） */
export interface UpdateMeRequest {
  displayName: string | null
}

/** 修改自己的密码 */
export interface ChangePasswordRequest {
  oldPassword: string
  newPassword: string
}

/** 遥测重放任务状态；NONE 表示服务端本次启动以来从未提交过任务 */
export type ReplayState = 'NONE' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'

/**
 * 遥测重放任务状态。除 state 外的字段在从未提交过任务时不返回，因此都是可选的；
 * finishedAt 与 error 只在任务结束（成功、失败、取消）后才有，运行中不返回。
 * scanned 为读出的条目数，written 为成功写入时序库的条数，skipped 为跳过的条数，rejected 为物模型全部测点被拒的条数。
 */
export interface ReplayStatus {
  state: ReplayState
  from?: number
  to?: number
  startedAt?: number
  finishedAt?: number
  scanned?: number
  written?: number
  skipped?: number
  rejected?: number
  error?: string
}

/** 提交重放：毫秒时间范围，左闭右开，跨度不超过 6 小时 */
export interface ReplayRequest {
  from: number
  to: number
}

export type OrganizationStatus = 'ACTIVE' | 'DISABLED'
export interface CustomerView { code: string; name: string; status: OrganizationStatus; createdAt: string }
export interface ProjectView { code: string; name: string; customerCode: string | null; status: OrganizationStatus; systemDefault: boolean; createdAt: string }
export interface SiteView { code: string; name: string; projectCode: string; timezone: string; status: OrganizationStatus; systemDefault: boolean; createdAt: string }
export interface AssetView { code: string; name: string; siteCode: string; parentCode: string | null; type: string | null; status: OrganizationStatus; createdAt: string }
export interface DeviceAssignmentView { deviceKey: string; siteCode: string; assetCode: string | null }
