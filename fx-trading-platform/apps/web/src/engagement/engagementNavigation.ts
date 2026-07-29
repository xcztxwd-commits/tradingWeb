import {
  buildTradingPath,
  defaultTradingSymbols,
  normalizeTradingProductSymbol,
  type TradingProduct
} from '../app/tradingRoutes.ts'

const staticRoutes = Object.freeze({
  HOME: '/',
  DASHBOARD: '/dashboard',
  MARKETS: '/markets',
  ORDERS: '/orders',
  POSITIONS: '/positions',
  WALLET: '/wallet',
  ACCOUNT_OVERVIEW: '/account/overview',
  ACCOUNT_ASSETS: '/account/assets',
  FUNDING_RECORDS: '/account/orders/funding',
  TRADE_RECORDS: '/account/orders/trades',
  KYC: '/account/security/kyc',
  ACCOUNT_SETTINGS: '/account/settings',
  SECURITY: '/security',
  SETTINGS: '/settings'
} satisfies Record<string, string>)

const uuid = '[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}'
const imageTag = new RegExp(`<img data-asset-id="(${uuid})" alt="[^"]*">`, 'giu')
const allowedTags = new Set([
  'p', 'h1', 'h2', 'h3', 'ul', 'ol', 'li', 'strong', 'em', 'u', 's', 'span', 'a', 'br', 'img'
])

export function resolveEngagementPageKey(pathname: string): string | null {
  if (/^\/trade\/spot(?:\/|$)/u.test(pathname)) return 'TRADE_SPOT'
  if (/^\/trade\/perpetual(?:\/|$)/u.test(pathname)) return 'TRADE_PERPETUAL'
  const path = pathname.length > 1 ? pathname.replace(/\/$/u, '') : pathname
  return ({
    '/': 'HOME',
    '/dashboard': 'DASHBOARD',
    '/markets': 'MARKETS',
    '/orders': 'ORDERS',
    '/positions': 'POSITIONS',
    '/wallet': 'WALLET',
    '/account': 'ACCOUNT_OVERVIEW',
    '/account/overview': 'ACCOUNT_OVERVIEW',
    '/account/assets': 'ACCOUNT_ASSETS',
    '/account/orders/funding': 'FUNDING_RECORDS',
    '/account/orders/trades': 'TRADE_RECORDS',
    '/account/security/kyc': 'KYC',
    '/account/settings': 'ACCOUNT_SETTINGS',
    '/security': 'SECURITY',
    '/settings': 'SETTINGS',
    '/messages': 'MESSAGES'
  } as Record<string, string>)[path] ?? null
}

export function resolveEngagementRoute(routeKey: unknown, params: unknown): string | null {
  if (typeof routeKey !== 'string' || !isStringRecord(params)) return null
  const staticPath = Object.hasOwn(staticRoutes, routeKey)
    ? staticRoutes[routeKey as keyof typeof staticRoutes]
    : undefined
  if (staticPath) return Object.keys(params).length === 0 ? staticPath : null

  if (routeKey === 'TRADE_SPOT') return tradingRoute('spot', params)
  if (routeKey === 'TRADE_PERPETUAL') return tradingRoute('perpetual', params)
  if (routeKey === 'MESSAGE_CENTER') {
    if (!hasOnlyKeys(params, ['filter'])) return null
    const filter = params.filter
    if (filter === undefined) return '/messages'
    return filter === 'ALL' || filter === 'UNREAD' ? `/messages?filter=${filter}` : null
  }
  return null
}

export function prepareEngagementHtml(value: unknown): string | null {
  if (typeof value !== 'string' || value.trim() === '') return null
  if (/<\/?(?:script|iframe|object|embed|style|link|meta|svg|math)\b/iu.test(value)) return null
  if (/\b(?:src|srcset|href|style|on[a-z]+)\s*=/iu.test(value)) return null

  for (const match of value.matchAll(/<\/?([a-z0-9]+)\b[^>]*>/giu)) {
    if (!allowedTags.has(match[1].toLowerCase())) return null
  }
  for (const match of value.matchAll(/<img\b[^>]*>/giu)) {
    if (!new RegExp(`^<img data-asset-id="${uuid}" alt="[^"]*">$`, 'iu').test(match[0])) return null
  }

  return value.replace(
    imageTag,
    (tag, assetId: string) => tag.replace(
      '<img ',
      `<img src="/api/public/engagement/assets/${assetId}" `
    )
  )
}

function tradingRoute(product: TradingProduct, params: Record<string, string>) {
  if (!hasOnlyKeys(params, ['symbol'])) return null
  if (params.symbol === undefined) return buildTradingPath(product, defaultTradingSymbols[product])
  const symbol = normalizeTradingProductSymbol(product, params.symbol)
  return symbol ? buildTradingPath(product, symbol) : null
}

function hasOnlyKeys(value: Record<string, string>, allowed: readonly string[]) {
  return Object.keys(value).every((key) => allowed.includes(key))
}

function isStringRecord(value: unknown): value is Record<string, string> {
  return Boolean(value)
    && typeof value === 'object'
    && !Array.isArray(value)
    && Object.values(value as Record<string, unknown>).every((item) => typeof item === 'string')
}
