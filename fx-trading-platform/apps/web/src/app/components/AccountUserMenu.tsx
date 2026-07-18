import { LogOut, Settings, ShieldCheck, UserRound, Wallet } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'

import { TopbarToolIcon } from '../../components/TopbarToolIcon'
import {
  clearStoredAuthToken,
  logoutAuth,
  readStoredAuthToken,
  readStoredRefreshToken
} from '@fx-platform/frontend-core'

type AccountUserMenuProps = {
  email?: string | null
  onLogout: () => void
}

const accountLinks = [
  { to: '/account/overview', label: '总览', icon: UserRound },
  { to: '/account/assets', label: '钱包总览', icon: Wallet },
  { to: '/account/orders/trades', label: '交易订单', icon: Settings },
  { to: '/account/security/kyc', label: '身份认证', icon: ShieldCheck }
] as const

export function AccountUserMenu({ email, onLogout }: AccountUserMenuProps) {
  const navigate = useNavigate()
  const menuRef = useRef<HTMLDivElement>(null)
  const [open, setOpen] = useState(false)
  const displayEmail = email ?? readStoredEmail() ?? 'member@fxtrader.local'

  useEffect(() => {
    if (!open) return

    const handlePointer = (event: PointerEvent) => {
      if (!menuRef.current?.contains(event.target as Node)) setOpen(false)
    }
    const handleKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false)
    }

    document.addEventListener('pointerdown', handlePointer)
    document.addEventListener('keydown', handleKey)
    return () => {
      document.removeEventListener('pointerdown', handlePointer)
      document.removeEventListener('keydown', handleKey)
    }
  }, [open])

  const logout = () => {
    const accessToken = readStoredAuthToken()
    const refreshToken = readStoredRefreshToken()
    if (accessToken || refreshToken) {
      void logoutAuth(accessToken, refreshToken).catch(() => undefined)
    }
    clearStoredAuthToken()
    onLogout()
    setOpen(false)
    navigate('/')
  }

  return (
    <div className="account-user-menu" ref={menuRef}>
      <button
        type="button"
        className="app-topbar__icon account-user-menu__trigger"
        aria-label="个人中心"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen(true)}
      >
        <TopbarToolIcon name="user" />
      </button>

      {open ? (
        <div className="account-user-menu__panel" role="menu" aria-label="个人中心">
          <div className="account-user-menu__profile">
            <span className="account-user-menu__avatar" aria-hidden="true">
              FX
            </span>
            <div>
              <strong>{maskIdentifier(displayEmail)}</strong>
              <small>UID 829341 · Lv.1 · 已绑定邮箱</small>
            </div>
          </div>
          <nav>
            {accountLinks.map((item) => {
              const Icon = item.icon
              return (
                <Link key={item.to} to={item.to} role="menuitem" onClick={() => setOpen(false)}>
                  <Icon size={17} aria-hidden="true" />
                  <span>{item.label}</span>
                </Link>
              )
            })}
          </nav>
          <button type="button" className="account-user-menu__logout" role="menuitem" onClick={logout}>
            <LogOut size={17} aria-hidden="true" />
            <span>退出登录</span>
          </button>
        </div>
      ) : null}
    </div>
  )
}

function readStoredEmail() {
  try {
    return globalThis.localStorage?.getItem('fx-platform-user-email')
  } catch {
    return null
  }
}

function maskIdentifier(value: string) {
  const [name, domain] = value.split('@')
  if (!domain) return `${value.slice(0, 3)}***`
  return `${name.slice(0, 2)}***@${domain}`
}
