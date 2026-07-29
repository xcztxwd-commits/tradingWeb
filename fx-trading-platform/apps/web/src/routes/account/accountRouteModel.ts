export const accountRouteModes = [
  'dashboard',
  'overview',
  'assets',
  'funding-records',
  'trade-records',
  'kyc',
  'account-settings',
  'security',
  'settings'
] as const

export type AccountRouteMode = (typeof accountRouteModes)[number]

const accountRoutePaths: Record<AccountRouteMode, string> = {
  dashboard: '/dashboard',
  overview: '/account/overview',
  assets: '/account/assets',
  'funding-records': '/account/orders/funding',
  'trade-records': '/account/orders/trades',
  kyc: '/account/security/kyc',
  'account-settings': '/account/settings',
  security: '/security',
  settings: '/settings'
}

export function getAccountRoutePath(mode: AccountRouteMode) {
  return accountRoutePaths[mode]
}

export function needsAccountSession(mode: AccountRouteMode) {
  return mode !== 'security' && mode !== 'settings'
}
