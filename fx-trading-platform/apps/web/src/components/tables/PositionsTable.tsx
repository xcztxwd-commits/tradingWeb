import type { PositionResponse } from '@fx-platform/frontend-core'

type Props = {
  positions: PositionResponse[]
  onClose?: (position: PositionResponse) => Promise<void>
}

export function PositionsTable({ positions, onClose }: Props) {
  return (
    <table>
      <thead>
        <tr>
          <th>Symbol</th>
          <th>Side</th>
          <th>Lots</th>
          <th>Open</th>
          <th>Margin</th>
          <th>PnL</th>
          <th>Status</th>
          <th>Action</th>
        </tr>
      </thead>
      <tbody>
        {positions.length === 0 ? (
          <tr>
            <td colSpan={8}>No open positions</td>
          </tr>
        ) : (
          positions.map((position) => (
            <tr key={position.id}>
              <td>{position.symbol}</td>
              <td>{position.side}</td>
              <td>{position.lots}</td>
              <td>{position.openPrice}</td>
              <td>{position.marginHeld}</td>
              <td>{position.floatingPnl}</td>
              <td>{position.status}</td>
              <td>
                <button className="table-action" onClick={() => void onClose?.(position)}>
                  Close
                </button>
              </td>
            </tr>
          ))
        )}
      </tbody>
    </table>
  )
}
