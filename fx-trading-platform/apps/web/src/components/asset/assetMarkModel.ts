export type AssetMarkModel = SingleAssetMark | ForexPairAssetMark

type SingleAssetMark = {
  kind: 'single'
  asset: string
  display: string
  label: string
  variant: string
}

type ForexPairAssetMark = {
  kind: 'pair'
  asset: string
  label: string
  base: CurrencyMark
  quote: CurrencyMark
  variant: 'fx'
}

export type CurrencyMark = {
  code: string
  icon: string
  label: string
  fallback: boolean
}

const knownAssets = new Set([
  'AUD',
  'BABY',
  'BANANAS',
  'BNB',
  'BTC',
  'DOGE',
  'EIGEN',
  'ETH',
  'EUR',
  'EPIC',
  'GBP',
  'HEI',
  'HMSTR',
  'IO',
  'JASMY',
  'MEGA',
  'MIRA',
  'MITO',
  'NIGHT',
  'OPG',
  'POND',
  'ROBO',
  'SOL',
  'STG',
  'SYN',
  'TRUMP',
  'TST',
  'US100',
  'USD',
  'USDC',
  'USDT',
  'XAU',
  'XAUT',
  'ZKC',
  'ZKP',
  'XRP'
])

const quoteSuffixes = [
  'USDT',
  'USDC',
  'USD',
  'JPY',
  'EUR',
  'GBP',
  'AUD',
  'CAD',
  'CHF',
  'NZD',
  'CNH',
  'CNY',
  'HKD',
  'SGD',
  'SEK',
  'NOK',
  'MXN',
  'ZAR',
  'TRY',
  'PLN',
  'DKK',
  'HUF',
  'CZK',
  'ILS',
  'INR',
  'KRW',
  'THB',
  'BRL',
  'RUB'
]

const currencyIcons: Record<string, { icon: string; label: string }> = {
  AED: { icon: '🇦🇪', label: 'UAE Dirham' },
  AUD: { icon: '🇦🇺', label: 'Australian Dollar' },
  ARS: { icon: '🇦🇷', label: 'Argentine Peso' },
  BDT: { icon: '🇧🇩', label: 'Bangladeshi Taka' },
  BGN: { icon: '🇧🇬', label: 'Bulgarian Lev' },
  BHD: { icon: '🇧🇭', label: 'Bahraini Dinar' },
  BOB: { icon: '🇧🇴', label: 'Bolivian Boliviano' },
  BRL: { icon: '🇧🇷', label: 'Brazilian Real' },
  BSD: { icon: '🇧🇸', label: 'Bahamian Dollar' },
  BWP: { icon: '🇧🇼', label: 'Botswana Pula' },
  CAD: { icon: '🇨🇦', label: 'Canadian Dollar' },
  CHF: { icon: '🇨🇭', label: 'Swiss Franc' },
  CLP: { icon: '🇨🇱', label: 'Chilean Peso' },
  CNH: { icon: '🇨🇳', label: 'Chinese Yuan Offshore' },
  CNY: { icon: '🇨🇳', label: 'Chinese Yuan' },
  COP: { icon: '🇨🇴', label: 'Colombian Peso' },
  CRC: { icon: '🇨🇷', label: 'Costa Rican Colon' },
  CZK: { icon: '🇨🇿', label: 'Czech Koruna' },
  DKK: { icon: '🇩🇰', label: 'Danish Krone' },
  DOP: { icon: '🇩🇴', label: 'Dominican Peso' },
  EGP: { icon: '🇪🇬', label: 'Egyptian Pound' },
  EUR: { icon: '🇪🇺', label: 'Euro' },
  FJD: { icon: '🇫🇯', label: 'Fijian Dollar' },
  GBP: { icon: '🇬🇧', label: 'British Pound' },
  GEL: { icon: '🇬🇪', label: 'Georgian Lari' },
  GHS: { icon: '🇬🇭', label: 'Ghanaian Cedi' },
  HKD: { icon: '🇭🇰', label: 'Hong Kong Dollar' },
  HRK: { icon: '🇭🇷', label: 'Croatian Kuna' },
  HUF: { icon: '🇭🇺', label: 'Hungarian Forint' },
  IDR: { icon: '🇮🇩', label: 'Indonesian Rupiah' },
  ILS: { icon: '🇮🇱', label: 'Israeli New Shekel' },
  INR: { icon: '🇮🇳', label: 'Indian Rupee' },
  ISK: { icon: '🇮🇸', label: 'Icelandic Krona' },
  JOD: { icon: '🇯🇴', label: 'Jordanian Dinar' },
  JPY: { icon: '🇯🇵', label: 'Japanese Yen' },
  KES: { icon: '🇰🇪', label: 'Kenyan Shilling' },
  KRW: { icon: '🇰🇷', label: 'South Korean Won' },
  KWD: { icon: '🇰🇼', label: 'Kuwaiti Dinar' },
  KZT: { icon: '🇰🇿', label: 'Kazakhstani Tenge' },
  LKR: { icon: '🇱🇰', label: 'Sri Lankan Rupee' },
  MAD: { icon: '🇲🇦', label: 'Moroccan Dirham' },
  MXN: { icon: '🇲🇽', label: 'Mexican Peso' },
  MYR: { icon: '🇲🇾', label: 'Malaysian Ringgit' },
  NGN: { icon: '🇳🇬', label: 'Nigerian Naira' },
  NOK: { icon: '🇳🇴', label: 'Norwegian Krone' },
  NZD: { icon: '🇳🇿', label: 'New Zealand Dollar' },
  OMR: { icon: '🇴🇲', label: 'Omani Rial' },
  PEN: { icon: '🇵🇪', label: 'Peruvian Sol' },
  PHP: { icon: '🇵🇭', label: 'Philippine Peso' },
  PKR: { icon: '🇵🇰', label: 'Pakistani Rupee' },
  PLN: { icon: '🇵🇱', label: 'Polish Zloty' },
  QAR: { icon: '🇶🇦', label: 'Qatari Riyal' },
  RON: { icon: '🇷🇴', label: 'Romanian Leu' },
  RSD: { icon: '🇷🇸', label: 'Serbian Dinar' },
  RUB: { icon: '🇷🇺', label: 'Russian Ruble' },
  SAR: { icon: '🇸🇦', label: 'Saudi Riyal' },
  SEK: { icon: '🇸🇪', label: 'Swedish Krona' },
  SGD: { icon: '🇸🇬', label: 'Singapore Dollar' },
  THB: { icon: '🇹🇭', label: 'Thai Baht' },
  TRY: { icon: '🇹🇷', label: 'Turkish Lira' },
  TWD: { icon: '🇹🇼', label: 'New Taiwan Dollar' },
  UAH: { icon: '🇺🇦', label: 'Ukrainian Hryvnia' },
  USD: { icon: '🇺🇸', label: 'US Dollar' },
  UYU: { icon: '🇺🇾', label: 'Uruguayan Peso' },
  VND: { icon: '🇻🇳', label: 'Vietnamese Dong' },
  XAG: { icon: 'Ag', label: 'Silver' },
  XAU: { icon: 'Au', label: 'Gold' },
  XPT: { icon: 'Pt', label: 'Platinum' },
  XPD: { icon: 'Pd', label: 'Palladium' },
  ZAR: { icon: '🇿🇦', label: 'South African Rand' },
  ZMW: { icon: '🇿🇲', label: 'Zambian Kwacha' }
}

export function createAssetMarkModel(symbol: string, category?: string): AssetMarkModel {
  const pair = parseForexPair(symbol, category)
  if (pair) return pair

  const asset = normalizeAssetSymbol(symbol)
  const variant = knownAssets.has(asset) ? asset.toLowerCase() : normalizeCategory(category)
  return {
    kind: 'single',
    asset,
    display: asset.slice(0, asset === 'US100' ? 4 : 3),
    label: asset,
    variant
  }
}

function parseForexPair(symbol: string, category?: string): ForexPairAssetMark | null {
  const parts = splitSymbolParts(symbol)
  if (!parts) return null

  const [baseCode, quoteCode] = parts
  const hasKnownCurrency = Boolean(currencyIcons[baseCode] && currencyIcons[quoteCode])
  if (category !== 'fx' && !hasKnownCurrency) return null

  return {
    kind: 'pair',
    asset: `${baseCode}${quoteCode}`,
    label: `${baseCode}/${quoteCode}`,
    base: toCurrencyMark(baseCode),
    quote: toCurrencyMark(quoteCode),
    variant: 'fx'
  }
}

function splitSymbolParts(symbol: string): [string, string] | null {
  const upper = symbol.trim().toUpperCase()
  const separated = upper.split(/[-/_:\s]+/).filter(Boolean)
  if (separated.length === 2 && separated[0].length === 3 && separated[1].length === 3) {
    return [separated[0], separated[1]]
  }

  const compact = upper.replace(/[^A-Z]/g, '')
  if (compact.length !== 6) return null
  return [compact.slice(0, 3), compact.slice(3)]
}

function toCurrencyMark(code: string): CurrencyMark {
  const known = currencyIcons[code]
  if (known) return { code, icon: known.icon, label: known.label, fallback: false }
  return { code, icon: code, label: code, fallback: true }
}

function normalizeAssetSymbol(symbol: string) {
  const upper = symbol.trim().toUpperCase()
  const suffix = quoteSuffixes.find((item) => upper.endsWith(item) && upper.length > item.length)
  return suffix ? upper.slice(0, -suffix.length) : upper
}

function normalizeCategory(category?: string) {
  if (category === 'fx') return 'fx'
  if (category === 'metals') return 'metal'
  if (category === 'indices') return 'index'
  return 'default'
}
