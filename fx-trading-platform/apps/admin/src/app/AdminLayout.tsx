import { useEffect, useMemo, useState } from 'react'
import {
  Activity,
  BadgeCheck,
  Bell,
  BookOpen,
  Box,
  Briefcase,
  Building2,
  ClipboardList,
  CreditCard,
  Download,
  FileClock,
  Gauge,
  Grid2X2,
  Home,
  Landmark,
  LayoutDashboard,
  LineChart,
  ListChecks,
  LogOut,
  Mail,
  Menu,
  MessageSquare,
  Newspaper,
  ReceiptText,
  Settings,
  ShieldCheck,
  Upload,
  User,
  Users,
  Wallet,
  X,
  type LucideIcon
} from 'lucide-react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useLocation } from 'react-router-dom'

import { adminMenuGroups, findAdminMenuItem } from './adminMenu'
import { clearAdminToken } from '../services/adminToken'

type RouteTab = {
  to: string
  label: string
}

const iconMap: Record<string, LucideIcon> = {
  activity: Activity,
  badge: BadgeCheck,
  bank: Landmark,
  bell: Bell,
  book: BookOpen,
  box: Box,
  briefcase: Briefcase,
  building: Building2,
  card: CreditCard,
  chart: LineChart,
  clipboard: ClipboardList,
  clock: FileClock,
  download: Download,
  gauge: Gauge,
  history: FileClock,
  home: Home,
  layout: LayoutDashboard,
  list: ListChecks,
  mail: Mail,
  menu: Menu,
  message: MessageSquare,
  news: Newspaper,
  notice: BookOpen,
  receipt: ReceiptText,
  rotate: Activity,
  settings: Settings,
  shield: ShieldCheck,
  upload: Upload,
  user: User,
  users: Users,
  wallet: Wallet
}

export function AdminLayout() {
  const navigate = useNavigate()
  const location = useLocation()
  const [collapsed, setCollapsed] = useState(false)
  const [tabs, setTabs] = useState<RouteTab[]>([{ to: '/dashboard', label: '仪表盘' }])

  const activeEntry = useMemo(() => findAdminMenuItem(location.pathname), [location.pathname])
  const activeLabel = activeEntry?.item.label ?? routeFallbackLabel(location.pathname)

  useEffect(() => {
    if (location.pathname === '/login') return
    setTabs((current) => {
      if (current.some((tab) => tab.to === location.pathname)) return current
      return [...current, { to: location.pathname, label: activeLabel }]
    })
  }, [activeLabel, location.pathname])

  const logout = () => {
    clearAdminToken()
    navigate('/login', { replace: true })
  }

  const closeTab = (tab: RouteTab) => {
    setTabs((current) => {
      const next = current.filter((item) => item.to !== tab.to)
      if (location.pathname === tab.to) {
        navigate(next.at(-1)?.to ?? '/dashboard', { replace: true })
      }
      return next.length ? next : [{ to: '/dashboard', label: '仪表盘' }]
    })
  }

  return (
    <div className={`admin-shell ${collapsed ? 'is-collapsed' : ''}`}>
      <a className="admin-skip-link" href="#admin-content">
        跳到主内容
      </a>
      <aside className="admin-sidebar" aria-label="后台导航">
        <div className="admin-brand">
          <span className="brand-mark">FX</span>
          {!collapsed ? (
            <span className="brand-copy">
              <strong className="brand-title">后台管理系统</strong>
              <span className="brand-subtitle">运营控制台</span>
            </span>
          ) : null}
        </div>
        <nav className="admin-nav">
          {adminMenuGroups.map((group) => {
            const GroupIcon = iconFor(group.icon)
            return (
              <section key={group.label} className="admin-nav-group">
                <div className="admin-nav-group-title">
                  <GroupIcon size={16} />
                  {!collapsed ? <span>{group.label}</span> : null}
                </div>
                {group.items.map((item) => {
                  const ItemIcon = iconFor(item.icon)
                  return (
                    <NavLink key={item.to} to={item.to} className="admin-nav-link" title={item.label}>
                      <ItemIcon size={16} />
                      {!collapsed ? <span>{item.label}</span> : null}
                    </NavLink>
                  )
                })}
              </section>
            )
          })}
        </nav>
        <button
          type="button"
          className={collapsed ? 'sidebar-collapse is-active' : 'sidebar-collapse'}
          aria-label={collapsed ? '展开侧边栏' : '收起侧边栏'}
          title={collapsed ? '展开侧边栏' : '收起侧边栏'}
          onClick={() => setCollapsed((value) => !value)}
        >
          <Menu size={16} />
        </button>
      </aside>

      <main className="admin-main">
        <header className="admin-topbar">
          <BreadcrumbTrail groupLabel={activeEntry?.group.label ?? '后台'} pageLabel={activeLabel} />
          <div className="topbar-actions">
            <button type="button" className="topbar-icon-button" title="全屏" onClick={toggleFullscreen}>
              <LayoutDashboard size={16} />
              <span>全屏</span>
            </button>
            <button type="button" className="topbar-icon-button" title="通知">
              <Bell size={16} />
              <span>通知</span>
            </button>
            <button type="button" className="topbar-icon-button" title="表格设置">
              <Settings size={16} />
              <span>表格设置</span>
            </button>
            <button type="button" className="topbar-icon-button" title="九宫格">
              <Grid2X2 size={16} />
              <span>九宫格</span>
            </button>
            <button type="button" className="topbar-icon-button" onClick={logout}>
              <LogOut size={16} />
              <span>退出</span>
            </button>
            <span className="admin-avatar">A</span>
          </div>
        </header>

        <RouteTabs tabs={tabs} activePath={location.pathname} onClose={closeTab} />

        <section id="admin-content" className="admin-content" tabIndex={-1}>
          <Outlet />
        </section>
      </main>
    </div>
  )
}

function RouteTabs({ tabs, activePath, onClose }: { tabs: RouteTab[]; activePath: string; onClose: (tab: RouteTab) => void }) {
  return (
    <div className="route-tabs">
      {tabs.map((tab) => (
        <NavLink key={tab.to} to={tab.to} className={tab.to === activePath ? 'route-tab active' : 'route-tab'}>
          <span>{tab.label}</span>
          {tab.to !== '/dashboard' ? (
            <button
              type="button"
              onClick={(event) => {
                event.preventDefault()
                onClose(tab)
              }}
              aria-label={`关闭${tab.label}`}
            >
              <X size={13} />
            </button>
          ) : null}
        </NavLink>
      ))}
    </div>
  )
}

function BreadcrumbTrail({ groupLabel, pageLabel }: { groupLabel: string; pageLabel: string }) {
  return (
    <div className="breadcrumb-trail">
      <span>{groupLabel}</span>
      <span>/</span>
      <strong>{pageLabel}</strong>
    </div>
  )
}

function iconFor(name: string) {
  return iconMap[name] ?? LayoutDashboard
}

function routeFallbackLabel(pathname: string) {
  if (pathname === '/dashboard') return '仪表盘'
  return pathname.split('/').filter(Boolean).at(-1) ?? '后台'
}

function toggleFullscreen() {
  if (document.fullscreenElement) {
    void document.exitFullscreen()
    return
  }
  void document.documentElement.requestFullscreen?.()
}
