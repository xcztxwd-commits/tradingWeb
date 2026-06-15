import { Activity, ClipboardList, Landmark, ShieldCheck, Users } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'

import { getAdminSnapshot, updateUserStatus, type AdminSnapshot } from '../services/adminApi'
import { login } from '../services/authApi'
import type { AdminUser } from '../types'

const menus = [
  '控制台',
  '用户管理',
  '账户管理',
  '订单管理',
  '持仓管理',
  '成交记录',
  '资金流水',
  '品种配置',
  '风控配置',
  '审计日志'
]

const tokenStorageKey = 'fx-platform-admin-token'

const userStatusLabels: Record<AdminUser['status'], string> = {
  ACTIVE: '正常',
  FROZEN: '冻结',
  DISABLED: '停用'
}

const userRoleLabels: Record<AdminUser['role'], string> = {
  USER: '普通用户',
  ADMIN: '管理员'
}

const riskLevelLabels: Record<string, string> = {
  LOW: '低风险',
  MEDIUM: '中风险',
  HIGH: '高风险'
}

const auditActionLabels: Record<string, string> = {
  ADMIN_ARTICLE_CREATE: '文章创建',
  ADMIN_DICTIONARY_UPSERT: '字典更新',
  ADMIN_FINANCE_ADJUSTMENT: '资金调整',
  ADMIN_FINANCE_DEPOSIT: '后台入金',
  ADMIN_FINANCE_WITHDRAWAL: '后台出金',
  ADMIN_MESSAGE_CREATE: '消息创建',
  ADMIN_ORDER_CANCEL: '订单取消',
  ADMIN_ORDER_CANCEL_IDEMPOTENT: '重复取消订单',
  ADMIN_PAYMENT_METHOD_CREATE: '支付方式创建',
  ADMIN_PAYMENT_METHOD_UPDATE: '支付方式更新',
  ADMIN_POSITION_FORCE_CLOSE: '强平持仓',
  ADMIN_PRICE_ADJUSTMENT_CREATE: '价格调整创建',
  ADMIN_SETTING_UPDATE: '系统设置更新',
  ADMIN_SYMBOL_STATUS_UPDATE: '品种状态更新',
  ADMIN_USER_FORCE_LOGOUT: '强制退出登录',
  ADMIN_USER_KYC_REVIEW: '实名审核',
  ADMIN_USER_NOTE_CREATE: '用户备注创建',
  ADMIN_USER_RISK_LEVEL_UPDATE: '风险等级更新',
  ADMIN_USER_STATUS_UPDATE: '用户状态更新',
  SYMBOL_STATUS_UPDATE: '行情品种状态更新'
}

const auditTargetLabels: Record<string, string> = {
  ACCOUNT: '账户',
  ADMIN_FUND_OPERATION: '资金操作',
  ARTICLE: '文章',
  DICTIONARY: '字典',
  MESSAGE: '消息',
  ORDER: '订单',
  PAYMENT_METHOD: '支付方式',
  POSITION: '持仓',
  SETTING: '系统设置',
  SYMBOL: '品种',
  USER: '用户'
}

export function AdminDashboard() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [token, setToken] = useState(() => localStorage.getItem(tokenStorageKey))
  const [snapshot, setSnapshot] = useState<AdminSnapshot>()
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const stats = useMemo(() => {
    const data = snapshot
    return [
      { label: '活跃用户', value: String(data?.users.filter((user) => user.status === 'ACTIVE').length ?? 0), icon: Users },
      { label: '持仓数量', value: String(data?.positions.length ?? 0), icon: ClipboardList },
      { label: '行情状态', value: compactMarketStatus(data?.marketStatus.status), icon: Activity },
      { label: '审计事件', value: String(data?.auditLogs.length ?? 0), icon: ShieldCheck }
    ]
  }, [snapshot])

  useEffect(() => {
    if (!token) return
    void refresh(token)
  }, [token])

  const submitLogin = async (event: React.FormEvent) => {
    event.preventDefault()
    setLoading(true)
    setError('')
    try {
      const auth = await login(email, password)
      localStorage.setItem(tokenStorageKey, auth.accessToken)
      setToken(auth.accessToken)
    } catch {
      setError('登录失败，请检查账号或密码')
    } finally {
      setLoading(false)
    }
  }

  const changeStatus = async (user: AdminUser, status: AdminUser['status']) => {
    if (!token) return
    setLoading(true)
    setError('')
    try {
      await updateUserStatus(user.id, status, token)
      await refresh(token)
    } catch {
      setError('状态更新失败，请稍后重试')
    } finally {
      setLoading(false)
    }
  }

  const logout = () => {
    localStorage.removeItem(tokenStorageKey)
    setToken(null)
    setSnapshot(undefined)
  }

  return (
    <div className="admin-shell">
      <aside>
        <div className="admin-brand">
          <Landmark size={20} />
          外汇后台
        </div>
        <nav>
          {menus.map((menu) => (
            <button key={menu}>{menu}</button>
          ))}
        </nav>
      </aside>
      <main>
        <header className="admin-topbar">
          <div>
            <h1>后台管理</h1>
            <p>用户、账户、订单、行情源和审计日志的统一入口。</p>
          </div>
          {token ? <button onClick={logout}>退出</button> : null}
        </header>

        {!token ? (
          <form className="login-panel" onSubmit={submitLogin}>
            <label>
              管理员邮箱
              <input
                value={email}
                autoComplete="username"
                placeholder="请输入管理员邮箱"
                onChange={(event) => setEmail(event.target.value)}
              />
            </label>
            <label>
              密码
              <input
                value={password}
                type="password"
                autoComplete="current-password"
                placeholder="请输入密码"
                onChange={(event) => setPassword(event.target.value)}
              />
            </label>
            <button disabled={loading}>{loading ? '登录中' : '登录'}</button>
          </form>
        ) : (
          <>
            <section className="stat-grid">
              {stats.map((stat) => (
                <div className="stat" key={stat.label}>
                  <stat.icon size={20} />
                  <span>{stat.label}</span>
                  <strong>{stat.value}</strong>
                </div>
              ))}
            </section>
            <section className="admin-grid">
              <UserTable users={snapshot?.users ?? []} loading={loading} onStatusChange={changeStatus} />
              <OrdersPanel snapshot={snapshot} />
              <AuditPanel snapshot={snapshot} />
            </section>
          </>
        )}
        {error ? <div className="admin-error">{error}</div> : null}
      </main>
    </div>
  )

  async function refresh(accessToken: string) {
    setLoading(true)
    setError('')
    try {
      setSnapshot(await getAdminSnapshot(accessToken))
    } catch {
      localStorage.removeItem(tokenStorageKey)
      setToken(null)
      setError('后台数据加载失败，请重新登录')
    } finally {
      setLoading(false)
    }
  }
}

function compactMarketStatus(status?: string) {
  if (status === 'USING_DEMO_QUOTES') return '使用模拟行情'
  if (status === 'MASSIVE_CONFIGURED') return '行情源已配置'
  return '状态未知'
}

function formatRiskLevel(value: string) {
  return riskLevelLabels[value] ?? '未评级'
}

function formatAuditAction(value: string) {
  return auditActionLabels[value] ?? '其他操作'
}

function formatAuditTarget(value: string | null) {
  if (!value) return '-'
  return auditTargetLabels[value] ?? '其他对象'
}

function formatDateTime(value: string | null) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '-'
  return date.toLocaleString('zh-CN', { hour12: false })
}

function UserTable({
  users,
  loading,
  onStatusChange
}: {
  users: AdminUser[]
  loading: boolean
  onStatusChange: (user: AdminUser, status: AdminUser['status']) => Promise<void>
}) {
  return (
    <section className="admin-table">
      <h2>用户管理</h2>
      <table>
        <thead>
          <tr>
            <th>邮箱</th>
            <th>角色</th>
            <th>状态</th>
            <th>风险</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          {users.length === 0 ? (
            <tr>
              <td colSpan={5}>{loading ? '正在加载用户数据' : '暂无用户数据'}</td>
            </tr>
          ) : (
            users.map((user) => (
              <tr key={user.id}>
                <td>{user.email}</td>
                <td>{userRoleLabels[user.role]}</td>
                <td>{userStatusLabels[user.status]}</td>
                <td>{formatRiskLevel(user.riskLevel)}</td>
                <td>
                  {user.role === 'ADMIN' ? (
                    '无需操作'
                  ) : user.status === 'ACTIVE' ? (
                    <button disabled={loading} onClick={() => void onStatusChange(user, 'FROZEN')}>
                      冻结
                    </button>
                  ) : (
                    <button disabled={loading} onClick={() => void onStatusChange(user, 'ACTIVE')}>
                      启用
                    </button>
                  )}
                </td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </section>
  )
}

function OrdersPanel({ snapshot }: { snapshot?: AdminSnapshot }) {
  return (
    <section className="admin-table">
      <h2>订单与资金</h2>
      <table>
        <thead>
          <tr>
            <th>指标</th>
            <th>数值</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <td>账户数</td>
            <td>{snapshot?.accounts.length ?? 0}</td>
          </tr>
          <tr>
            <td>订单数</td>
            <td>{snapshot?.orders.length ?? 0}</td>
          </tr>
          <tr>
            <td>资金流水数</td>
            <td>{snapshot?.ledger.length ?? 0}</td>
          </tr>
          <tr>
            <td>交易品种数</td>
            <td>{snapshot?.symbols.length ?? 0}</td>
          </tr>
        </tbody>
      </table>
    </section>
  )
}

function AuditPanel({ snapshot }: { snapshot?: AdminSnapshot }) {
  const rows = snapshot?.auditLogs.slice(0, 6) ?? []
  return (
    <section className="admin-table">
      <h2>审计日志</h2>
      <table>
        <thead>
          <tr>
            <th>动作</th>
            <th>对象</th>
            <th>时间</th>
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr>
              <td colSpan={3}>暂无审计事件</td>
            </tr>
          ) : (
            rows.map((log) => (
              <tr key={log.id}>
                <td>{formatAuditAction(log.action)}</td>
                <td>{formatAuditTarget(log.targetType)}</td>
                <td>{formatDateTime(log.createdAt)}</td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </section>
  )
}
