import type { PriceType } from '../model/types.ts'

export const PRICE_FIELD_LABELS = {
  bid: 'bid（买一价）',
  ask: 'ask（卖一价）',
  last: 'last（最新价）',
  mark: 'mark（标记价）',
  index: 'index（指数价）',
  price: 'price（委托价）',
  requestedPrice: 'requestedPrice（请求价）',
  triggerPrice: 'triggerPrice（触发价）',
  activationPrice: 'activationPrice（激活价）',
  stopLoss: 'stopLoss（止损价）',
  takeProfit: 'takeProfit（止盈价）',
  fillPrice: 'fillPrice（成交价）',
  entryPrice: 'entryPrice（开仓均价）',
  markPrice: 'markPrice（标记价）',
  breakEvenPrice: 'breakEvenPrice（回本价）',
  estimatedLiquidationPrice: 'estimatedLiquidationPrice（预计强平价）',
} as const

export type PriceFieldKey = keyof typeof PRICE_FIELD_LABELS

export const PRICE_TYPE_LABELS: Readonly<Record<PriceType, string>> = {
  BID: PRICE_FIELD_LABELS.bid,
  ASK: PRICE_FIELD_LABELS.ask,
  LAST: PRICE_FIELD_LABELS.last,
  MARK: PRICE_FIELD_LABELS.mark,
  INDEX: PRICE_FIELD_LABELS.index,
}

export function priceFieldLabel(key: PriceFieldKey): string {
  return PRICE_FIELD_LABELS[key]
}
