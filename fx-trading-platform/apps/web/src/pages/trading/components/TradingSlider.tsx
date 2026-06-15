import { getPercentageStep, percentageSteps } from '../../../features/market/tradingModels'
import styles from './TradingSlider.module.css'

type Props = {
  value: number
  disabled?: boolean
  onChange: (value: number) => void
}

export function TradingSlider({ value, disabled = false, onChange }: Props) {
  const snappedValue = getPercentageStep(value)

  return (
    <div className={`${styles.slider} ${disabled ? styles.disabled : ''}`}>
      <div className={styles.trackWrap}>
        <input
          aria-label="Order size percentage"
          type="range"
          min="0"
          max="100"
          step="25"
          value={snappedValue}
          disabled={disabled}
          onChange={(event) => onChange(getPercentageStep(Number(event.target.value)))}
        />
        <span className={styles.fill} style={{ transform: `scaleX(${snappedValue / 100})` }} />
      </div>
      <div className={styles.steps}>
        {percentageSteps.map((step) => (
          <button
            key={step}
            type="button"
            disabled={disabled}
            className={step === snappedValue ? styles.activeStep : ''}
            onClick={() => onChange(step)}
          >
            {step}
          </button>
        ))}
      </div>
    </div>
  )
}
