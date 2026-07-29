import { Activity, ArrowLeft, ArrowRight, KeyRound, LineChart, LockKeyhole, Mail, ShieldCheck, Smartphone } from 'lucide-react'
import type { FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'

import type { DeviceClass } from '../../app/device/deviceClass'
import type { AuthViewMode, AuthViewModel } from './authView.types'
import styles from './AuthPageContent.module.css'

type AuthPageContentProps = {
  expectedMode: AuthViewMode
  model: AuthViewModel
  platform: DeviceClass
}

export function AuthPageContent({ expectedMode, model, platform }: AuthPageContentProps) {
  if (model.mode !== expectedMode) return null
  if (model.mode === 'login') return <LoginContent model={model} platform={platform} />
  if (model.mode === 'register') return <RegisterContent model={model} platform={platform} />
  return <SupportContent model={model} platform={platform} />
}

function LoginContent({ model, platform }: { model: AuthViewModel; platform: DeviceClass }) {
  const { t } = useTranslation()
  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    void model.submit()
  }

  return (
    <section className={pageClassName(platform)} aria-labelledby="login-title">
      <div className={styles.ambient} aria-hidden="true" />
      <div className={styles.shell}>
        <section className={styles.productIntro} aria-label={t('auth.productIntro')}>
          <div className={styles.brandMark}><LineChart size={28} aria-hidden="true" /></div>
          <p className={styles.eyebrow}>FX Trader Pro</p>
          <h1 id="login-title">{t('auth.loginTitle')}</h1>
          <p className={styles.summary}>{t('auth.loginSummary')}</p>
          <div className={styles.signalGrid}>
            <span><Activity size={17} aria-hidden="true" />{t('auth.realtimeMarket')}</span>
            <span><ShieldCheck size={17} aria-hidden="true" />{t('auth.riskCheck')}</span>
            <span><LockKeyhole size={17} aria-hidden="true" />{t('auth.accountIsolation')}</span>
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
              value={model.email}
              onChange={(event) => model.setEmail(event.target.value)}
            />
          </label>
          <label className={styles.field}>
            <span>{t('auth.password')}</span>
            <input
              autoComplete="current-password"
              type="password"
              value={model.password}
              onChange={(event) => model.setPassword(event.target.value)}
            />
          </label>
          {model.error !== null ? <p className={styles.error} role="alert">{model.error || t('auth.loginFailed')}</p> : null}
          <button className={styles.submit} disabled={model.submitting} type="submit">
            {model.submitting ? t('auth.loginSubmitting') : t('auth.loginSubmit')}
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

function RegisterContent({ model, platform }: { model: AuthViewModel; platform: DeviceClass }) {
  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    void model.submit()
  }

  return (
    <section className={pageClassName(platform)} aria-labelledby="register-title">
      <div className={styles.ambient} aria-hidden="true" />
      <div className={`${styles.shell} ${styles.supportShell}`}>
        <section className={styles.productIntro} aria-label="账户创建">
          <div className={styles.brandMark}><ShieldCheck size={28} aria-hidden="true" /></div>
          <p className={styles.eyebrow}>新用户奖励</p>
          <h1 id="register-title">创建 FX Trader 账户</h1>
          <p className={styles.summary}>用任意邮箱或手机号标识创建账户。提交后直接写入账户数据库并进入账户中心。</p>
          <div className={styles.signalGrid}>
            <span><Mail size={17} aria-hidden="true" />邮箱注册</span>
            <span><Smartphone size={17} aria-hidden="true" />手机号</span>
            <span><ShieldCheck size={17} aria-hidden="true" />KYC 优先</span>
          </div>
        </section>

        <form className={styles.form} onSubmit={handleSubmit}>
          <header className={styles.formHeader}>
            <h2>创建账户</h2>
            <p>输入登录标识和密码后直接注册。</p>
          </header>
          <div className={styles.segmentedControl} aria-label="注册方式">
            <button type="button" aria-pressed={model.channel === 'email'} onClick={() => model.setChannel('email')}>邮箱</button>
            <button type="button" aria-pressed={model.channel === 'phone'} onClick={() => model.setChannel('phone')}>手机号</button>
          </div>
          <label className={styles.field}>
            <span>{model.channel === 'email' ? '邮箱' : '手机号'}</span>
            <span className={styles.phoneField}>
              {model.channel === 'phone' ? (
                <select value={model.countryCode} onChange={(event) => model.setCountryCode(event.target.value)} aria-label="国家区号">
                  <option value="+60">+60</option>
                  <option value="+86">+86</option>
                  <option value="+65">+65</option>
                </select>
              ) : null}
              <input
                autoComplete={model.channel === 'email' ? 'email' : 'tel'}
                inputMode={model.channel === 'email' ? 'text' : 'tel'}
                type={model.channel === 'email' ? 'text' : 'tel'}
                value={model.identifier}
                onChange={(event) => model.setIdentifier(event.target.value)}
              />
            </span>
          </label>
          <label className={styles.field}>
            <span>登录密码</span>
            <input autoComplete="new-password" type="password" value={model.password} onChange={(event) => model.setPassword(event.target.value)} />
          </label>
          {model.error !== null ? <p className={styles.error} role="alert">{model.error || '操作失败，请稍后重试'}</p> : null}
          <button className={styles.submit} disabled={model.submitting} type="submit">
            {model.submitting ? '处理中' : '直接注册'}
            <ArrowRight size={17} aria-hidden="true" />
          </button>
          <BackToLogin />
        </form>
      </div>
    </section>
  )
}

function SupportContent({ model, platform }: { model: AuthViewModel; platform: DeviceClass }) {
  const title = model.mode === 'forgot-password' ? '找回密码' : '2FA 验证说明'
  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    model.confirmSupport()
  }

  return (
    <section className={pageClassName(platform)} aria-labelledby={`${model.mode}-title`}>
      <div className={styles.ambient} aria-hidden="true" />
      <div className={`${styles.shell} ${styles.supportShell}`}>
        <section className={styles.productIntro} aria-label={title}>
          <div className={styles.brandMark}><KeyRound size={28} aria-hidden="true" /></div>
          <p className={styles.eyebrow}>账户安全</p>
          <h1 id={`${model.mode}-title`}>{title}</h1>
          <p className={styles.summary}>确认账户信息后继续安全流程。页面保持轻量，不额外引入未设计的账户模块。</p>
        </section>
        <form className={styles.form} onSubmit={handleSubmit}>
          <header className={styles.formHeader}>
            <h2>{title}</h2>
            <p>输入邮箱并确认下一步。</p>
          </header>
          <label className={styles.field}>
            <span>邮箱</span>
            <input
              autoComplete="email"
              inputMode="email"
              required
              type="email"
              value={model.email}
              onChange={(event) => model.setEmail(event.target.value)}
            />
          </label>
          {model.confirmed ? <div className={styles.success} role="status" aria-live="polite">已确认，请继续按账户安全提示操作。</div> : null}
          <button className={styles.submit} type="submit">继续<ArrowRight size={17} aria-hidden="true" /></button>
          <BackToLogin />
        </form>
      </div>
    </section>
  )
}

function BackToLogin() {
  return <Link className={styles.backLink} to="/login"><ArrowLeft size={16} aria-hidden="true" />返回登录</Link>
}

function pageClassName(platform: DeviceClass) {
  return `${styles.page}${platform === 'mobile' ? ` ${styles.mobile}` : ''}`
}
