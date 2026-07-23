import { ChevronDown, CircleDollarSign, Gem, LineChart } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'

import { resolveTradingPath } from '../hooks/useLastTradingSymbol'
import type { TradingProduct } from '../tradingRoutes'
import styles from './TradingNavMenu.module.css'

const tradingMenuItems: Array<{
  product: TradingProduct
  label: string
  description: string
  icon: typeof LineChart
}> = [
  { product: 'spot', label: '现货交易', description: '五个 USDT 现货交易对', icon: Gem },
  { product: 'perpetual', label: 'USDT 永续', description: '五个 USDT 本位永续合约', icon: CircleDollarSign }
]

export function TradingNavMenu({ triggerClassName }: { triggerClassName?: string }) {
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

  const openProduct = (product: TradingProduct) => {
    setOpen(false)
    navigate(resolveTradingPath(product))
  }

  return (
    <div
      className={styles.root}
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
        className={[triggerClassName, styles.trigger, open && styles.active].filter(Boolean).join(' ')}
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
            openProduct(tradingMenuItems[activeIndex].product)
          }
        }}
      >
        <LineChart size={17} aria-hidden="true" />
        <span>交易</span>
        <ChevronDown size={15} aria-hidden="true" />
      </button>

      {open ? (
        <div className={styles.panel} role="menu" aria-label="交易分类">
          {tradingMenuItems.map((item, index) => {
            const Icon = item.icon
            return (
              <button
                key={item.product}
                type="button"
                role="menuitem"
                className={index === activeIndex ? styles.active : undefined}
                onMouseEnter={() => setActiveIndex(index)}
                onClick={() => openProduct(item.product)}
              >
                <span className={styles.icon}>
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
