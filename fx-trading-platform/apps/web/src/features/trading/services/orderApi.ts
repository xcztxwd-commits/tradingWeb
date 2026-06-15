import type { MockOrderPayload, MockOrderResponse } from '../types/order'

export function submitOrder(payload: MockOrderPayload): Promise<MockOrderResponse> {
  return Promise.resolve({
    success: true,
    orderId: `mock_${Date.now()}`,
    status: 'submitted',
    payload
  })
}
