import { createContentPreviewModel, type ContentPreviewModel, type ContentPreviewSurface } from '../models/contentPreview.ts'
import type { MessageWire, PopupClaimWire } from './engagementTypes.ts'

export type PopupDisplayModel = Readonly<{
  queueSessionId: PopupClaimWire['queueSessionId']
  deliveryId: PopupClaimWire['deliveryId']
  campaignId: PopupClaimWire['campaignId']
  revisionId: PopupClaimWire['revisionId']
  deliveryToken: PopupClaimWire['deliveryToken']
  expiresAt: PopupClaimWire['expiresAt']
  content: ContentPreviewModel
}>

export type MessageReadState = 'UNREAD' | 'READ'

export type MessageModel = Readonly<{
  publicationId: MessageWire['publicationId']
  contentItemId: MessageWire['contentItemId']
  revisionId: MessageWire['revisionId']
  sourceType: MessageWire['sourceType']
  category: MessageWire['category']
  sentAt: MessageWire['sentAt']
  deliveredAt: MessageWire['deliveredAt']
  content: Omit<ContentPreviewModel, 'surface' | 'sizeMode'>
  readState: MessageReadState
  readAt: string | null
  readSource: MessageWire['readSource']
}>

export type MessagePageModel = Readonly<{
  items: readonly MessageModel[]
  page: number
  size: number
  total: number
  totalPages: number
}>

const POPUP_KEYS = [
  'queueSessionId', 'deliveryId', 'campaignId', 'revisionId', 'deliveryToken', 'expiresAt',
  'templateSize', 'title', 'sanitizedHtml', 'coverAssetId', 'ctaLabel', 'ctaRouteKey', 'ctaParams'
] satisfies readonly (keyof PopupClaimWire)[]
const MESSAGE_KEYS = [
  'publicationId', 'contentItemId', 'revisionId', 'sourceType', 'category', 'sentAt', 'deliveredAt',
  'title', 'sanitizedHtml', 'coverAssetId', 'ctaLabel', 'ctaRouteKey', 'ctaParams', 'unread', 'readAt', 'readSource'
] satisfies readonly (keyof MessageWire)[]
const MESSAGE_SOURCE_TYPES = ['MANUAL', 'CAMPAIGN'] as const satisfies readonly MessageWire['sourceType'][]
const MESSAGE_READ_SOURCES = ['USER', 'POPUP', 'READ_ALL'] as const satisfies readonly NonNullable<MessageWire['readSource']>[]

export function createPopupDisplayModel(input: unknown, surface: ContentPreviewSurface): PopupDisplayModel {
  const source = exactRecord(input, 'popup claim', POPUP_KEYS)
  const content = createContentPreviewModel({
    sizeMode: source.templateSize,
    title: source.title,
    sanitizedHtml: source.sanitizedHtml,
    coverAssetId: source.coverAssetId,
    cta: parseCta(source)
  }, surface)

  return Object.freeze({
    queueSessionId: text(source.queueSessionId, 'queueSessionId'),
    deliveryId: text(source.deliveryId, 'deliveryId'),
    campaignId: text(source.campaignId, 'campaignId'),
    revisionId: text(source.revisionId, 'revisionId'),
    deliveryToken: text(source.deliveryToken, 'deliveryToken'),
    expiresAt: text(source.expiresAt, 'expiresAt'),
    content
  })
}

export function createMessagePageModel(input: unknown): MessagePageModel {
  const source = exactRecord(input, 'message page', ['items', 'page', 'size', 'total', 'totalPages'])
  if (!Array.isArray(source.items)) throw new TypeError('items must be an array')
  const page = integer(source.page, 'page')
  const size = integer(source.size, 'size')
  const total = integer(source.total, 'total')
  const totalPages = integer(source.totalPages, 'totalPages')

  return Object.freeze({
    items: Object.freeze(source.items.map(createMessageModel)),
    page,
    size,
    total,
    totalPages
  })
}

export function createUnreadCountModel(input: unknown) {
  return integer(exactRecord(input, 'unread count', ['count']).count, 'count')
}

function createMessageModel(input: unknown): MessageModel {
  const source = exactRecord(input, 'message', MESSAGE_KEYS)
  if (typeof source.unread !== 'boolean') throw new TypeError('unread must be boolean')
  const readAt = nullableText(source.readAt, 'readAt')
  const readSource = nullableEnum(source.readSource, MESSAGE_READ_SOURCES, 'readSource')
  if (source.unread ? readAt !== null || readSource !== null : readAt === null || readSource === null) {
    throw new TypeError('Message receipt state is inconsistent')
  }
  const preview = createContentPreviewModel({
    sizeMode: 'MEDIUM',
    title: source.title,
    sanitizedHtml: source.sanitizedHtml,
    coverAssetId: source.coverAssetId,
    cta: parseCta(source)
  }, 'PC')
  const { surface: _surface, sizeMode: _sizeMode, ...content } = preview

  return Object.freeze({
    publicationId: text(source.publicationId, 'publicationId'),
    contentItemId: text(source.contentItemId, 'contentItemId'),
    revisionId: text(source.revisionId, 'revisionId'),
    sourceType: enumValue(source.sourceType, MESSAGE_SOURCE_TYPES, 'sourceType'),
    category: text(source.category, 'category'),
    sentAt: text(source.sentAt, 'sentAt'),
    deliveredAt: text(source.deliveredAt, 'deliveredAt'),
    content: Object.freeze(content),
    readState: source.unread ? 'UNREAD' : 'READ',
    readAt,
    readSource
  })
}

function parseCta(source: Record<string, unknown>) {
  const values = [source.ctaLabel, source.ctaRouteKey, source.ctaParams]
  if (values.every((value) => value === null || value === undefined)) return null
  if (values.some((value) => typeof value !== 'string')) throw new TypeError('CTA fields must be supplied together')
  let params: unknown
  try {
    params = JSON.parse(source.ctaParams as string)
  } catch {
    throw new TypeError('ctaParams must be valid JSON')
  }
  return { label: source.ctaLabel, routeKey: source.ctaRouteKey, params }
}

function exactRecord(value: unknown, name: string, keys: readonly string[]): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new TypeError(`${name} must be an object`)
  }
  const source = value as Record<string, unknown>
  for (const key of Object.keys(source)) {
    if (!keys.includes(key)) throw new TypeError(`Unsupported field: ${key}`)
  }
  return source
}

function text(value: unknown, name: string) {
  if (typeof value !== 'string' || value.trim() === '') throw new TypeError(`${name} must be non-empty text`)
  return value
}

function nullableText(value: unknown, name: string) {
  return value === null || value === undefined ? null : text(value, name)
}

function enumValue<const T extends string>(value: unknown, allowed: readonly T[], name: string): T {
  if (typeof value !== 'string' || !allowed.includes(value as T)) throw new TypeError(`${name} is unsupported`)
  return value as T
}

function nullableEnum<const T extends string>(value: unknown, allowed: readonly T[], name: string): T | null {
  return value === null || value === undefined ? null : enumValue(value, allowed, name)
}

function integer(value: unknown, name: string) {
  if (!Number.isSafeInteger(value) || (value as number) < 0) throw new TypeError(`${name} must be a non-negative integer`)
  return value as number
}
