import type { AccountSummary as AccountSummaryType } from '@fx-platform/frontend-core'

type Props = {
  account?: AccountSummaryType
}

export function AccountSummary({ account }: Props) {
  const fallback = {
    balance: '10000.00',
    equity: '10000.00',
    usedMargin: '0.00',
    freeMargin: '10000.00',
    marginLevel: '-',
    status: 'DEMO'
  }

  const data = account ?? fallback

  return (
    <section className="account-strip">
      <Metric label="Balance" value={data.balance} />
      <Metric label="Equity" value={data.equity} />
      <Metric label="Used Margin" value={data.usedMargin} />
      <Metric label="Free Margin" value={data.freeMargin} />
      <Metric label="Status" value={data.status} />
    </section>
  )
}

function Metric({ label, value }: { label: string; value: string | number }) {
  return (
    <div className="metric">
      <span>{label}</span>
      <strong>{String(value)}</strong>
    </div>
  )
}
