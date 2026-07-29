import styles from './TradingControls.module.css'

type Props = {
  estimatedLiquidationPrice?: string | null
  className?: string
}

export function SimplifiedLiquidationDisclaimer({ estimatedLiquidationPrice, className = '' }: Props) {
  const classes = [styles.disclaimer, className].filter(Boolean).join(' ')

  return (
    <aside className={classes} role="note">
      {estimatedLiquidationPrice ? (
        <p className={styles.disclaimerEstimate}>
          <span>Estimated liquidation price / 预计强平价</span>
          <strong>{estimatedLiquidationPrice}</strong>
        </p>
      ) : null}
      <p lang="zh-CN">
        预计强平价使用简化 Demo 模型，不代表 Binance、OKX 或其他真实交易所的完整风控，不可用于真实资金交易。
      </p>
      <p lang="en">
        The estimated liquidation price uses a simplified Demo model. It is not the complete risk model of Binance, OKX, or any real exchange and must not be used for real-money trading.
      </p>
    </aside>
  )
}
