import type { components } from '@fx-platform/shared-types'

type Schemas = components['schemas']
type RequiredGenerated<T, K extends keyof T> = Readonly<{
  [P in K]-?: NonNullable<T[P]>
}>
type NullableGenerated<T, K extends keyof T> = Readonly<{
  [P in K]-?: NonNullable<T[P]> | null
}>

type GeneratedPopupSurface = Schemas['PopupSurface']
type GeneratedPopupClaim = Schemas['PopupClaim']
type GeneratedPopupOutcome = Schemas['PopupOutcomeResult']
type GeneratedMessage = Schemas['UserMessageResponse']
type GeneratedMessagePage = Schemas['UserMessagePageResponse']
type GeneratedUnreadCount = Schemas['UnreadMessageCountResponse']

export type PopupSurfaceWire = RequiredGenerated<GeneratedPopupSurface, keyof GeneratedPopupSurface>

export type PopupClaimWire =
  & RequiredGenerated<GeneratedPopupClaim, Exclude<keyof GeneratedPopupClaim,
    'coverAssetId' | 'ctaLabel' | 'ctaRouteKey' | 'ctaParams'>>
  & NullableGenerated<GeneratedPopupClaim,
    'coverAssetId' | 'ctaLabel' | 'ctaRouteKey' | 'ctaParams'>

export type PopupOutcomeWire = RequiredGenerated<GeneratedPopupOutcome, keyof GeneratedPopupOutcome>

export type MessageWire =
  & RequiredGenerated<GeneratedMessage, Exclude<keyof GeneratedMessage,
    'coverAssetId' | 'ctaLabel' | 'ctaRouteKey' | 'ctaParams' | 'readAt' | 'readSource'>>
  & NullableGenerated<GeneratedMessage,
    'coverAssetId' | 'ctaLabel' | 'ctaRouteKey' | 'ctaParams' | 'readAt' | 'readSource'>

export type MessagePageWire = Readonly<{
  items: readonly MessageWire[]
  page: NonNullable<GeneratedMessagePage['page']>
  size: NonNullable<GeneratedMessagePage['size']>
  total: NonNullable<GeneratedMessagePage['total']>
  totalPages: NonNullable<GeneratedMessagePage['totalPages']>
}>

export type UnreadCountWire = RequiredGenerated<GeneratedUnreadCount, 'count'>
