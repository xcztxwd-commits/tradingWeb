import {
  ArrowDownCircle,
  ArrowUpCircle,
  BadgeHelp,
  BarChart3,
  Gamepad2,
  Gift,
  PartyPopper,
  QrCode,
  Settings,
  ShieldCheck,
  Sparkles,
  UserRoundCheck,
  Users,
  WalletCards
} from 'lucide-react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'

export function AccountHubPage() {
  const { t } = useTranslation()

  return (
    <section className="user-page accountHub" aria-labelledby="account-title">
      <header className="accountHub__profile">
        <div className="accountHub__avatar" aria-hidden="true">
          XC
        </div>
        <div>
          <h1 id="account-title">{t('account.entryTitle')}</h1>
          <strong>xczokx</strong>
          <span>
            <UserRoundCheck size={15} aria-hidden="true" />
            {t('account.verified', { defaultValue: '已认证' })}
          </span>
          <div className="accountHub__profileActions">
            <Link to="/settings">{t('account.settings')}</Link>
            <button type="button" aria-label={t('account.qrCode', { defaultValue: '二维码' })}>
              <QrCode size={18} aria-hidden="true" />
            </button>
          </div>
        </div>
        <Link className="accountHub__accountButton" to="/settings">
          <Settings size={18} aria-hidden="true" />
        </Link>
      </header>

      <section className="accountHub__vip" aria-label={t('account.unlockVip', { defaultValue: '解锁欧易 VIP' })}>
        <div>
          <strong>{t('account.unlockVip', { defaultValue: '解锁欧易 VIP' })}</strong>
          <span>{t('account.vipSummary', { defaultValue: '费率折扣、更高收益、优享服务' })}</span>
        </div>
        <div className="accountHub__vipPerks">
          <span>
            <Sparkles size={16} aria-hidden="true" />
            {t('account.feeDiscount', { defaultValue: '费率折扣' })}
          </span>
          <span>
            <BarChart3 size={16} aria-hidden="true" />
            {t('account.higherYield', { defaultValue: '更高收益' })}
          </span>
          <span>
            <BadgeHelp size={16} aria-hidden="true" />
            {t('account.priorityService', { defaultValue: '优享服务' })}
          </span>
        </div>
      </section>

      <section className="accountHub__section" aria-label={t('account.commonFeatures', { defaultValue: '常用功能' })}>
        <div className="accountHub__sectionHead">
          <h2>{t('account.commonFeatures', { defaultValue: '常用功能' })}</h2>
          <Link to="/settings">{t('common.edit')}</Link>
        </div>
        <div className="accountHub__iconGrid">
          <HubLink to="/security" icon={<BadgeHelp size={25} aria-hidden="true" />} label={t('account.helpCenter', { defaultValue: '获取帮助' })} />
          <HubLink to="/trading" icon={<Gamepad2 size={25} aria-hidden="true" />} label={t('account.demoTrading', { defaultValue: '模拟交易' })} />
          <HubLink to="/account" icon={<Gift size={25} aria-hidden="true" />} label={t('account.inviteFriends', { defaultValue: '邀请好友' })} />
          <HubLink to="/orders" icon={<PartyPopper size={25} aria-hidden="true" />} label={t('account.activityCenter', { defaultValue: '活动中心' })} />
        </div>
      </section>

      <section className="accountHub__section" aria-label={t('account.assetManagement', { defaultValue: '资产管理' })}>
        <h2>{t('account.assetManagement', { defaultValue: '资产管理' })}</h2>
        <div className="accountHub__iconGrid accountHub__iconGrid--compact">
          <HubLink to="/wallet" icon={<ArrowDownCircle size={27} aria-hidden="true" />} label={t('assets.depositEntry')} />
          <HubLink to="/wallet" icon={<ArrowUpCircle size={27} aria-hidden="true" />} label={t('assets.withdrawEntry')} />
          <HubLink to="/wallet" icon={<Users size={27} aria-hidden="true" />} label={t('account.c2cTrading', { defaultValue: 'C2C 交易' })} />
        </div>
      </section>

      <section className="accountHub__section" aria-label={t('account.tradeEntries', { defaultValue: '交易' })}>
        <h2>{t('account.tradeEntries', { defaultValue: '交易' })}</h2>
        <div className="accountHub__iconGrid">
          <HubLink to="/markets" icon={<BarChart3 size={27} aria-hidden="true" />} label={t('nav.markets')} />
          <HubLink to="/trading" icon={<BarChart3 size={27} aria-hidden="true" />} label={t('account.contractTrading', { defaultValue: '合约' })} />
          <HubLink to="/wallet" icon={<WalletCards size={27} aria-hidden="true" />} label={t('account.optionsTrading', { defaultValue: '期权' })} />
          <HubLink to="/security" icon={<ShieldCheck size={27} aria-hidden="true" />} label={t('nav.security')} />
        </div>
      </section>
    </section>
  )
}

function HubLink({ to, icon, label }: { to: string; icon: ReactNode; label: string }) {
  return (
    <Link className="accountHub__iconLink" to={to}>
      {icon}
      <span>{label}</span>
    </Link>
  )
}
