import { Bell, CandlestickChart, Eye, LayoutTemplate, LockKeyhole, MonitorCog, ShieldCheck } from 'lucide-react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'

import { LanguageSwitcher } from '../../components/LanguageSwitcher'
import type { AccountRouteModel } from '../../routes/account/accountRoute.types'
import { cssModuleClasses as css } from '../data/cssModuleClasses'
import {
  accountPanelFocusOptions,
  defaultOrderTypeOptions,
  loginAlertModeOptions,
  mobileTableModeOptions,
  numberFontOptions,
  pnlDisplayOptions,
  quickEntryOptions,
  slippageAlertOptions,
  tableDensityOptions,
  terminalLayoutOptions,
  tradeNotificationsOptions
} from '../../routes/account/settingsPreferences'
import styles from './AccountSettingsContent.module.css'
import surfaceStyles from '../data/UserPageSurface.module.css'

export function SettingsContent({ model }: { model: AccountRouteModel }) {
  const { t } = useTranslation()
  const { preferences, saveNoticeId, updatePreference } = model.settings

  return (
    <section className={`${styles.page} ${surfaceStyles['user-page']}`} aria-labelledby="settings-title">
      <header className={`${styles.header} ${surfaceStyles['user-page__header']}`}>
        <div>
          <span>{t('settings.preferenceSettings')}</span>
          <h1 id="settings-title">{t('settings.center')}</h1>
          <p>{t('settings.centerSummary')}</p>
        </div>
        <Link className={css(surfaceStyles, "table-action", "table-action--primary")} to="/security">
          {t('security.title')}
        </Link>
      </header>

      <p key={saveNoticeId} className={styles.persistHint} role="status" aria-live="polite">
        {t('settings.preferencesSaved')}
      </p>

      <div className={styles.settingsGrid}>
        <SettingsSurface
          icon={<CandlestickChart size={20} aria-hidden="true" />}
          title={t('settings.tradingPreference')}
          description={t('settings.tradingPreferenceDescription')}
        >
          <div className={styles.preferenceControls}>
            <PreferenceSelect
              label={t('settings.defaultOrderType')}
              value={preferences.defaultOrderType}
              options={defaultOrderTypeOptions}
              getOptionLabel={(option) => option}
              onChange={(value) => updatePreference('defaultOrderType', value)}
            />
            <PreferenceToggle
              label={t('settings.orderConfirmation')}
              description={t('settings.orderConfirmationDescription')}
              checked={preferences.requireOrderConfirm}
              onChange={(value) => updatePreference('requireOrderConfirm', value)}
            />
            <PreferenceToggle
              label={t('settings.priceProtection')}
              description={t('settings.priceProtectionDescription')}
              checked={preferences.priceProtection}
              onChange={(value) => updatePreference('priceProtection', value)}
            />
            <PreferenceSegmentedControl
              label={t('settings.slippageAlert')}
              value={preferences.slippageAlert}
              options={slippageAlertOptions}
              getOptionLabel={(option) => option}
              onChange={(value) => updatePreference('slippageAlert', value)}
            />
          </div>
        </SettingsSurface>

        <SettingsSurface
          icon={<Eye size={20} aria-hidden="true" />}
          title={t('settings.displayPreference')}
          description={t('settings.displayPreferenceDescription')}
        >
          <LanguageSwitcher />
          <div className={styles.preferenceControls}>
            <PreferenceSegmentedControl
              label={t('settings.tableDensity')}
              value={preferences.tableDensity}
              options={tableDensityOptions}
              getOptionLabel={(option) => t(`settings.options.tableDensity.${option}`)}
              onChange={(value) => updatePreference('tableDensity', value)}
            />
            <PreferenceSegmentedControl
              label={t('settings.pnlDisplay')}
              value={preferences.pnlDisplay}
              options={pnlDisplayOptions}
              getOptionLabel={(option) => t(`settings.options.pnlDisplay.${option}`)}
              onChange={(value) => updatePreference('pnlDisplay', value)}
            />
            <PreferenceSelect
              label={t('settings.numberFont')}
              value={preferences.numberFont}
              options={numberFontOptions}
              getOptionLabel={(option) => option}
              onChange={(value) => updatePreference('numberFont', value)}
            />
          </div>
        </SettingsSurface>

        <SettingsSurface
          icon={<Bell size={20} aria-hidden="true" />}
          title={t('settings.notificationPreference')}
          description={t('settings.notificationPreferenceDescription')}
        >
          <div className={styles.preferenceControls}>
            <PreferenceSelect
              label={t('settings.tradeNotifications')}
              value={preferences.tradeNotifications}
              options={tradeNotificationsOptions}
              getOptionLabel={(option) => t(`settings.options.tradeNotifications.${option}`)}
              onChange={(value) => updatePreference('tradeNotifications', value)}
            />
            <PreferenceToggle
              label={t('settings.riskAlerts')}
              description={t('settings.riskAlertsDescription')}
              checked={preferences.riskAlerts}
              onChange={(value) => updatePreference('riskAlerts', value)}
            />
            <PreferenceSelect
              label={t('settings.loginAlert')}
              value={preferences.loginAlertMode}
              options={loginAlertModeOptions}
              getOptionLabel={(option) => t(`settings.options.loginAlert.${option}`)}
              onChange={(value) => updatePreference('loginAlertMode', value)}
            />
            <PreferenceToggle
              label={t('settings.fundReview')}
              description={t('settings.fundReviewDescription')}
              checked={preferences.fundReviewNotifications}
              onChange={(value) => updatePreference('fundReviewNotifications', value)}
            />
          </div>
        </SettingsSurface>

        <SettingsSurface
          icon={<LockKeyhole size={20} aria-hidden="true" />}
          title={t('settings.securityEntry')}
          description={t('settings.securityEntryDescription')}
        >
          <div className={styles.securityEntry}>
            <ShieldCheck size={24} aria-hidden="true" />
            <div>
              <strong>{t('settings.accountProtectionStatus')}</strong>
              <span>{t('settings.accountProtectionDescription')}</span>
            </div>
            <Link className={css(surfaceStyles, "table-action", "table-action--secondary")} to="/security">
              {t('assets.viewSecurityCenter')}
            </Link>
          </div>
        </SettingsSurface>

        <SettingsSurface
          icon={<LayoutTemplate size={20} aria-hidden="true" />}
          title={t('settings.layoutPreference')}
          description={t('settings.layoutPreferenceDescription')}
        >
          <div className={styles.preferenceControls}>
            <PreferenceSegmentedControl
              label={t('settings.terminalLayout')}
              value={preferences.terminalLayout}
              options={terminalLayoutOptions}
              getOptionLabel={(option) => t(`settings.options.terminalLayout.${option}`)}
              onChange={(value) => updatePreference('terminalLayout', value)}
            />
            <PreferenceSelect
              label={t('settings.bottomAccountPanel')}
              value={preferences.accountPanelFocus}
              options={accountPanelFocusOptions}
              getOptionLabel={(option) => t(`settings.options.accountPanelFocus.${option}`)}
              onChange={(value) => updatePreference('accountPanelFocus', value)}
            />
            <PreferenceSegmentedControl
              label={t('settings.mobileTable')}
              value={preferences.mobileTableMode}
              options={mobileTableModeOptions}
              getOptionLabel={(option) => t(`settings.options.mobileTable.${option}`)}
              onChange={(value) => updatePreference('mobileTableMode', value)}
            />
            <PreferenceSelect
              label={t('settings.quickEntry')}
              value={preferences.quickEntry}
              options={quickEntryOptions}
              getOptionLabel={(option) => t(`settings.options.quickEntry.${option}`)}
              onChange={(value) => updatePreference('quickEntry', value)}
            />
          </div>
        </SettingsSurface>

        <SettingsSurface
          icon={<MonitorCog size={20} aria-hidden="true" />}
          title={t('settings.pageEntries')}
          description={t('settings.pageEntriesDescription')}
        >
          <div className={styles.linkGrid}>
            <Link to="/wallet">{t('assets.walletTitle')}</Link>
            <Link to="/security">{t('security.title')}</Link>
            <Link to="/register">{t('auth.registerEntry')}</Link>
            <Link to="/forgot-password">{t('auth.forgotPassword')}</Link>
            <Link to="/two-factor-help">{t('auth.twoFactorHelp')}</Link>
          </div>
        </SettingsSurface>
      </div>
    </section>
  )
}
function SettingsSurface({
  children,
  description,
  icon,
  title
}: {
  children: ReactNode
  description: string
  icon: ReactNode
  title: string
}) {
  return (
    <section className={styles.surface}>
      <div className={styles.surfaceHeader}>
        <div className={styles.surfaceIcon}>{icon}</div>
        <div>
          <h2>{title}</h2>
          <p>{description}</p>
        </div>
      </div>
      {children}
    </section>
  )
}

function PreferenceSelect<T extends string>({
  getOptionLabel,
  label,
  onChange,
  options,
  value
}: {
  getOptionLabel: (option: T) => string
  label: string
  onChange: (value: T) => void
  options: readonly T[]
  value: T
}) {
  return (
    <label className={styles.selectControl}>
      <span>{label}</span>
      <select value={value} onChange={(event) => onChange(event.target.value as T)}>
        {options.map((option) => (
          <option key={option} value={option}>
            {getOptionLabel(option)}
          </option>
        ))}
      </select>
    </label>
  )
}

function PreferenceToggle({
  checked,
  description,
  label,
  onChange
}: {
  checked: boolean
  description: string
  label: string
  onChange: (value: boolean) => void
}) {
  return (
    <label className={styles.switchControl}>
      <input type="checkbox" checked={checked} onChange={(event) => onChange(event.target.checked)} />
      <span className={styles.switchVisual} aria-hidden="true" />
      <span>
        <strong>{label}</strong>
        <small>{description}</small>
      </span>
    </label>
  )
}

function PreferenceSegmentedControl<T extends string>({
  getOptionLabel,
  label,
  onChange,
  options,
  value
}: {
  getOptionLabel: (option: T) => string
  label: string
  onChange: (value: T) => void
  options: readonly T[]
  value: T
}) {
  return (
    <fieldset className={styles.segmentedControl}>
      <legend>{label}</legend>
      <div className={styles.segmentGroup}>
        {options.map((option) => (
          <button
            key={option}
            type="button"
            className={option === value ? styles.segmentActive : undefined}
            aria-pressed={option === value}
            onClick={() => onChange(option)}
          >
            {getOptionLabel(option)}
          </button>
        ))}
      </div>
    </fieldset>
  )
}
