export type AdminMenuItem = {
  to: string
  label: string
  icon: string
  pageKey?: string
  authority?: string
}

export type AdminMenuGroup = {
  label: string
  icon: string
  items: AdminMenuItem[]
}

export const adminMenuGroups: AdminMenuGroup[] = [
  {
    label: '首页',
    icon: 'home',
    items: [
      { to: '/dashboard', label: '仪表盘', icon: 'gauge' }
    ]
  },
  {
    label: '权限',
    icon: 'shield',
    items: [
      { to: '/system/users', label: '用户管理', icon: 'user', pageKey: 'system-users' },
      { to: '/system/roles', label: '角色管理', icon: 'badge', pageKey: 'system-roles' },
      { to: '/system/departments', label: '部门管理', icon: 'building', pageKey: 'system-departments' },
      { to: '/system/menus', label: '菜单管理', icon: 'menu', pageKey: 'system-menus' },
      { to: '/system/posts', label: '岗位管理', icon: 'briefcase', pageKey: 'system-posts' }
    ]
  },
  {
    label: '产品管理',
    icon: 'list',
    items: [
      { to: '/products/list', label: '产品列表', icon: 'list', pageKey: 'products' },
      { to: '/products/categories', label: '产品分类', icon: 'box', pageKey: 'product-categories' },
      { to: '/products/price-schedules', label: '涨跌设置', icon: 'chart', pageKey: 'price-schedules' },
      { to: '/products/data-providers', label: '行情数据源', icon: 'server' },
      { to: '/products/provider-instruments', label: '数据源品种', icon: 'database' },
      { to: '/products/symbol-bindings', label: '品种源绑定', icon: 'link' }
    ]
  },
  {
    label: '财务管理',
    icon: 'wallet',
    items: [
      { to: '/finance/ledger', label: '资金明细', icon: 'receipt', pageKey: 'finance-ledger' },
      { to: '/finance/recharge-orders', label: '充值订单', icon: 'download', pageKey: 'recharge-orders' },
      { to: '/finance/withdrawal-orders', label: '提现订单', icon: 'upload', pageKey: 'withdrawal-orders' },
      { to: '/finance/payment-methods', label: '收款方式', icon: 'bank', pageKey: 'payment-methods' }
    ]
  },
  {
    label: '用户管理',
    icon: 'users',
    items: [
      { to: '/members/list', label: '用户列表', icon: 'users', pageKey: 'members' },
      { to: '/members/payment-accounts', label: '用户银行卡', icon: 'card', pageKey: 'member-payment-accounts' }
    ]
  },
  {
    label: '订单管理',
    icon: 'clipboard',
    items: [
      { to: '/orders/history', label: '挂单/持仓/历史', icon: 'history', pageKey: 'order-history' }
    ]
  },
  {
    label: '日志',
    icon: 'clock',
    items: [
      { to: '/logs/verification-codes', label: '验证码发送记录', icon: 'rotate', pageKey: 'verification-codes' },
      { to: '/logs/request-logs', label: '请求日志', icon: 'activity', pageKey: 'request-logs' }
    ]
  },
  {
    label: '内容',
    icon: 'book',
    items: [
      { to: '/content/notices', label: '公告列表', icon: 'notice', pageKey: 'notices' },
      { to: '/content/news', label: '新闻列表', icon: 'news', pageKey: 'news' },
      { to: '/content/popup-campaigns/policy', label: '弹窗策略', icon: 'settings' },
      { to: '/content/popup-campaigns', label: '弹窗活动', icon: 'layout' },
      { to: '/content/member-notices', label: '普通消息', icon: 'bell' }
    ]
  },
  {
    label: '系统设置',
    icon: 'settings',
    items: [
      { to: '/config/settings/site', label: '站点配置', icon: 'settings', pageKey: 'settings-site' },
      { to: '/config/settings/upload', label: '上传配置', icon: 'upload', pageKey: 'settings-upload' },
      { to: '/config/settings/sms', label: '短信配置', icon: 'message', pageKey: 'settings-sms' },
      { to: '/config/settings/email', label: '邮箱配置', icon: 'mail', pageKey: 'settings-email' },
      { to: '/config/settings/footer', label: '底部导航', icon: 'layout', pageKey: 'settings-footer' }
    ]
  },
  {
    label: 'Demo 交易运营',
    icon: 'activity',
    items: [
      { to: '/trading/lab', label: '交易路径实验室', icon: 'activity', authority: 'TRADING_LAB_VIEW' },
      { to: '/accounts', label: '交易账户', icon: 'user' },
      { to: '/trading/orders', label: '订单', icon: 'clipboard' },
      { to: '/trading/positions', label: '持仓', icon: 'chart' },
      { to: '/trading/trades', label: '成交', icon: 'receipt' },
      { to: '/trading/funding-settlements', label: '资金费结算', icon: 'wallet' },
      { to: '/market/funding-config', label: '资金费配置', icon: 'settings' },
      { to: '/market/status', label: '行情状态', icon: 'activity' },
      { to: '/risk', label: '风控', icon: 'shield' },
      { to: '/audit-logs', label: '审计', icon: 'clock' }
    ]
  }
]

export const adminFeatureRoutes = adminMenuGroups.flatMap((group) => group.items.filter((item) => item.pageKey))

export function findAdminMenuItem(pathname: string) {
  for (const group of adminMenuGroups) {
    const item = group.items.find(
      (entry) => entry.to === pathname || pathname.startsWith(`${entry.to}/`)
    )
    if (item) {
      return { group, item }
    }
  }
  return undefined
}
