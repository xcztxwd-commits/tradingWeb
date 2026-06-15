import type { OrderResponse } from './types'

type Props = {
  orders: OrderResponse[]
}

export function OrdersTable({ orders }: Props) {
  return (
    <table>
      <thead>
        <tr>
          <th>Symbol</th>
          <th>Side</th>
          <th>Type</th>
          <th>Lots</th>
          <th>Status</th>
          <th>Price</th>
        </tr>
      </thead>
      <tbody>
        {orders.map((order) => (
          <tr key={order.id}>
            <td>{order.symbol}</td>
            <td>{order.side}</td>
            <td>{order.orderType}</td>
            <td>{order.lots}</td>
            <td>{order.status}</td>
            <td>{order.executionPrice ?? '-'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
