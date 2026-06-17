import { Activity, ArrowRight, LineChart, LockKeyhole, ShieldCheck } from 'lucide-react'
import type { FormEvent } from 'react'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'

import { login } from '../../services/authApi'
import { writeStoredAuthTokens } from '../../features/trading-session/tradingSessionStorage'
import styles from './LoginPage.module.css'

export function LoginPage() {
  const navigate = useNavigate()
  const { t } = useTranslation()
  const [searchParams] = useSearchParams()
  const redirectPath = useMemo(() => safeRedirectPath(searchParams.get('redirect')), [searchParams])
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setSubmitting(true)
    setError(null)

    try {
      const auth = await login(email.trim(), password)
      writeStoredAuthTokens(auth.accessToken, auth.refreshToken)
      writeStoredEmail(auth.email || email.trim())
      navigate(redirectPath, { replace: true })
    } catch (nextError) {
      setError(formatLoginError(nextError, t('auth.loginFailed')))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className={styles.page} aria-labelledby="login-title">
      <div className={styles.ambient} aria-hidden="true" />
      <div className={styles.shell}>
        <section className={styles.productIntro} aria-label={t('auth.productIntro')}>
          <div className={styles.brandMark}>
            <LineChart size={28} aria-hidden="true" />
          </div>
          <p className={styles.eyebrow}>FX Trader Pro</p>
          <h1 id="login-title">{t('auth.loginTitle')}</h1>
          <p className={styles.summary}>{t('auth.loginSummary')}</p>
          <div className={styles.signalGrid}>
            <span>
              <Activity size={17} aria-hidden="true" />
              {t('auth.realtimeMarket')}
            </span>
            <span>
              <ShieldCheck size={17} aria-hidden="true" />
              {t('auth.riskCheck')}
            </span>
            <span>
              <LockKeyhole size={17} aria-hidden="true" />
              {t('auth.accountIsolation')}
            </span>
          </div>
        </section>

        <form className={styles.form} onSubmit={handleSubmit}>
          <header className={styles.formHeader}>
            <h2>{t('auth.loginAccount')}</h2>
            <p>{t('auth.enterExecution')}</p>
          </header>

          <label className={styles.field}>
            <span>{t('auth.email')}</span>
            <input
              autoComplete="email"
              inputMode="text"
              type="text"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
            />
          </label>

          <label className={styles.field}>
            <span>{t('auth.password')}</span>
            <input
              autoComplete="current-password"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
            />
          </label>

          {error ? (
            <p className={styles.error} role="alert">
              {error}
            </p>
          ) : null}

          <button className={styles.submit} disabled={submitting} type="submit">
            {submitting ? t('auth.loginSubmitting') : t('auth.loginSubmit')}
            <ArrowRight size={17} aria-hidden="true" />
          </button>

          <nav className={styles.formLinks} aria-label={t('auth.loginHelp')}>
            <Link to="/register">{t('auth.register')}</Link>
            <Link to="/forgot-password">{t('auth.forgotPassword')}</Link>
            <Link to="/two-factor-help">{t('auth.twoFactorHelp')}</Link>
          </nav>
        </form>
      </div>
    </section>
  )
}

function safeRedirectPath(value: string | null) {
  if (!value || !value.startsWith('/') || value.startsWith('//')) return '/account/overview'
  return value
}

function formatLoginError(error: unknown, fallback: string) {
  if (error instanceof Error && error.message) return error.message
  if (typeof error === 'string' && error) return error
  return fallback
}

function writeStoredEmail(email: string) {
  try {
    globalThis.localStorage?.setItem('fx-platform-user-email', email)
  } catch {
    // The account menu can fall back when localStorage is unavailable.
  }
}
