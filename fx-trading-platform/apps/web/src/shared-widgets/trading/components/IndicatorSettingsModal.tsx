import { ChevronRight, RotateCcw, X } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'

import {
  cloneIndicatorSettings,
  defaultIndicatorSettings,
  indicatorConfigOptions,
  updateIndicatorEnabled
} from '../chartSettings'
import type {
  IndicatorConfigGroup,
  IndicatorConfigOption,
  IndicatorConfigName,
  IndicatorParameterSettings,
  IndicatorSettings,
  LineType,
  MovingAverageLineSettings,
  MovingAverageSettings,
  VolumeIndicatorSettings
} from '../chartSettings'
import styles from './IndicatorSettingsModal.module.css'

type Props = {
  open: boolean
  settings: IndicatorSettings
  onClose: () => void
  onConfirm: (settings: IndicatorSettings) => void
}

type IndicatorTab = 'main' | 'secondary'
type MovingAverageKey = 'movingAverage' | 'exponentialMovingAverage' | 'weightedMovingAverage'

const movingAverageMeta: Record<
  'MA' | 'EMA' | 'WMA',
  { titleKey: string; prefix: string; settingsKey: MovingAverageKey }
> = {
  MA: { titleKey: 'chart.maTitle', prefix: 'MA', settingsKey: 'movingAverage' },
  EMA: { titleKey: 'chart.emaTitle', prefix: 'EMA', settingsKey: 'exponentialMovingAverage' },
  WMA: { titleKey: 'chart.wmaTitle', prefix: 'WMA', settingsKey: 'weightedMovingAverage' }
}

const tabGroups: Record<IndicatorTab, IndicatorConfigGroup[]> = {
  main: ['main'],
  secondary: ['trading', 'secondary']
}

const indicatorGroupLabels: Record<IndicatorConfigGroup, string> = {
  trading: 'chart.indicatorGroups.trading',
  main: 'chart.indicatorGroups.main',
  secondary: 'chart.indicatorGroups.secondary'
}

export function IndicatorSettingsModal({ open, settings, onClose, onConfirm }: Props) {
  const { t } = useTranslation()
  const [activeTab, setActiveTab] = useState<IndicatorTab>('main')
  const [activeIndicator, setActiveIndicator] = useState<IndicatorConfigName>('MA')
  const [draftSettings, setDraftSettings] = useState<IndicatorSettings>(() => cloneIndicatorSettings(settings))
  const visibleOptions = getVisibleIndicatorOptions(activeTab)
  const activeOption = indicatorConfigOptions.find((item) => item.value === activeIndicator) ?? visibleOptions[0]

  useEffect(() => {
    if (!open) return
    setActiveTab('main')
    setActiveIndicator('MA')
    setDraftSettings(cloneIndicatorSettings(settings))
  }, [open, settings])

  if (!open) return null

  const indicatorEnabled = (indicator: IndicatorConfigName) => {
    if (indicator === 'VOL' || indicator === 'VOLUME') return draftSettings.volume.enabled
    return draftSettings.enabled.includes(indicator)
  }

  const toggleIndicator = (indicator: IndicatorConfigName, enabled: boolean) => {
    setDraftSettings((current) => updateIndicatorEnabled(current, indicator, enabled))
  }

  const updateMovingAverageLine = (
    key: MovingAverageKey,
    index: number,
    patch: Partial<MovingAverageLineSettings>
  ) => {
    setDraftSettings((current) => ({
      ...current,
      [key]: {
        lines: current[key].lines.map((line, lineIndex) => (lineIndex === index ? { ...line, ...patch } : line))
      }
    }))
  }

  const updateIndicatorParameters = (indicator: string, patch: Partial<IndicatorParameterSettings>) => {
    setDraftSettings((current) => ({
      ...current,
      indicators: {
        ...current.indicators,
        [indicator]: {
          ...current.indicators[indicator],
          ...patch
        }
      }
    }))
  }

  const selectTab = (tab: IndicatorTab) => {
    setActiveTab(tab)
    setActiveIndicator(getVisibleIndicatorOptions(tab)[0]?.value ?? 'MA')
  }

  const updateVolume = (patch: Partial<VolumeIndicatorSettings>) => {
    setDraftSettings((current) => ({
      ...current,
      volume: {
        ...current.volume,
        ...patch,
        ma1: patch.ma1 ? { ...current.volume.ma1, ...patch.ma1 } : current.volume.ma1,
        ma2: patch.ma2 ? { ...current.volume.ma2, ...patch.ma2 } : current.volume.ma2
      }
    }))
  }

  return (
    <div className={styles.layer} role="presentation">
      <section className={styles.modal} role="dialog" aria-modal="true" aria-label={t('chart.indicatorSettings')} data-shortcut-disabled="true">
        <header className={styles.header}>
          <strong>{t('chart.indicatorSettings')}</strong>
          <button type="button" aria-label={t('common.close')} onClick={onClose}>
            <X size={20} />
          </button>
        </header>

        <div className={styles.tabs}>
          <button
            type="button"
            className={activeTab === 'main' ? styles.active : ''}
            onClick={() => selectTab('main')}
          >
            {t('chart.mainIndicators')}
          </button>
          <button
            type="button"
            className={activeTab === 'secondary' ? styles.active : ''}
            onClick={() => selectTab('secondary')}
          >
            {t('chart.secondaryIndicatorsWithCount', {
              count:
                draftSettings.enabled.filter((indicator) =>
                  getVisibleIndicatorOptions('secondary').some((item) => item.value === indicator)
                ).length + (draftSettings.volume.enabled ? 1 : 0)
            })}
          </button>
        </div>

        <div className={styles.body}>
          <aside className={styles.indicatorList}>
            {tabGroups[activeTab].map((group) => (
              <div key={group} className={styles.indicatorGroup}>
                <span className={styles.indicatorGroupLabel}>{t(indicatorGroupLabels[group])}</span>
                {visibleOptions.filter((item) => item.group === group).map((item) => (
                  <button
                    key={item.value}
                    type="button"
                    className={activeIndicator === item.value ? styles.selected : ''}
                    onClick={() => setActiveIndicator(item.value)}
                  >
                    <input
                      type="checkbox"
                      checked={indicatorEnabled(item.value)}
                      onChange={(event) => toggleIndicator(item.value, event.target.checked)}
                      onClick={(event) => event.stopPropagation()}
                    />
                    <span>{t(item.label)}</span>
                    <ChevronRight size={15} />
                  </button>
                ))}
              </div>
            ))}
          </aside>

          <main className={styles.configPane}>
            {activeIndicator === 'VOL' ? (
              <VolumeSettingsPanel settings={draftSettings.volume} onChange={updateVolume} />
            ) : activeIndicator === 'MA' || activeIndicator === 'EMA' || activeIndicator === 'WMA' ? (
              <MovingAveragePanel
                meta={movingAverageMeta[activeIndicator]}
                settings={draftSettings[movingAverageMeta[activeIndicator].settingsKey]}
                onLineChange={(index, patch) =>
                  updateMovingAverageLine(movingAverageMeta[activeIndicator].settingsKey, index, patch)
                }
              />
            ) : activeOption ? (
              <GenericIndicatorPanel
                option={activeOption}
                settings={draftSettings.indicators[activeOption.value]}
                onChange={(patch) => updateIndicatorParameters(activeOption.value, patch)}
              />
            ) : (
              <EmptyConfigPanel label={indicatorConfigOptions.find((item) => item.value === activeIndicator)?.label ?? activeIndicator} />
            )}
          </main>
        </div>

        <footer className={styles.footer}>
          <button type="button" className={styles.resetButton} onClick={() => setDraftSettings(cloneIndicatorSettings(defaultIndicatorSettings))}>
            <RotateCcw size={16} />
            {t('common.reset')}
          </button>
          <div className={styles.actions}>
            <button type="button" className={styles.cancelButton} onClick={onClose}>
              {t('common.cancel')}
            </button>
            <button type="button" className={styles.confirmButton} onClick={() => onConfirm(cloneIndicatorSettings(draftSettings))}>
              {t('common.confirm')}
            </button>
          </div>
        </footer>
      </section>
    </div>
  )
}

function MovingAveragePanel({
  meta,
  settings,
  onLineChange
}: {
  meta: { titleKey: string; prefix: string }
  settings: MovingAverageSettings
  onLineChange: (index: number, patch: Partial<MovingAverageLineSettings>) => void
}) {
  const { t } = useTranslation()

  return (
    <div className={styles.configContent}>
      <h3>{t(meta.titleKey)}</h3>
      <div className={styles.lineGrid}>
        {settings.lines.map((line, index) => (
          <div className={styles.lineRow} key={`${meta.prefix}-${index}`}>
            <label className={styles.checkLabel}>
              <input
                type="checkbox"
                checked={line.enabled}
                onChange={(event) => onLineChange(index, { enabled: event.target.checked })}
              />
              {meta.prefix}
              {index + 1}
            </label>
            <input
              type="number"
              min={1}
              value={line.period}
              onChange={(event) => onLineChange(index, { period: Number(event.target.value) || 1 })}
            />
            <select
              value={line.lineType}
              onChange={(event) => onLineChange(index, { lineType: event.target.value as LineType })}
            >
              <option value="solid">{t('chart.lineStyles.solid')}</option>
              <option value="dashed">{t('chart.lineStyles.dashed')}</option>
            </select>
            <input type="color" value={line.color} onChange={(event) => onLineChange(index, { color: event.target.value })} />
            <input
              type="number"
              min={1}
              max={6}
              value={line.width}
              onChange={(event) => onLineChange(index, { width: Number(event.target.value) || 1 })}
            />
          </div>
        ))}
      </div>
    </div>
  )
}

function GenericIndicatorPanel({
  option,
  settings,
  onChange
}: {
  option: IndicatorConfigOption
  settings: IndicatorParameterSettings
  onChange: (patch: Partial<IndicatorParameterSettings>) => void
}) {
  const { t } = useTranslation()

  return (
    <div className={styles.configContent}>
      <h3>
        {t(option.label)}
        <span className={styles.sourcePill}>{option.source === 'klinecharts' ? 'KLineChart' : t('chart.sourceCustom')}</span>
      </h3>

      {option.calcParams.length > 0 ? (
        <div className={styles.paramGrid}>
          {option.calcParams.map((_, index) => (
            <label key={`${option.value}-${index}`}>
              {t(option.paramLabels[index] ?? 'chart.params.number', { number: index + 1 })}
              <input
                type="number"
                min={1}
                value={settings.calcParams[index] ?? option.calcParams[index]}
                onChange={(event) => {
                  const calcParams = [...settings.calcParams]
                  calcParams[index] = Number(event.target.value) || 1
                  onChange({ calcParams })
                }}
              />
            </label>
          ))}
        </div>
      ) : (
        <p className={styles.emptyText}>{t('chart.noParams')}</p>
      )}

      <div className={styles.styleGrid}>
        {option.visualStyle !== 'bar' ? (
          <>
            <label>
              {t('chart.primaryColor')}
              <input type="color" value={settings.color} onChange={(event) => onChange({ color: event.target.value })} />
            </label>
            <label>
              {t('chart.secondaryColor')}
              <input
                type="color"
                value={settings.secondaryColor}
                onChange={(event) => onChange({ secondaryColor: event.target.value })}
              />
            </label>
            <label>
              {t('chart.lineStyle')}
              <select value={settings.lineType} onChange={(event) => onChange({ lineType: event.target.value as LineType })}>
                <option value="solid">{t('chart.lineStyles.solid')}</option>
                <option value="dashed">{t('chart.lineStyles.dashed')}</option>
              </select>
            </label>
            <label>
              {t('chart.lineWidth')}
              <input
                type="number"
                min={1}
                max={6}
                value={settings.width}
                onChange={(event) => onChange({ width: Number(event.target.value) || 1 })}
              />
            </label>
          </>
        ) : null}

        {option.visualStyle !== 'line' ? (
          <>
            <label>
              {t('chart.upBar')}
              <input type="color" value={settings.barUpColor} onChange={(event) => onChange({ barUpColor: event.target.value })} />
            </label>
            <label>
              {t('chart.downBar')}
              <input type="color" value={settings.barDownColor} onChange={(event) => onChange({ barDownColor: event.target.value })} />
            </label>
          </>
        ) : null}
      </div>

      <label className={styles.opacityRow}>
        <span>{t('chart.opacity')}</span>
        <input
          type="range"
          min={0}
          max={100}
          value={settings.opacity}
          onChange={(event) => onChange({ opacity: Number(event.target.value) })}
        />
        <strong>{settings.opacity}%</strong>
      </label>
    </div>
  )
}

function VolumeSettingsPanel({
  settings,
  onChange
}: {
  settings: VolumeIndicatorSettings
  onChange: (patch: Partial<VolumeIndicatorSettings>) => void
}) {
  const { t } = useTranslation()

  return (
    <div className={styles.configContent}>
      <h3>{t('chart.volumeTitle')}</h3>
      <label className={styles.switchRow}>
        <input type="checkbox" checked={settings.enabled} onChange={(event) => onChange({ enabled: event.target.checked })} />
        {t('chart.enableVolume')}
      </label>

      <div className={styles.volumeColors}>
        <label>
          {t('chart.volUp')}
          <input type="color" value={settings.barUpColor} onChange={(event) => onChange({ barUpColor: event.target.value })} />
        </label>
        <label>
          {t('chart.volDown')}
          <input type="color" value={settings.barDownColor} onChange={(event) => onChange({ barDownColor: event.target.value })} />
        </label>
      </div>

      <div className={styles.lineGrid}>
        <VolumeMaRow
          label="MA1"
          value={settings.ma1}
          onChange={(value) => onChange({ ma1: value })}
        />
        <VolumeMaRow
          label="MA2"
          value={settings.ma2}
          onChange={(value) => onChange({ ma2: value })}
        />
      </div>

      <label className={styles.opacityRow}>
        <span>{t('chart.opacity')}</span>
        <input
          type="range"
          min={0}
          max={100}
          value={settings.opacity}
          onChange={(event) => onChange({ opacity: Number(event.target.value) })}
        />
        <strong>{settings.opacity}%</strong>
      </label>
    </div>
  )
}

function VolumeMaRow({
  label,
  value,
  onChange
}: {
  label: string
  value: VolumeIndicatorSettings['ma1']
  onChange: (value: VolumeIndicatorSettings['ma1']) => void
}) {
  return (
    <div className={styles.lineRow}>
      <label className={styles.checkLabel}>
        <input type="checkbox" checked={value.enabled} onChange={(event) => onChange({ ...value, enabled: event.target.checked })} />
        {label}
      </label>
      <input
        type="number"
        min={1}
        value={value.period}
        onChange={(event) => onChange({ ...value, period: Number(event.target.value) || 1 })}
      />
      <span className={styles.linePreview} />
      <input type="color" value={value.color} onChange={(event) => onChange({ ...value, color: event.target.value })} />
    </div>
  )
}

function EmptyConfigPanel({ label }: { label: string }) {
  const { t } = useTranslation()

  return (
    <div className={styles.configContent}>
      <h3>{t(label)}</h3>
      <p className={styles.emptyText}>{t('chart.noConfigItems')}</p>
    </div>
  )
}

function getVisibleIndicatorOptions(tab: IndicatorTab) {
  const groups = tabGroups[tab]
  return indicatorConfigOptions.filter((item) => groups.includes(item.group))
}
