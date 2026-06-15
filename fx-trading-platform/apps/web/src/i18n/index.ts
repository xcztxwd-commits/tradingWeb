import i18next, { type TOptions } from 'i18next'
import { initReactI18next } from 'react-i18next'

import enUS from './locales/en-US.ts'
import jaJP from './locales/ja-JP.ts'
import zhCN from './locales/zh-CN.ts'

export const languageStorageKey = 'fx-trader-language'
export const supportedLanguages = ['zh-CN', 'en-US', 'ja-JP'] as const

export type SupportedLanguage = (typeof supportedLanguages)[number]

const languageAliases: Record<string, SupportedLanguage> = {
  zh: 'zh-CN',
  'zh-CN': 'zh-CN',
  en: 'en-US',
  'en-US': 'en-US',
  ja: 'ja-JP',
  'ja-JP': 'ja-JP'
}

const resources = {
  'zh-CN': { translation: zhCN },
  'en-US': { translation: enUS },
  'ja-JP': { translation: jaJP }
}

export function normalizeLanguage(language: string | null | undefined): SupportedLanguage | null {
  if (!language) return null

  const normalized = language.replace('_', '-')
  return languageAliases[normalized] ?? languageAliases[normalized.split('-')[0]] ?? null
}

function readStoredLanguage(): SupportedLanguage | null {
  if (typeof window === 'undefined') return null

  try {
    return normalizeLanguage(window.localStorage.getItem(languageStorageKey))
  } catch {
    return null
  }
}

function detectNavigatorLanguage(): SupportedLanguage | null {
  if (typeof navigator === 'undefined') return null

  const languages = navigator.languages?.length ? navigator.languages : [navigator.language]
  for (const language of languages) {
    const normalized = normalizeLanguage(language)
    if (normalized) return normalized
  }
  return null
}

export function getInitialLanguage(): SupportedLanguage {
  return readStoredLanguage() ?? detectNavigatorLanguage() ?? 'en-US'
}

function persistLanguage(language: SupportedLanguage) {
  if (typeof window === 'undefined') return

  try {
    window.localStorage.setItem(languageStorageKey, language)
  } catch {
    // Browser storage can be unavailable in privacy modes; language still changes for this session.
  }
}

export function syncDocumentLanguage(language: SupportedLanguage) {
  if (typeof document !== 'undefined') {
    document.documentElement.lang = language
  }
}

void i18next
  .use(initReactI18next)
  .init({
    resources,
    lng: getInitialLanguage(),
    fallbackLng: 'en-US',
    supportedLngs: supportedLanguages,
    interpolation: {
      escapeValue: false
    }
  })

i18next.on('languageChanged', (language) => {
  const normalized = normalizeLanguage(language) ?? 'en-US'
  persistLanguage(normalized)
  syncDocumentLanguage(normalized)
})

syncDocumentLanguage(normalizeLanguage(i18next.language) ?? getInitialLanguage())

export async function changeLanguage(language: SupportedLanguage) {
  persistLanguage(language)
  syncDocumentLanguage(language)
  await i18next.changeLanguage(language)
}

export function t(key: string, options?: TOptions): string {
  return i18next.t(key, options)
}

export { i18next }
