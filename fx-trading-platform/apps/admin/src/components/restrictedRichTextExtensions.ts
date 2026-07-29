import { Mark, Node as TiptapNode } from '@tiptap/core'

const PLATFORM_ASSET_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const ROUTE_KEY = /^[A-Z][A-Z0-9_]{0,63}$/
const ROUTE_PARAM_KEY = /^[a-z][A-Za-z0-9]{0,31}$/
const MAX_ALT_LENGTH = 200
const MAX_ROUTE_PARAM_LENGTH = 256
const MAX_ROUTE_PARAMS_LENGTH = 2_048

const INTERNAL_ROUTES = new Set([
  'HOME',
  'DASHBOARD',
  'MARKETS',
  'ORDERS',
  'POSITIONS',
  'WALLET',
  'ACCOUNT_OVERVIEW',
  'ACCOUNT_ASSETS',
  'FUNDING_RECORDS',
  'TRADE_RECORDS',
  'KYC',
  'ACCOUNT_SETTINGS',
  'SECURITY',
  'SETTINGS',
  'TRADE_SPOT',
  'TRADE_PERPETUAL',
  'MESSAGE_CENTER'
])

export type PlatformImageReference = Readonly<{
  assetId: string
  alt: string
}>

export type InternalLinkReference = Readonly<{
  routeKey: string
  params: Readonly<Record<string, string>>
}>

export function platformAssetUrl(assetId: unknown) {
  return typeof assetId === 'string' && PLATFORM_ASSET_ID.test(assetId)
    ? `/api/public/engagement/assets/${assetId}`
    : null
}

export function normalizePlatformImageReference(value: PlatformImageReference | null | undefined) {
  if (!value || !platformAssetUrl(value.assetId)) return null
  const alt = typeof value.alt === 'string' ? value.alt.trim().slice(0, MAX_ALT_LENGTH) : ''
  return { assetId: value.assetId, alt } satisfies PlatformImageReference
}

export function normalizeInternalLinkReference(value: InternalLinkReference | null | undefined) {
  if (!value || !ROUTE_KEY.test(value.routeKey) || !INTERNAL_ROUTES.has(value.routeKey)) return null
  if (!isRecord(value.params)) return null

  const params: Record<string, string> = {}
  for (const [key, parameter] of Object.entries(value.params)) {
    if (!ROUTE_PARAM_KEY.test(key) || typeof parameter !== 'string' || parameter.length > MAX_ROUTE_PARAM_LENGTH) {
      return null
    }
    params[key] = parameter
  }
  if (JSON.stringify(params).length > MAX_ROUTE_PARAMS_LENGTH) return null
  return { routeKey: value.routeKey, params } satisfies InternalLinkReference
}

export const PlatformImage = TiptapNode.create({
  name: 'image',
  group: 'block',
  atom: true,
  draggable: false,

  addAttributes() {
    return {
      assetId: { default: null, rendered: false },
      alt: { default: '', rendered: false }
    }
  },

  parseHTML() {
    return []
  },

  renderHTML({ node }) {
    const assetId = node.attrs.assetId
    const src = platformAssetUrl(assetId)
    if (!src) return ['span', { 'data-invalid-platform-image': 'true', 'aria-hidden': 'true' }]
    const alt = typeof node.attrs.alt === 'string' ? node.attrs.alt.slice(0, MAX_ALT_LENGTH) : ''
    return ['img', { src, alt, loading: 'lazy', 'data-platform-asset-id': assetId }]
  },

  renderText({ node }) {
    return typeof node.attrs.alt === 'string' ? node.attrs.alt.slice(0, MAX_ALT_LENGTH) : ''
  }
})

export const InternalLink = Mark.create({
  name: 'link',
  inclusive: false,

  addAttributes() {
    return {
      routeKey: { default: null, rendered: false },
      params: { default: {}, rendered: false }
    }
  },

  parseHTML() {
    return []
  },

  renderHTML({ mark }) {
    const reference = normalizeInternalLinkReference({
      routeKey: mark.attrs.routeKey,
      params: mark.attrs.params
    })
    if (!reference) return ['span', { 'data-invalid-internal-link': 'true' }, 0]
    return [
      'span',
      {
        'data-internal-link': 'true',
        'data-route-key': reference.routeKey,
        'data-route-params': JSON.stringify(reference.params)
      },
      0
    ]
  }
})

function isRecord(value: unknown): value is Readonly<Record<string, string>> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
