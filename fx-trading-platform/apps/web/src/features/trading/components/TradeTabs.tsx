import { useTranslation } from 'react-i18next'

type Props = {
  onToolsUnavailable: () => void
}

export function TradeTabs({ onToolsUnavailable }: Props) {
  const { t } = useTranslation()

  return (
    <div className="trade-panel__top-tabs" role="tablist" aria-label={t('trading.panel')}>
      <button type="button" className="trade-panel__top-tab trade-panel__top-tab--active" role="tab" aria-selected="true">
        {t('trading.trade')}
      </button>
      <button type="button" className="trade-panel__top-tab" role="tab" aria-selected="false" onClick={onToolsUnavailable}>
        {t('trading.tools')}
      </button>
    </div>
  )
}
