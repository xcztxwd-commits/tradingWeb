import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'

import { login } from '../services/authApi'
import { setAdminAuthTokens } from '../services/adminToken'

type RedirectState = {
  from?: {
    pathname?: string
  }
}

export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const submitLogin = async (event: React.FormEvent) => {
    event.preventDefault()
    setLoading(true)
    setError('')
    try {
      const auth = await login(email, password)
      if (auth.role !== 'ADMIN') {
        setError('当前账号不是管理员')
        return
      }
      setAdminAuthTokens(auth.accessToken, auth.refreshToken, auth.authorities)
      const redirectTo = (location.state as RedirectState | null)?.from?.pathname ?? '/dashboard'
      navigate(redirectTo, { replace: true })
    } catch {
      setError('登录失败，请检查账号或密码')
    } finally {
      setLoading(false)
    }
  }

  return (
    <main className="login-screen">
      <section className="login-hero" aria-label="后台能力">
        <div className="login-brand-line">
          <span className="brand-mark">FX</span>
          <span>FX Trading Admin</span>
        </div>
        <h2>统一管理交易、风控、财务与内容运营</h2>
        <p>面向后台人员的高密度工作台，登录后进入实时运营控制台。</p>
        <div className="login-hero-grid" aria-hidden="true">
          <span>用户</span>
          <span>订单</span>
          <span>资金</span>
          <span>审计</span>
        </div>
      </section>
      <form className="login-panel" onSubmit={submitLogin}>
        <div>
          <span className="login-panel-kicker">管理员登录</span>
          <h1>外汇后台</h1>
        </div>
        <label>
          管理员邮箱
          <input
            value={email}
            autoComplete="username"
            placeholder="请输入管理员邮箱"
            onChange={(event) => setEmail(event.target.value)}
          />
        </label>
        <label>
          密码
          <input
            value={password}
            type="password"
            autoComplete="current-password"
            placeholder="请输入密码"
            onChange={(event) => setPassword(event.target.value)}
          />
        </label>
        <button className="login-submit" disabled={loading}>{loading ? '登录中' : '登录'}</button>
        {error ? <div className="admin-error">{error}</div> : null}
      </form>
    </main>
  )
}
