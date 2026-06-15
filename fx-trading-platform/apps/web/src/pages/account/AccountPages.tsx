import { ArrowDownCircle, ArrowUpCircle, Bell, CheckCircle2, CircleDollarSign, Settings, ShieldCheck, UserRound } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link, NavLink } from 'react-router-dom'

const accountNav = [
  { to: '/account/overview', label: '总览' },
  { to: '/account/assets', label: '资产' },
  { to: '/account/orders/funding', label: '资金流水' },
  { to: '/account/orders/trades', label: '交易订单' },
  { to: '/account/security/kyc', label: '身份认证' },
  { to: '/account/settings', label: '设置' }
] as const

const ledgers = [
  { time: '2026-06-14 09:20', type: '充值', asset: 'USDT', amount: '+2,400.00', note: '账户入金' },
  { time: '2026-06-14 10:42', type: '交易', asset: 'BTC', amount: '-0.018', note: '现货买入' },
  { time: '2026-06-14 12:18', type: '提现', asset: 'USDT', amount: '-300.00', note: '处理中' }
] as const

const tradeOrders = [
  { pair: 'BTCUSDT', side: '买入', status: '当前委托', price: '67,240.00', quantity: '0.018', time: '2026-06-14 12:40' },
  { pair: 'ETHUSDT', side: '卖出', status: '历史委托', price: '3,420.00', quantity: '0.42', time: '2026-06-13 22:11' },
  { pair: 'EURUSD', side: '买入', status: '历史成交', price: '1.0832', quantity: '20,000', time: '2026-06-13 14:32' }
] as const

export function AccountOverviewPage() {
  return (
    <AccountShell title="总览" summary="账户、资产、认证和近期行为集中展示。">
      <div className="account-dashboard-layout">
        <AccountProfileSummary />
        <AccountOnboardingSteps />
        <AccountAssetActionPanel />
        <AccountDashboardInsights />
      </div>
    </AccountShell>
  )
}

function AccountProfileSummary() {
  return (
    <section className="account-profile-summary" aria-label="账户资料摘要">
      <div className="account-profile-summary__identity">
        <span>FX</span>
        <div>
          <strong>fx***@example.com</strong>
          <small>UID 829341 · Lv.1 现货用户</small>
        </div>
      </div>
      <div className="account-profile-summary__stats">
        <AccountProfileStat label="账户状态" value="待验证" />
        <AccountProfileStat label="可用入口" value="6 个" />
        <AccountProfileStat label="安全提醒" value="1 项" />
      </div>
    </section>
  )
}

function AccountProfileStat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  )
}

function AccountOnboardingSteps() {
  const steps = [
    {
      title: '身份认证',
      note: '完成基础认证后开放充值、提现和更高交易限额。',
      action: '立即验证',
      to: '/account/security/kyc',
      active: true
    },
    {
      title: '充值资产',
      note: '查看钱包结构、近期流水和可用资金。',
      action: '查看钱包',
      to: '/account/assets'
    },
    {
      title: '开始交易',
      note: '进入交易页选择 BTCUSDT、ETHUSDT 或外汇品种。',
      action: '去交易',
      to: '/trading'
    }
  ]

  return (
    <section className="account-onboarding-section">
      <div className="account-panel__head">
        <div>
          <h2>新手任务</h2>
          <p>按认证、资金、交易的顺序推进，减少第一次使用时的跳转成本。</p>
        </div>
      </div>
      <div className="account-onboarding-grid">
        {steps.map((step, index) => (
          <article className={step.active ? 'account-step-card account-step-card--active' : 'account-step-card'} key={step.title}>
            <span>{String(index + 1).padStart(2, '0')}</span>
            <div>
              <strong>{step.title}</strong>
              <small>{step.note}</small>
            </div>
            <Link className={step.active ? 'table-action table-action--primary' : 'table-action table-action--secondary'} to={step.to}>
              {step.action}
            </Link>
          </article>
        ))}
      </div>
    </section>
  )
}

function AccountAssetActionPanel() {
  return (
    <section className="account-asset-panel">
      <div>
        <span>总资产估值</span>
        <strong>12,842.30 USDT</strong>
        <small>今日变化 +1.82%</small>
      </div>
      <div className="account-action-strip" aria-label="资产快捷操作">
        <Link className="table-action table-action--primary" to="/account/orders/funding">
          <ArrowDownCircle size={16} aria-hidden="true" />
          充值
        </Link>
        <button className="table-action table-action--secondary" type="button">
          <ArrowUpCircle size={16} aria-hidden="true" />
          提现
        </button>
        <Link className="table-action table-action--secondary" to="/trading">
          <CircleDollarSign size={16} aria-hidden="true" />
          交易
        </Link>
      </div>
    </section>
  )
}

function AccountDashboardInsights() {
  const insights = [
    {
      icon: <ShieldCheck size={20} aria-hidden="true" />,
      title: '风险与安全',
      value: '1 项待处理',
      note: '完成身份认证后可提升资金权限。'
    },
    {
      icon: <CheckCircle2 size={20} aria-hidden="true" />,
      title: '资产结构',
      value: '现货 65%',
      note: '合约和资金账户保持轻量占比。'
    },
    {
      icon: <Bell size={20} aria-hidden="true" />,
      title: '近期活动',
      value: '3 条流水',
      note: '充值、交易、提现记录集中在资金流水页。'
    }
  ]

  return (
    <div className="account-insight-grid">
      {insights.map((insight) => (
        <section className="account-insight-card" key={insight.title}>
          {insight.icon}
          <div>
            <span>{insight.title}</span>
            <strong>{insight.value}</strong>
            <small>{insight.note}</small>
          </div>
        </section>
      ))}
    </div>
  )
}

export function AccountAssetsPage() {
  return (
    <AccountShell title="钱包总览" summary="资产曲线、近期流水和账户卡片保持在同一屏。">
      <div className="account-split-grid">
        <AssetSummaryChartCard />
        <RecentLedgerPanel />
      </div>
      <AssetAccountCards />
    </AccountShell>
  )
}

export function FundingRecordsPage() {
  return (
    <AccountShell title="资金流水" summary="只展示总览、充值和提现三类资金记录。">
      <div className="account-filter-row">
        <button type="button" aria-pressed="true">总览</button>
        <button type="button">充值</button>
        <button type="button">提现</button>
        <select aria-label="资产">
          <option>全部资产</option>
          <option>USDT</option>
          <option>BTC</option>
        </select>
      </div>
      <AccountTable
        headers={['时间(UTC+8)', '类别', '资产', '数量', '备注']}
        rows={ledgers.map((row) => [row.time, row.type, row.asset, row.amount, row.note])}
      />
    </AccountShell>
  )
}

export function TradeOrdersPage() {
  return (
    <AccountShell title="交易订单" summary="当前委托、历史委托和历史成交保持轻量筛选。">
      <div className="account-filter-row">
        <button type="button" aria-pressed="true">当前委托</button>
        <button type="button">历史委托</button>
        <button type="button">历史成交</button>
        <input aria-label="交易对" placeholder="BTCUSDT" />
        <select aria-label="方向">
          <option>全部方向</option>
          <option>买入</option>
          <option>卖出</option>
        </select>
      </div>
      <AccountTable
        headers={['交易对', '方向', '状态', '价格', '数量', '时间']}
        rows={tradeOrders.map((row) => [row.pair, row.side, row.status, row.price, row.quantity, row.time])}
      />
    </AccountShell>
  )
}

export function KycPage() {
  return (
    <AccountShell title="身份认证" summary="认证状态和下一步操作放在同一个清晰面板。">
      <section className="account-panel account-panel--kyc">
        <ShieldCheck size={28} aria-hidden="true" />
        <div>
          <h2>完成身份认证</h2>
          <p>提交基础身份信息后开启充值、提现和更高交易限额。</p>
          <button className="table-action table-action--primary" type="button">
            立即验证
          </button>
        </div>
      </section>
    </AccountShell>
  )
}

export function AccountSettingsPage() {
  return (
    <AccountShell title="设置" summary="只保留第一版需要的昵称、通知语言和偏好设置。">
      <div className="settings-list">
        <PreferenceRows icon={<UserRound size={19} aria-hidden="true" />} title="昵称和头像" value="FX member" />
        <PreferenceRows icon={<Bell size={19} aria-hidden="true" />} title="通知语言" value="简体中文" />
        <PreferenceRows icon={<Settings size={19} aria-hidden="true" />} title="交易偏好" value="默认进入最近交易品种" />
      </div>
    </AccountShell>
  )
}

function AccountShell({ title, summary, children }: { title: string; summary: string; children: ReactNode }) {
  return (
    <section className="user-page account-shell" aria-labelledby="account-page-title">
      <aside className="account-sidebar" aria-label="个人中心导航">
        <div className="account-sidebar__profile">
          <span>FX</span>
          <div>
            <strong>fx***@example.com</strong>
            <small>UID 829341</small>
          </div>
        </div>
        <nav>
          {accountNav.map((item) => (
            <NavLink key={item.to} to={item.to}>
              {item.label}
            </NavLink>
          ))}
        </nav>
      </aside>
      <main className="account-main">
        <header className="user-page__header">
          <div>
            <h1 id="account-page-title">{title}</h1>
            <p>{summary}</p>
          </div>
        </header>
        {children}
      </main>
    </section>
  )
}

function AssetSummaryChartCard() {
  return (
    <section className="account-panel asset-chart-card">
      <div className="account-panel__head">
        <div>
          <h2>资产估值</h2>
          <p>12,842.30 USDT</p>
        </div>
        <AssetRangeTabs />
      </div>
      <svg viewBox="0 0 420 180" role="img" aria-label="资产曲线">
        <path d="M12 144 C84 112 120 128 168 92 C226 48 266 86 314 58 C360 32 386 44 408 28" />
        <path d="M12 144 C84 112 120 128 168 92 C226 48 266 86 314 58 C360 32 386 44 408 28 L408 168 L12 168 Z" />
      </svg>
      <div className="asset-action-row">
        <Link className="table-action table-action--primary" to="/account/orders/funding">
          <ArrowDownCircle size={16} aria-hidden="true" />
          充值
        </Link>
        <button className="table-action table-action--secondary" type="button">
          <ArrowUpCircle size={16} aria-hidden="true" />
          提现
        </button>
      </div>
    </section>
  )
}

function AssetRangeTabs() {
  return (
    <div className="asset-range-tabs" aria-label="资产区间">
      {['1日', '1周', '1月', '半年', '1年'].map((range, index) => (
        <button key={range} type="button" aria-pressed={index === 2}>
          {range}
        </button>
      ))}
    </div>
  )
}

function RecentLedgerPanel() {
  return (
    <section className="account-panel">
      <div className="account-panel__head">
        <h2>近期流水</h2>
        <Link to="/account/orders/funding">全部</Link>
      </div>
      <ul className="ledger-list">
        {ledgers.map((row) => (
          <li key={`${row.time}-${row.type}`}>
            <span>{row.type}</span>
            <strong>{row.amount} {row.asset}</strong>
            <small>{row.time}</small>
          </li>
        ))}
      </ul>
    </section>
  )
}

function AssetAccountCards() {
  const cards = [
    { title: '现货账户', value: '8,420.10 USDT', note: '可用资金' },
    { title: '合约账户', value: '3,120.44 USDT', note: '保证金余额' },
    { title: '资金账户', value: '1,301.76 USDT', note: '充值提现' }
  ]
  return (
    <div className="account-overview-grid">
      {cards.map((card) => (
        <section key={card.title} className="account-card">
          <CheckCircle2 size={20} aria-hidden="true" />
          <div>
            <span>{card.title}</span>
            <strong>{card.value}</strong>
            <small>{card.note}</small>
          </div>
        </section>
      ))}
    </div>
  )
}

function AccountTable({ headers, rows }: { headers: string[]; rows: string[][] }) {
  return (
    <div className="account-table">
      <table>
        <thead>
          <tr>
            {headers.map((header) => (
              <th key={header}>{header}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.join('|')}>
              {row.map((cell) => (
                <td key={cell}>{cell}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function PreferenceRows({ icon, title, value }: { icon: ReactNode; title: string; value: string }) {
  return (
    <section className="preference-row">
      {icon}
      <div>
        <strong>{title}</strong>
        <span>{value}</span>
      </div>
      <button type="button">编辑</button>
    </section>
  )
}
