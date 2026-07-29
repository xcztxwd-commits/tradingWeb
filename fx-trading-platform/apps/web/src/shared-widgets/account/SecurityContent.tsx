import { CheckCircle2, Clock3, KeyRound, Laptop, LockKeyhole, ShieldAlert, ShieldCheck, Smartphone, WalletCards } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'

import { cssModuleClasses as css } from '../data/cssModuleClasses'
import surfaceStyles from '../data/UserPageSurface.module.css'
import accountStyles from './AccountPagesContent.module.css'

const styles = { ...surfaceStyles, ...accountStyles }

const securityItems = [
  {
    icon: LockKeyhole,
    titleKey: 'security.items.loginPassword.title',
    statusKey: 'security.status.enabled',
    descriptionKey: 'security.items.loginPassword.description',
    actionKey: 'security.items.loginPassword.action',
    href: '/forgot-password',
    tone: 'ready'
  },
  {
    icon: Smartphone,
    titleKey: 'security.items.twoFactor.title',
    statusKey: 'security.status.soon',
    descriptionKey: 'security.items.twoFactor.description',
    actionKey: 'security.items.twoFactor.action',
    href: '/two-factor-help',
    tone: 'soon'
  },
  {
    icon: Laptop,
    titleKey: 'security.items.deviceManagement.title',
    statusKey: 'security.status.soon',
    descriptionKey: 'security.items.deviceManagement.description',
    actionKey: 'security.items.deviceManagement.action',
    disabledReasonKey: 'security.items.deviceManagement.disabledReason',
    tone: 'soon'
  },
  {
    icon: Clock3,
    titleKey: 'security.items.loginHistory.title',
    statusKey: 'security.status.preview',
    descriptionKey: 'security.items.loginHistory.description',
    actionKey: 'security.items.loginHistory.action',
    href: '#security-login-history',
    tone: 'preview'
  },
  {
    icon: WalletCards,
    titleKey: 'security.items.withdrawalWhitelist.title',
    statusKey: 'security.status.soon',
    descriptionKey: 'security.items.withdrawalWhitelist.description',
    actionKey: 'security.items.withdrawalWhitelist.action',
    disabledReasonKey: 'security.items.withdrawalWhitelist.disabledReason',
    tone: 'soon'
  }
]

const loginHistory = [
  { time: '2026-06-13 09:42', device: 'Windows / Chrome', location: 'Kuala Lumpur', statusKey: 'security.loginHistory.currentSession' },
  { time: '2026-06-12 20:16', device: 'Windows / Chrome', location: 'Kuala Lumpur', statusKey: 'security.loginHistory.normal' },
  { time: '2026-06-11 18:33', device: 'Mobile Safari', location: 'Singapore', statusKey: 'security.loginHistory.normal' }
]

export function SecurityContent() {
  const { t } = useTranslation()

  return (
    <section className={css(styles, "user-page")} aria-labelledby="security-title">
      <header className={css(styles, "user-page__header")}>
        <div>
          <span>{t('security.eyebrow')}</span>
          <h1 id="security-title">{t('security.title')}</h1>
          <p>{t('security.centerSummary')}</p>
        </div>
        <Link className={css(styles, "table-action", "table-action--secondary")} to="/settings">
          {t('security.backToSettings')}
        </Link>
      </header>

      <div className={css(styles, "user-page__metrics")}>
        <div className={css(styles, "metric")}>
          <span>{t('security.level')}</span>
          <strong>{t('security.standardProtection')}</strong>
        </div>
        <div className={css(styles, "metric")}>
          <span>{t('security.enabledItems')}</span>
          <strong>1 / 5</strong>
        </div>
        <div className={css(styles, "metric")}>
          <span>{t('security.pendingItems')}</span>
          <strong>2FA, {t('security.deviceManagement')}, {t('security.whitelistShort')}</strong>
        </div>
      </div>

      <div className={css(styles, "settings-grid", "settings-grid--security")}>
        {securityItems.map((item) => {
          const reasonId = `${item.titleKey.replaceAll('.', '-')}-disabled-reason`
          const disabledReason = item.disabledReasonKey ? t(item.disabledReasonKey) : undefined

          return (
            <article className={css(styles, "settings-card")} key={item.titleKey}>
              <div className={css(styles, "settings-card__icon")} aria-hidden="true">
                <item.icon size={20} />
              </div>
              <div className={css(styles, "settings-card__body")}>
                <div className={css(styles, "settings-card__title-row")}>
                  <h2>{t(item.titleKey)}</h2>
                  <StatusBadge tone={item.tone}>{t(item.statusKey)}</StatusBadge>
                </div>
                <p>{t(item.descriptionKey)}</p>
                {disabledReason ? (
                  <p className={css(styles, "settings-card__note")} id={reasonId}>
                    {disabledReason}
                  </p>
                ) : null}
                {item.href ? (
                  <Link className={css(styles, "settings-card__link")} to={item.href}>
                    {t(item.actionKey)}
                  </Link>
                ) : (
                  <button
                    className={css(styles, "settings-card__link")}
                    type="button"
                    disabled
                    aria-describedby={reasonId}
                    title={disabledReason}
                  >
                    {t('security.actionUnavailable')}
                  </button>
                )}
              </div>
            </article>
          )
        })}
      </div>

      <section className={css(styles, "user-page__events")} id="security-login-history">
        <div className={css(styles, "settings-section-head")}>
          <div>
            <h2>{t('security.loginHistory.title')}</h2>
            <p>{t('security.loginHistory.description')}</p>
          </div>
          <ShieldCheck size={20} aria-hidden="true" />
        </div>
        <div>
          {loginHistory.map((entry) => (
            <div key={`${entry.time}-${entry.device}`}>
              <CheckCircle2 size={18} aria-hidden="true" />
              <div>
                <strong>{entry.device}</strong>
                <span>{entry.time} · {entry.location}</span>
              </div>
              <StatusBadge tone="preview">{t(entry.statusKey)}</StatusBadge>
            </div>
          ))}
        </div>
      </section>

      <section className={css(styles, "user-page__events")} id="security-withdrawal-whitelist">
        <div className={css(styles, "settings-section-head")}>
          <div>
            <h2>{t('security.withdrawalWhitelist')}</h2>
            <p>{t('security.withdrawalWhitelistDescription')}</p>
          </div>
          <ShieldAlert size={20} aria-hidden="true" />
        </div>
        <div className={css(styles, "action-card-grid")}>
          <Link className={css(styles, "action-card")} to="/wallet">
            <KeyRound size={20} aria-hidden="true" />
            <strong>{t('assets.addressBookEntry')}</strong>
            <span>{t('security.addressBookWalletHint')}</span>
          </Link>
        </div>
      </section>
    </section>
  )
}

function StatusBadge({ children, tone }: { children: string; tone: string }) {
  return <span className={css(styles, 'status-chip', `status-chip--${tone}`)}>{children}</span>
}
