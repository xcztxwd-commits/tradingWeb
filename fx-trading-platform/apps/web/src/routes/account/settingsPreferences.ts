export const settingsStorageKey = 'fx.settings.preferences.v1'

export const accountPanelFocusOptions = ['currentOrders', 'accountInfo', 'fundOverview'] as const
export const loginAlertModeOptions = ['everyLogin', 'unusualLogin', 'off'] as const
export const mobileTableModeOptions = ['cardList', 'summaryCards'] as const
export const numberFontOptions = ['Tabular figures', 'System default'] as const
export const pnlDisplayOptions = ['colorAndSign', 'signOnly', 'valueOnly'] as const
export const quickEntryOptions = ['marketsOrdersPositions', 'walletSecuritySettings'] as const
export const slippageAlertOptions = ['0.30%', '0.50%', '1.00%'] as const
export const tableDensityOptions = ['compact', 'standard', 'comfortable'] as const
export const terminalLayoutOptions = ['chartFirst', 'orderFirst'] as const
export const tradeNotificationsOptions = ['inApp', 'silent'] as const
export const defaultOrderTypeOptions = ['Limit', 'Market'] as const

export type SettingsPreferences = {
  accountPanelFocus: (typeof accountPanelFocusOptions)[number]
  defaultOrderType: (typeof defaultOrderTypeOptions)[number]
  fundReviewNotifications: boolean
  loginAlertMode: (typeof loginAlertModeOptions)[number]
  mobileTableMode: (typeof mobileTableModeOptions)[number]
  numberFont: (typeof numberFontOptions)[number]
  pnlDisplay: (typeof pnlDisplayOptions)[number]
  priceProtection: boolean
  quickEntry: (typeof quickEntryOptions)[number]
  requireOrderConfirm: boolean
  riskAlerts: boolean
  slippageAlert: (typeof slippageAlertOptions)[number]
  tableDensity: (typeof tableDensityOptions)[number]
  terminalLayout: (typeof terminalLayoutOptions)[number]
  tradeNotifications: (typeof tradeNotificationsOptions)[number]
}

export const defaultSettingsPreferences: SettingsPreferences = {
  accountPanelFocus: 'currentOrders',
  defaultOrderType: 'Limit',
  fundReviewNotifications: true,
  loginAlertMode: 'everyLogin',
  mobileTableMode: 'cardList',
  numberFont: 'Tabular figures',
  pnlDisplay: 'colorAndSign',
  priceProtection: true,
  quickEntry: 'marketsOrdersPositions',
  requireOrderConfirm: true,
  riskAlerts: true,
  slippageAlert: '0.50%',
  tableDensity: 'standard',
  terminalLayout: 'chartFirst',
  tradeNotifications: 'inApp'
}

export function loadSettingsPreferences(): SettingsPreferences {
  try {
    if (typeof window === 'undefined') return defaultSettingsPreferences
    const storedPreferences = window.localStorage.getItem(settingsStorageKey)
    if (!storedPreferences) return defaultSettingsPreferences
    return normalizeSettingsPreferences(JSON.parse(storedPreferences))
  } catch {
    return defaultSettingsPreferences
  }
}

export function normalizeSettingsPreferences(
  storedPreferences: Partial<Record<keyof SettingsPreferences, unknown>>
): SettingsPreferences {
  return {
    accountPanelFocus: pickStoredOption(
      storedPreferences.accountPanelFocus,
      accountPanelFocusOptions,
      defaultSettingsPreferences.accountPanelFocus
    ),
    defaultOrderType: pickStoredOption(
      storedPreferences.defaultOrderType,
      defaultOrderTypeOptions,
      defaultSettingsPreferences.defaultOrderType
    ),
    fundReviewNotifications:
      typeof storedPreferences.fundReviewNotifications === 'boolean'
        ? storedPreferences.fundReviewNotifications
        : defaultSettingsPreferences.fundReviewNotifications,
    loginAlertMode: pickStoredOption(
      storedPreferences.loginAlertMode,
      loginAlertModeOptions,
      defaultSettingsPreferences.loginAlertMode
    ),
    mobileTableMode: pickStoredOption(
      storedPreferences.mobileTableMode,
      mobileTableModeOptions,
      defaultSettingsPreferences.mobileTableMode
    ),
    numberFont: pickStoredOption(
      storedPreferences.numberFont,
      numberFontOptions,
      defaultSettingsPreferences.numberFont
    ),
    pnlDisplay: pickStoredOption(
      storedPreferences.pnlDisplay,
      pnlDisplayOptions,
      defaultSettingsPreferences.pnlDisplay
    ),
    priceProtection:
      typeof storedPreferences.priceProtection === 'boolean'
        ? storedPreferences.priceProtection
        : defaultSettingsPreferences.priceProtection,
    quickEntry: pickStoredOption(
      storedPreferences.quickEntry,
      quickEntryOptions,
      defaultSettingsPreferences.quickEntry
    ),
    requireOrderConfirm:
      typeof storedPreferences.requireOrderConfirm === 'boolean'
        ? storedPreferences.requireOrderConfirm
        : defaultSettingsPreferences.requireOrderConfirm,
    riskAlerts:
      typeof storedPreferences.riskAlerts === 'boolean'
        ? storedPreferences.riskAlerts
        : defaultSettingsPreferences.riskAlerts,
    slippageAlert: pickStoredOption(
      storedPreferences.slippageAlert,
      slippageAlertOptions,
      defaultSettingsPreferences.slippageAlert
    ),
    tableDensity: pickStoredOption(
      storedPreferences.tableDensity,
      tableDensityOptions,
      defaultSettingsPreferences.tableDensity
    ),
    terminalLayout: pickStoredOption(
      storedPreferences.terminalLayout,
      terminalLayoutOptions,
      defaultSettingsPreferences.terminalLayout
    ),
    tradeNotifications: pickStoredOption(
      storedPreferences.tradeNotifications,
      tradeNotificationsOptions,
      defaultSettingsPreferences.tradeNotifications
    )
  }
}

export function saveSettingsPreferences(nextPreferences: SettingsPreferences) {
  try {
    window.localStorage.setItem(settingsStorageKey, JSON.stringify(nextPreferences))
  } catch {
    // Preferences remain available in memory when storage is unavailable.
  }
}

function pickStoredOption<T extends string>(value: unknown, options: readonly T[], fallback: T): T {
  return typeof value === 'string' && options.includes(value as T) ? (value as T) : fallback
}
