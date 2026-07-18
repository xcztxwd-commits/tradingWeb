export type AssetMarkModel = SingleAssetMark | ForexPairAssetMark

type SingleAssetMark = {
  kind: 'single'
  asset: string
  display: string
  label: string
  variant: string
  imageUrl?: string
}

type ForexPairAssetMark = {
  kind: 'pair'
  asset: string
  label: string
  base: CurrencyMark
  quote: CurrencyMark
  variant: 'fx'
}

export type CurrencyFlagStyle = 'solid' | 'horizontal' | 'vertical' | 'disc' | 'stripes' | 'cross' | 'diagonal'

export type CurrencyMarkVisual =
  | {
      kind: 'flag'
      style: CurrencyFlagStyle
      colors: string[]
    }
  | {
      kind: 'glyph'
      text: string
    }

export type CurrencyMark = {
  code: string
  visual: CurrencyMarkVisual
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
  'RUB',
  'ARS',
  'CLP',
  'PKR'
]

const currencyMarks: Record<string, { visual: CurrencyMarkVisual; label: string }> = {
  AED: { visual: flag('horizontal', '#00732f', '#ffffff', '#000000'), label: 'UAE Dirham' },
  AUD: { visual: flag('solid', '#012169', '#ffffff', '#e4002b'), label: 'Australian Dollar' },
  ARS: { visual: flag('horizontal', '#74acdf', '#ffffff', '#74acdf'), label: 'Argentine Peso' },
  BDT: { visual: flag('disc', '#006a4e', '#f42a41'), label: 'Bangladeshi Taka' },
  BGN: { visual: flag('horizontal', '#ffffff', '#00966e', '#d62612'), label: 'Bulgarian Lev' },
  BHD: { visual: flag('vertical', '#ffffff', '#ce1126'), label: 'Bahraini Dinar' },
  BOB: { visual: flag('horizontal', '#d52b1e', '#f9e300', '#007934'), label: 'Bolivian Boliviano' },
  BRL: { visual: flag('disc', '#009b3a', '#ffdf00', '#002776'), label: 'Brazilian Real' },
  BSD: { visual: flag('horizontal', '#00abc9', '#fcd116', '#00abc9'), label: 'Bahamian Dollar' },
  BWP: { visual: flag('horizontal', '#6da9d2', '#ffffff', '#000000'), label: 'Botswana Pula' },
  CAD: { visual: flag('vertical', '#ff0000', '#ffffff', '#ff0000'), label: 'Canadian Dollar' },
  CHF: { visual: flag('cross', '#d52b1e', '#ffffff'), label: 'Swiss Franc' },
  CLP: { visual: flag('horizontal', '#0039a6', '#ffffff', '#d52b1e'), label: 'Chilean Peso' },
  CNH: { visual: flag('solid', '#de2910', '#ffde00'), label: 'Chinese Yuan Offshore' },
  CNY: { visual: flag('solid', '#de2910', '#ffde00'), label: 'Chinese Yuan' },
  COP: { visual: flag('horizontal', '#fcd116', '#003893', '#ce1126'), label: 'Colombian Peso' },
  CRC: { visual: flag('horizontal', '#002b7f', '#ffffff', '#ce1126'), label: 'Costa Rican Colon' },
  CZK: { visual: flag('horizontal', '#ffffff', '#d7141a', '#11457e'), label: 'Czech Koruna' },
  DKK: { visual: flag('cross', '#c8102e', '#ffffff'), label: 'Danish Krone' },
  DOP: { visual: flag('cross', '#002d62', '#ffffff', '#ce1126'), label: 'Dominican Peso' },
  EGP: { visual: flag('horizontal', '#ce1126', '#ffffff', '#000000'), label: 'Egyptian Pound' },
  EUR: { visual: flag('solid', '#234ad5', '#f7c948'), label: 'Euro' },
  FJD: { visual: flag('solid', '#68bfe5', '#012169'), label: 'Fijian Dollar' },
  GBP: { visual: flag('cross', '#012169', '#ffffff', '#c8102e'), label: 'British Pound' },
  GEL: { visual: flag('cross', '#ffffff', '#ff0000'), label: 'Georgian Lari' },
  GHS: { visual: flag('horizontal', '#ce1126', '#fcd116', '#006b3f'), label: 'Ghanaian Cedi' },
  HKD: { visual: flag('solid', '#de2910', '#ffffff'), label: 'Hong Kong Dollar' },
  HRK: { visual: flag('horizontal', '#ff0000', '#ffffff', '#171796'), label: 'Croatian Kuna' },
  HUF: { visual: flag('horizontal', '#ce2939', '#ffffff', '#477050'), label: 'Hungarian Forint' },
  IDR: { visual: flag('horizontal', '#ff0000', '#ffffff'), label: 'Indonesian Rupiah' },
  ILS: { visual: flag('horizontal', '#0038b8', '#ffffff', '#0038b8'), label: 'Israeli New Shekel' },
  INR: { visual: flag('horizontal', '#ff9933', '#ffffff', '#138808'), label: 'Indian Rupee' },
  ISK: { visual: flag('cross', '#02529c', '#ffffff', '#dc1e35'), label: 'Icelandic Krona' },
  JOD: { visual: flag('horizontal', '#000000', '#ffffff', '#007a3d'), label: 'Jordanian Dinar' },
  JPY: { visual: flag('disc', '#ffffff', '#bc002d'), label: 'Japanese Yen' },
  KES: { visual: flag('horizontal', '#000000', '#bb0000', '#006600'), label: 'Kenyan Shilling' },
  KRW: { visual: flag('disc', '#ffffff', '#c60c30', '#003478'), label: 'South Korean Won' },
  KWD: { visual: flag('horizontal', '#007a3d', '#ffffff', '#ce1126'), label: 'Kuwaiti Dinar' },
  KZT: { visual: flag('solid', '#00afca', '#f4c430'), label: 'Kazakhstani Tenge' },
  LKR: { visual: flag('vertical', '#00534e', '#ffbe29', '#8d153a'), label: 'Sri Lankan Rupee' },
  MAD: { visual: flag('solid', '#c1272d', '#006233'), label: 'Moroccan Dirham' },
  MXN: { visual: flag('vertical', '#006847', '#ffffff', '#ce1126'), label: 'Mexican Peso' },
  MYR: { visual: flag('stripes', '#cc0001', '#ffffff', '#010066'), label: 'Malaysian Ringgit' },
  NGN: { visual: flag('vertical', '#008751', '#ffffff', '#008751'), label: 'Nigerian Naira' },
  NOK: { visual: flag('cross', '#ba0c2f', '#ffffff', '#00205b'), label: 'Norwegian Krone' },
  NZD: { visual: flag('solid', '#00247d', '#ffffff', '#cc142b'), label: 'New Zealand Dollar' },
  OMR: { visual: flag('horizontal', '#ffffff', '#db161b', '#008000'), label: 'Omani Rial' },
  PEN: { visual: flag('vertical', '#d91023', '#ffffff', '#d91023'), label: 'Peruvian Sol' },
  PHP: { visual: flag('horizontal', '#0038a8', '#ce1126', '#ffffff'), label: 'Philippine Peso' },
  PKR: { visual: flag('vertical', '#ffffff', '#01411c'), label: 'Pakistani Rupee' },
  PLN: { visual: flag('horizontal', '#ffffff', '#dc143c'), label: 'Polish Zloty' },
  QAR: { visual: flag('vertical', '#ffffff', '#8d1b3d'), label: 'Qatari Riyal' },
  RON: { visual: flag('vertical', '#002b7f', '#fcd116', '#ce1126'), label: 'Romanian Leu' },
  RSD: { visual: flag('horizontal', '#c6363c', '#0c4076', '#ffffff'), label: 'Serbian Dinar' },
  RUB: { visual: flag('horizontal', '#ffffff', '#0039a6', '#d52b1e'), label: 'Russian Ruble' },
  SAR: { visual: flag('solid', '#006c35', '#ffffff'), label: 'Saudi Riyal' },
  SEK: { visual: flag('cross', '#006aa7', '#fecc00'), label: 'Swedish Krona' },
  SGD: { visual: flag('horizontal', '#ef3340', '#ffffff'), label: 'Singapore Dollar' },
  THB: { visual: flag('horizontal', '#a51931', '#ffffff', '#2d2a4a'), label: 'Thai Baht' },
  TRY: { visual: flag('solid', '#e30a17', '#ffffff'), label: 'Turkish Lira' },
  TWD: { visual: flag('solid', '#fe0000', '#000095'), label: 'New Taiwan Dollar' },
  UAH: { visual: flag('horizontal', '#0057b7', '#ffd700'), label: 'Ukrainian Hryvnia' },
  USD: { visual: flag('stripes', '#b22234', '#ffffff', '#3c3b6e'), label: 'US Dollar' },
  UYU: { visual: flag('stripes', '#ffffff', '#0038a8', '#fcd116'), label: 'Uruguayan Peso' },
  VND: { visual: flag('solid', '#da251d', '#ffcd00'), label: 'Vietnamese Dong' },
  XAG: { visual: glyph('Ag'), label: 'Silver' },
  XAU: { visual: glyph('Au'), label: 'Gold' },
  XPT: { visual: glyph('Pt'), label: 'Platinum' },
  XPD: { visual: glyph('Pd'), label: 'Palladium' },
  ZAR: { visual: flag('diagonal', '#007749', '#ffffff', '#de3831'), label: 'South African Rand' },
  ZMW: { visual: flag('vertical', '#198a00', '#de2010', '#ef7d00'), label: 'Zambian Kwacha' }
}

export function createAssetMarkModel(symbol: string, category?: string, iconUrl?: string): AssetMarkModel {
  const pair = parseForexPair(symbol, category)
  if (pair) return pair

  const asset = normalizeAssetSymbol(symbol)
  const variant = knownAssets.has(asset) ? asset.toLowerCase() : normalizeCategory(category)
  const imageUrl = normalizeIconUrl(iconUrl)
  const mark: SingleAssetMark = {
    kind: 'single',
    asset,
    display: asset.slice(0, asset === 'US100' ? 4 : 3),
    label: asset,
    variant
  }
  if (imageUrl) mark.imageUrl = imageUrl
  return mark
}

function parseForexPair(symbol: string, category?: string): ForexPairAssetMark | null {
  const parts = splitSymbolParts(symbol)
  if (!parts) return null

  const [baseCode, quoteCode] = parts
  const hasKnownCurrency = Boolean(currencyMarks[baseCode] && currencyMarks[quoteCode])
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
  const known = currencyMarks[code]
  if (known) return { code, visual: known.visual, label: known.label, fallback: false }
  return { code, visual: glyph(code), label: code, fallback: true }
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

function normalizeIconUrl(iconUrl?: string) {
  const text = iconUrl?.trim()
  return text ? text : undefined
}

function flag(style: CurrencyFlagStyle, ...colors: string[]): CurrencyMarkVisual {
  return { kind: 'flag', style, colors }
}

function glyph(text: string): CurrencyMarkVisual {
  return { kind: 'glyph', text }
}
