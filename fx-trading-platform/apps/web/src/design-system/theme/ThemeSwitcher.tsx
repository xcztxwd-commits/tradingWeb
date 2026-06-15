import type { CSSProperties } from 'react'
import { useTranslation } from 'react-i18next'

import { tradingThemes } from './themes'
import { useTheme } from './ThemeProvider'
import styles from './ThemeSwitcher.module.css'

export function ThemeSwitcher() {
  const { t } = useTranslation()
  const { themeId, setThemeId } = useTheme()

  return (
    <section className={styles.panel} aria-label={t('settings.themeSwitcherAria')}>
      <header className={styles.header}>
        <strong>{t('settings.theme')}</strong>
        <span>{t('settings.themeSwitcherSubtitle')}</span>
      </header>
      <div className={styles.grid}>
        {tradingThemes.map((theme) => (
          <button
            key={theme.id}
            type="button"
            className={styles.option}
            aria-label={t('settings.switchTheme', { name: theme.name })}
            aria-pressed={theme.id === themeId}
            style={{ '--theme-swatch-primary': theme.tokens.primary, '--theme-swatch-surface': theme.tokens.surface } as CSSProperties}
            onClick={() => setThemeId(theme.id)}
          >
            <span className={styles.swatch} aria-hidden="true">
              <span />
            </span>
            <span className={styles.copy}>
              <strong>{theme.name}</strong>
              <small>{t(theme.description)}</small>
            </span>
          </button>
        ))}
      </div>
    </section>
  )
}
