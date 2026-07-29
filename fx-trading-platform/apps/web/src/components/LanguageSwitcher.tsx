import { useId } from 'react'
import { useTranslation } from 'react-i18next'
import { SelectField, type SelectFieldOption } from '@fx-platform/ui'

import { changeLanguage, supportedLanguages, type SupportedLanguage } from '../i18n'
import { TopbarToolIcon } from './TopbarToolIcon'
import styles from './LanguageSwitcher.module.css'

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
    <div className={[styles.root, compact && styles.compact].filter(Boolean).join(' ')}>
      <span id={labelId} className={styles.label}>
        <TopbarToolIcon name="globe" size={compact ? 30 : 15} />
        <span className={styles.labelText}>{t('language.label')}</span>
      </span>
      <SelectField
        className={[styles.select, compact && styles.selectCompact].filter(Boolean).join(' ')}
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
