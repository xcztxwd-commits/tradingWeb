import type { PositionResponse } from '@fx-platform/frontend-core'

import type { ApiErrorView } from '../../components/user-page/userPageModels'

export type PositionView = 'CURRENT' | 'HISTORY'
export type ProtectionFormValues = { stopLoss: string; takeProfit: string }

export type PositionsRouteModel = {
  visiblePositions: PositionResponse[]
  view: PositionView
  symbol: string
  sessionMode: string
  sessionError: string | null
  loginRequired: boolean
  editingPosition: PositionResponse | null
  protectionForm: ProtectionFormValues
  protectionError: string | null
  pendingClosePosition: PositionResponse | null
  busyPositionId: string | null
  notice: string | null
  apiError: ApiErrorView | null
  setView(value: PositionView): void
  setSymbol(value: string): void
  refresh(): Promise<void>
  openLogin(): void
  openCloseDialog(position: PositionResponse): void
  dismissCloseDialog(): void
  closePosition(position: PositionResponse): Promise<void>
  startProtectionEdit(position: PositionResponse): void
  openProtectionWorkflow(position: PositionResponse): void
  setProtectionField(field: keyof ProtectionFormValues, value: string): void
  dismissProtectionEdit(): void
  submitProtection(): Promise<void>
}
