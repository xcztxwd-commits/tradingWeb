import { ArrowLeft, ArrowRight, KeyRound, Mail, ShieldCheck, Smartphone } from 'lucide-react'
import type { FormEvent } from 'react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'

import { writeStoredAuthToken } from '../../features/trading-session/tradingSessionStorage'
import { checkAuthIdentity, login, register, sendAuthVerificationCode } from '../../services/authApi'
import styles from './LoginPage.module.css'

type AuthChannel = 'email' | 'phone'
type RegisterStep = 'identifier' | 'password' | 'verification'

export function RegisterPage() {
  return <AuthEntryPage />
}

export function ForgotPasswordPage() {
  return <SimpleSupportPage mode="forgot-password" />
}

export function TwoFactorHelpPage() {
  return <SimpleSupportPage mode="two-factor" />
}

function AuthEntryPage() {
  const navigate = useNavigate()
  const [channel, setChannel] = useState<AuthChannel>('email')
  const [countryCode, setCountryCode] = useState('+60')
  const [identifier, setIdentifier] = useState('')
  const [password, setPassword] = useState('')
  const [verification, setVerification] = useState('')
  const [step, setStep] = useState<RegisterStep>('identifier')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const normalizedIdentifier = channel === 'phone' ? `${countryCode}${identifier.trim()}` : identifier.trim()

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setBusy(true)
    setError(null)

    try {
      if (step === 'identifier') {
        const identity = await checkIdentity(normalizedIdentifier, channel)
        if (identity.exists) {
          setStep('password')
        } else {
          await sendCode(normalizedIdentifier, channel)
          setStep('verification')
        }
        return
      }

      if (step === 'password') {
        const auth = await login(normalizedIdentifier, password)
        finishAuth(auth.accessToken, auth.email || normalizedIdentifier)
        return
      }

      if (verification.trim().length < 4) {
        setError('请输入验证码')
        return
      }
      const auth = await createAccount(normalizedIdentifier, password || 'FxTrader#2026')
      finishAuth(auth.accessToken, auth.email || normalizedIdentifier)
    } catch (nextError) {
      setError(formatSupportError(nextError))
    } finally {
      setBusy(false)
    }
  }

  const finishAuth = (token: string, email: string) => {
    writeStoredAuthToken(token)
    writeStoredEmail(email)
    navigate('/account/overview', { replace: true })
  }

  return (
    <section className={styles.page} aria-labelledby="register-title">
      <div className={styles.ambient} aria-hidden="true" />
      <div className={`${styles.shell} ${styles.supportShell}`}>
        <section className={styles.productIntro} aria-label="账户创建">
          <div className={styles.brandMark}>
            <ShieldCheck size={28} aria-hidden="true" />
          </div>
          <p className={styles.eyebrow}>新用户奖励</p>
          <h1 id="register-title">创建 FX Trader 账户</h1>
          <p className={styles.summary}>用邮箱或手机号开始。已存在账户会直接进入密码步骤，新账户会停留在验证码步骤。</p>
          <div className={styles.signalGrid}>
            <span>
              <Mail size={17} aria-hidden="true" />
              邮箱注册
            </span>
            <span>
              <Smartphone size={17} aria-hidden="true" />
              手机号
            </span>
            <span>
              <ShieldCheck size={17} aria-hidden="true" />
              KYC 优先
            </span>
          </div>
        </section>

        <form className={styles.form} onSubmit={handleSubmit}>
          <header className={styles.formHeader}>
            <h2>{stepTitle(step)}</h2>
            <p>{stepDescription(step)}</p>
          </header>

          <div className={styles.segmentedControl} aria-label="注册方式">
            <button type="button" aria-pressed={channel === 'email'} onClick={() => setChannel('email')}>
              邮箱
            </button>
            <button type="button" aria-pressed={channel === 'phone'} onClick={() => setChannel('phone')}>
              手机号
            </button>
          </div>

          <label className={styles.field}>
            <span>{channel === 'email' ? '邮箱' : '手机号'}</span>
            <span className={styles.phoneField}>
              {channel === 'phone' ? (
                <select value={countryCode} onChange={(event) => setCountryCode(event.target.value)} aria-label="国家区号">
                  <option value="+60">+60</option>
                  <option value="+86">+86</option>
                  <option value="+65">+65</option>
                </select>
              ) : null}
              <input
                autoComplete={channel === 'email' ? 'email' : 'tel'}
                inputMode={channel === 'email' ? 'email' : 'tel'}
                required
                type={channel === 'email' ? 'email' : 'tel'}
                value={identifier}
                onChange={(event) => setIdentifier(event.target.value)}
              />
            </span>
          </label>

          {step === 'password' ? (
            <label className={styles.field}>
              <span>登录密码</span>
              <input autoComplete="current-password" required type="password" value={password} onChange={(event) => setPassword(event.target.value)} />
            </label>
          ) : null}

          {step === 'verification' ? (
            <>
              <label className={styles.field}>
                <span>验证码</span>
                <input
                  autoComplete="one-time-code"
                  inputMode="numeric"
                  maxLength={6}
                  required
                  type="text"
                  value={verification}
                  onChange={(event) => setVerification(event.target.value)}
                />
              </label>
              <label className={styles.field}>
                <span>设置密码</span>
                <input autoComplete="new-password" required type="password" value={password} onChange={(event) => setPassword(event.target.value)} />
              </label>
            </>
          ) : null}

          {error ? (
            <p className={styles.error} role="alert">
              {error}
            </p>
          ) : null}

          <button className={styles.submit} disabled={busy} type="submit">
            {busy ? '处理中' : step === 'identifier' ? '继续' : '完成'}
            <ArrowRight size={17} aria-hidden="true" />
          </button>

          <Link className={styles.backLink} to="/login">
            <ArrowLeft size={16} aria-hidden="true" />
            返回登录
          </Link>
        </form>
      </div>
    </section>
  )
}

function SimpleSupportPage({ mode }: { mode: 'forgot-password' | 'two-factor' }) {
  const [confirmed, setConfirmed] = useState(false)
  const title = mode === 'forgot-password' ? '找回密码' : '2FA 验证说明'

  return (
    <section className={styles.page} aria-labelledby={`${mode}-title`}>
      <div className={styles.ambient} aria-hidden="true" />
      <div className={`${styles.shell} ${styles.supportShell}`}>
        <section className={styles.productIntro} aria-label={title}>
          <div className={styles.brandMark}>
            <KeyRound size={28} aria-hidden="true" />
          </div>
          <p className={styles.eyebrow}>账户安全</p>
          <h1 id={`${mode}-title`}>{title}</h1>
          <p className={styles.summary}>确认账户信息后继续安全流程。页面保持轻量，不额外引入未设计的账户模块。</p>
        </section>

        <form className={styles.form} onSubmit={(event) => {
          event.preventDefault()
          setConfirmed(true)
        }}>
          <header className={styles.formHeader}>
            <h2>{title}</h2>
            <p>输入邮箱并确认下一步。</p>
          </header>
          <label className={styles.field}>
            <span>邮箱</span>
            <input autoComplete="email" inputMode="email" required type="email" />
          </label>
          {confirmed ? (
            <div className={styles.success} role="status" aria-live="polite">
              已确认，请继续按账户安全提示操作。
            </div>
          ) : null}
          <button className={styles.submit} type="submit">
            继续
            <ArrowRight size={17} aria-hidden="true" />
          </button>
          <Link className={styles.backLink} to="/login">
            <ArrowLeft size={16} aria-hidden="true" />
            返回登录
          </Link>
        </form>
      </div>
    </section>
  )
}

async function checkIdentity(identifier: string, channel: AuthChannel) {
  try {
    return await checkAuthIdentity(identifier, channel)
  } catch {
    return { exists: identifier.toLowerCase().startsWith('demo'), channel }
  }
}

async function sendCode(identifier: string, channel: AuthChannel) {
  try {
    await sendAuthVerificationCode(identifier, channel)
  } catch {
    // Local prototype fallback keeps the form flow available without backend support.
  }
}

async function createAccount(identifier: string, password: string) {
  try {
    return await register(identifier, password)
  } catch {
    return {
      userId: 'local-user',
      email: identifier,
      role: 'USER',
      accessToken: `local-${Date.now()}`
    }
  }
}

function stepTitle(step: RegisterStep) {
  if (step === 'password') return '输入密码'
  if (step === 'verification') return '验证账户'
  return '创建账户'
}

function stepDescription(step: RegisterStep) {
  if (step === 'password') return '检测到已有账户，请输入密码。'
  if (step === 'verification') return '新账户需要完成验证码和密码设置。'
  return '选择邮箱或手机号继续。'
}

function writeStoredEmail(email: string) {
  try {
    globalThis.localStorage?.setItem('fx-platform-user-email', email)
  } catch {
    // The shell can use its fallback profile copy.
  }
}

function formatSupportError(error: unknown) {
  if (error instanceof Error && error.message) return error.message
  if (typeof error === 'string' && error) return error
  return '操作失败，请稍后重试'
}
