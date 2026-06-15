import { Languages } from 'lucide-react'
import { useId } from 'react'
import { useTranslation } from 'react-i18next'

import { changeLanguage, supportedLanguages, type SupportedLanguage } from '../i18n'
import { SelectField, type SelectFieldOption } from './SelectField'

const languageLabelKeys: Record<SupportedLanguage, string> = {
  'zh-CN': 'language.zhCN',
  'en-US': 'language.enUS',
  'ja-JP': 'language.jaJP'
}

export function LanguageSwitcher({ compact = false }: { compact?: boolean }) {
  const { i18n, t } = useTranslation()
  const labelId = useId()
  const activeLanguage = supportedLanguages.includes(i18n.language as SupportedLanguage) ? (i18n.language as SupportedLanguage) : 'en-US'
  const languageOptions: Array<SelectFieldOption<SupportedLanguage>> = supportedLanguages.map((language) => ({
    label: t(languageLabelKeys[language]),
    value: language
  }))

  return (
    <div className={`language-switcher${compact ? ' language-switcher--compact' : ''}`}>
      <span id={labelId} className="language-switcher__label">
        <Languages size={15} aria-hidden="true" />
        {t('language.label')}
      </span>
      <SelectField
        ariaLabel={t('language.label')}
        labelledBy={labelId}
        value={activeLanguage}
        options={languageOptions}
        onChange={(language) => {
          void changeLanguage(language)
        }}
      />
    </div>
  )
}
