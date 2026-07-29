import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const componentPath = join(currentDir, 'SimplifiedLiquidationDisclaimer.tsx')

function readRequiredSource(relativePath: string) {
  const path = join(currentDir, relativePath)
  assert.equal(existsSync(path), true, `${relativePath} must exist`)
  return readFileSync(path, 'utf8')
}

describe('simplified liquidation disclaimer', () => {
  it('keeps the complete Demo-only liquidation warning visibly available in Chinese and English', () => {
    assert.equal(existsSync(componentPath), true, 'liquidation disclaimer component must exist')

    const source = readFileSync(componentPath, 'utf8')
    assert.match(source, /预计强平价使用简化 Demo 模型/)
    assert.match(source, /不代表 Binance、OKX 或其他真实交易所的完整风控/)
    assert.match(source, /不可用于真实资金交易/)
    assert.match(source, /simplified Demo model/i)
    assert.match(source, /not the complete risk model of Binance, OKX, or any real exchange/i)
    assert.match(source, /must not be used for real-money trading/i)
    assert.match(source, /role="note"/)
  })

  it('keeps market source and Perpetual reference data explicit and stale-aware', () => {
    const badgeSource = readRequiredSource('MarketSourceBadge.tsx')
    const referenceSource = readRequiredSource('../components/PerpetualReferenceStrip.tsx')

    assert.match(badgeSource, /source: MarketSource/)
    assert.match(badgeSource, /stale\?: boolean/)
    assert.match(badgeSource, /LOCAL_SIMULATED/)
    assert.match(badgeSource, /data-stale=/)
    assert.match(referenceSource, /markPrice: string/)
    assert.match(referenceSource, /indexPrice: string/)
    assert.match(referenceSource, /fundingRate: string/)
    assert.match(referenceSource, /fundingCountdown: string/)
    assert.match(referenceSource, /<MarketSourceBadge/)
  })

  it('exposes account and symbol settings as bounded controlled inputs', () => {
    const source = readRequiredSource('TradingSettingsBar.tsx')

    assert.match(source, /positionMode: PositionMode/)
    assert.match(source, /marginMode: MarginMode/)
    assert.match(source, /maxLeverage: number/)
    assert.match(source, /quantityUnit: QuantityUnit/)
    assert.match(source, /onPositionModeChange:/)
    assert.match(source, /onMarginModeChange:/)
    assert.match(source, /onLeverageChange:/)
    assert.match(source, /onQuantityUnitChange:/)
    assert.match(source, /Math\.min\(100,/)
    assert.match(source, /Math\.max\(1,/)
    assert.match(source, /aria-busy=/)
    assert.match(source, /role="alert"/)
  })

  it('caps controlled TP and SL protection levels at ten with MARKET or LIMIT execution', () => {
    const source = readRequiredSource('MultiLevelProtectionEditor.tsx')

    assert.match(source, /export const MAX_PROTECTION_LEVELS = 10/)
    assert.match(source, /'TAKE_PROFIT' \| 'STOP_LOSS'/)
    assert.match(source, /'MARKET' \| 'LIMIT'/)
    assert.match(source, /levels: readonly ProtectionLevel\[\]/)
    assert.match(source, /onLevelChange:/)
    assert.match(source, /onAdd:/)
    assert.match(source, /onRemove:/)
    assert.match(source, /levels\.length >= levelLimit/)
    assert.match(source, /level\.executionType === 'LIMIT'/)
  })

  it('keeps partial close, full close, and isolated margin adjustment controlled by the parent', () => {
    const source = readRequiredSource('PositionActionDialog.tsx')

    assert.match(source, /'PARTIAL_CLOSE' \| 'FULL_CLOSE' \| 'ADJUST_MARGIN'/)
    assert.match(source, /'ADD' \| 'REDUCE'/)
    assert.match(source, /marginMode: 'CROSS' \| 'ISOLATED'/)
    assert.match(source, /onActionChange:/)
    assert.match(source, /onQuantityChange:/)
    assert.match(source, /onMarginAdjustmentChange:/)
    assert.match(source, /onConfirm:/)
    assert.match(source, /marginMode === 'ISOLATED'/)
    assert.match(source, /const actionControlsDisabled = disabled \|\| pending/)
    assert.match(source, /disabled=\{actionControlsDisabled\}/)
    assert.match(source, /pending=\{pending\}/)
    assert.match(source, /role="alert"/)
  })
})
