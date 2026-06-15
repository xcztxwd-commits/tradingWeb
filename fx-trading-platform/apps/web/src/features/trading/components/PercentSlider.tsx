import { useTranslation } from 'react-i18next'

const percentSteps = [0, 25, 50, 75, 100]

type Props = {
  value: number
  onChange: (value: number) => void
}

export function PercentSlider({ value, onChange }: Props) {
  const { t } = useTranslation()

  return (
    <div className="trade-panel__percent">
      <div className="trade-panel__range-wrap">
        <input
          aria-label={t('trading.quantityPercent')}
          type="range"
          min="0"
          max="100"
          step="25"
          value={value}
          onChange={(event) => onChange(Number(event.target.value))}
        />
        <span className="trade-panel__range-fill" style={{ transform: `scaleX(${value / 100})` }} />
      </div>
      <div className="trade-panel__percent-steps">
        {percentSteps.map((step) => (
          <button
            key={step}
            type="button"
            className={step === value ? 'trade-panel__percent-step--active' : ''}
            onClick={() => onChange(step)}
          >
            {step}%
          </button>
        ))}
      </div>
    </div>
  )
}
