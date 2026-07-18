import type { OrderEventResponse, OrderResponse } from '@fx-platform/frontend-core'

import type { ApiErrorView } from '../../components/user-page/userPageModels'

export type OrderView = 'CURRENT' | 'HISTORY' | 'TRADES' | 'EVENTS'

export type OrderModifyFields = {
  quantity: string
  price: string
  triggerPrice: string
  triggerExecutionType: string
}

export type OrdersRouteModel = {
  visibleOrders: OrderResponse[]
  events: OrderEventResponse[]
  view: OrderView
  status: string
  symbol: string
  fromDate: string
  toDate: string
  hasActiveFilters: boolean
  emptyMessage: string
  sessionMode: string
  sessionError: string | null
  loginRequired: boolean
  busyOrderId: string | null
  pendingCancelOrder: OrderResponse | null
  editingOrder: OrderResponse | null
  modifyFields: OrderModifyFields
  notice: string | null
  apiError: ApiErrorView | null
  setView(value: OrderView): void
  setStatus(value: string): void
  setSymbol(value: string): void
  setFromDate(value: string): void
  setToDate(value: string): void
  setModifyField(field: keyof OrderModifyFields, value: string): void
  clearFilters(): void
  refresh(): Promise<void>
  requestCancel(order: OrderResponse): void
  dismissCancel(): void
  confirmCancel(): Promise<void>
  showEvents(order: OrderResponse): Promise<void>
  startModify(order: OrderResponse): void
  dismissModify(): void
  submitModify(): Promise<void>
  openLogin(): void
}
