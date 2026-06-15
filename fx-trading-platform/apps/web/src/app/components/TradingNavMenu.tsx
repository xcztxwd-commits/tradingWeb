import { ChevronDown, CircleDollarSign, Gem, LineChart } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { resolveTradingPath, type TradingCategory } from '../hooks/useLastTradingSymbol'

const tradingMenuItems: Array<{
  category: TradingCategory
  label: string
  description: string
  icon: typeof LineChart
}> = [
  { category: 'crypto', label: '现货交易', description: 'BTC、ETH 等主流币种', icon: Gem },
  { category: 'forex', label: '外汇交易', description: 'EURUSD、GBPUSD 等外汇品种', icon: LineChart },
  { category: 'contract', label: '合约', description: '按最近合约品种快速进入', icon: CircleDollarSign }
]

export function TradingNavMenu() {
  const navigate = useNavigate()
  const menuRef = useRef<HTMLDivElement>(null)
  const [open, setOpen] = useState(false)
  const [activeIndex, setActiveIndex] = useState(0)

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

  const openCategory = (category: TradingCategory) => {
    setOpen(false)
    navigate(resolveTradingPath(category))
  }

  return (
    <div
      className="trading-nav-menu"
      ref={menuRef}
      onPointerEnter={(event) => {
        if (event.pointerType !== 'touch') setOpen(true)
      }}
      onPointerLeave={(event) => {
        if (event.pointerType !== 'touch') setOpen(false)
      }}
    >
      <button
        type="button"
        className={`app-topbar__link trading-nav-menu__trigger${open ? ' active' : ''}`}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen(true)}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown') {
            event.preventDefault()
            setOpen(true)
            setActiveIndex((current) => Math.min(current + 1, tradingMenuItems.length - 1))
          }
          if (event.key === 'ArrowUp') {
            event.preventDefault()
            setOpen(true)
            setActiveIndex((current) => Math.max(current - 1, 0))
          }
          if (event.key === 'Enter' && open) {
            event.preventDefault()
            openCategory(tradingMenuItems[activeIndex].category)
          }
        }}
      >
        <LineChart size={17} aria-hidden="true" />
        <span>交易</span>
        <ChevronDown size={15} aria-hidden="true" />
      </button>

      {open ? (
        <div className="trading-nav-menu__panel" role="menu" aria-label="交易分类">
          {tradingMenuItems.map((item, index) => {
            const Icon = item.icon
            return (
              <button
                key={item.category}
                type="button"
                role="menuitem"
                className={index === activeIndex ? 'active' : ''}
                onMouseEnter={() => setActiveIndex(index)}
                onClick={() => openCategory(item.category)}
              >
                <span className="trading-nav-menu__icon">
                  <Icon size={18} aria-hidden="true" />
                </span>
                <span>
                  <strong>{item.label}</strong>
                  <small>{item.description}</small>
                </span>
              </button>
            )
          })}
        </div>
      ) : null}
    </div>
  )
}
