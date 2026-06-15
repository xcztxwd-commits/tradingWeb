import type { LedgerEntry } from '../../types/trading'

type Props = {
  entries: LedgerEntry[]
}

export function LedgerTable({ entries }: Props) {
  return (
    <table>
      <thead>
        <tr>
          <th>Type</th>
          <th>Amount</th>
          <th>Balance</th>
          <th>Time</th>
        </tr>
      </thead>
      <tbody>
        {entries.length === 0 ? (
          <tr>
            <td colSpan={4}>No ledger entries</td>
          </tr>
        ) : (
          entries.map((entry) => (
            <tr key={entry.id}>
              <td>{entry.entryType}</td>
              <td>{entry.amount}</td>
              <td>{entry.balanceAfter}</td>
              <td>{entry.createdAt ? new Date(entry.createdAt).toLocaleTimeString() : '-'}</td>
            </tr>
          ))
        )}
      </tbody>
    </table>
  )
}
