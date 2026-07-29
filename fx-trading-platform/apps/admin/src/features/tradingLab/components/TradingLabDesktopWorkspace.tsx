import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
} from 'react'

import {
  createTradingLabDraftAutosave,
  type TradingLabDraftAutosave,
} from '../drafts/autosave.ts'
import {
  createTradingLabDraftStore,
  type TradingLabDraftStore,
  type TradingLabDraftSummary,
} from '../drafts/draftStore.ts'
import {
  downloadTradingLabScenarioJson,
  importTradingLabScenarioJson,
} from '../drafts/scenarioJson.ts'
import { generateRandomScenario } from '../generator/randomScenario.ts'
import {
  EMPTY_TRADING_LAB_RUN_CHART_EVIDENCE,
  type TradingLabRunChartEvidence,
} from '../chart/runChartEvidence.ts'
import { useLocalExpectedScenario } from '../localExpected/useLocalExpectedScenario.ts'
import type {
  BlockedOutcome,
  CalculatedOutcome,
} from '../localExpected/localOracleScheduler.ts'
import { scenarioFingerprint } from '../model/normalization.ts'
import type {
  TimelineAction,
  TimelineActionType,
  TradingLabScenario,
} from '../model/types.ts'
import {
  canRunScenario,
  validateScenario,
} from '../model/validation.ts'
import {
  createTradingLabWorkspaceAsyncGate,
} from '../workspaceAsyncGate.ts'
import { LocalExpectedPanel } from './LocalExpectedPanel.tsx'
import { NegativeModeBanner } from './NegativeModeBanner.tsx'
import { TimelineEditor } from './TimelineEditor.tsx'
import { TradingLabChartSection } from './TradingLabChartSection.tsx'

type DraftRuntime =
  | Readonly<{
      store: TradingLabDraftStore
      autosave: TradingLabDraftAutosave
    }>
  | Readonly<{ error: Error }>

export type TradingLabDesktopWorkspaceProps = Readonly<{
  locked?: boolean
  chartEvidence?: TradingLabRunChartEvidence
  scenarioOverride?: TradingLabScenario | null
  runRequestState?: 'IDLE' | 'CREATING' | 'CREATE_UNKNOWN'
  onRunRequest?: (request: TradingLabRunRequest) => void
}>

export type TradingLabRunRequest = Readonly<{
  scenario: TradingLabScenario
  localCalculation: CalculatedOutcome | BlockedOutcome
}>

const VALIDATION_PUBLIC_ACTIONS = new Set<TimelineActionType>([
  'PLACE_ORDER',
  'CANCEL_ORDER',
  'CANCEL_ALL',
  'SET_POSITION_MODE',
  'SET_MARGIN_MODE',
  'SET_LEVERAGE',
])

function newRandomSeed(currentSeed: string): string {
  const values = new Uint32Array(4)
  globalThis.crypto.getRandomValues(values)
  const generated = `random-${Array.from(
    values,
    (value) => value.toString(16).padStart(8, '0'),
  ).join('')}`
  return generated === currentSeed ? `${generated}-next` : generated
}

function createDraftRuntime(): DraftRuntime {
  try {
    const store = createTradingLabDraftStore()
    return {
      store,
      autosave: createTradingLabDraftAutosave(store),
    }
  } catch (error) {
    return {
      error: error instanceof Error
        ? error
        : new Error('本地草稿存储不可用'),
    }
  }
}

export function TradingLabDesktopWorkspace({
  locked = false,
  chartEvidence = EMPTY_TRADING_LAB_RUN_CHART_EVIDENCE,
  scenarioOverride,
  runRequestState = 'IDLE',
  onRunRequest,
}: TradingLabDesktopWorkspaceProps) {
  const [runtime] = useState(createDraftRuntime)
  const [asyncGate] = useState(() =>
    createTradingLabWorkspaceAsyncGate(locked),
  )
  useLayoutEffect(() => {
    asyncGate.setLocked(locked)
  }, [asyncGate, locked])
  const [scenario, setScenario] = useState<TradingLabScenario | null>(null)
  const [drafts, setDrafts] = useState<TradingLabDraftSummary[]>([])
  const [message, setMessage] = useState('正在读取本地草稿…')
  const [randomSeed, setRandomSeed] = useState('')
  const [scenarioHash, setScenarioHash] = useState('')
  const importInputRef = useRef<HTMLInputElement>(null)
  const gateLifecycleRef = useRef(0)
  const activeScenario = scenarioOverride === undefined
    ? scenario
    : scenarioOverride
  const localExpected = useLocalExpectedScenario(activeScenario)

  useEffect(() => {
    setRandomSeed(activeScenario?.seed ?? '')
  }, [activeScenario?.id, activeScenario?.seed])

  useEffect(() => {
    let current = true
    if (activeScenario === null) {
      setScenarioHash('')
      return () => {
        current = false
      }
    }
    setScenarioHash('')
    void scenarioFingerprint(activeScenario)
      .then((fingerprint) => {
        if (current) {
          setScenarioHash(fingerprint)
        }
      })
      .catch(() => {
        if (current) {
          setScenarioHash('无法计算')
        }
      })
    return () => {
      current = false
    }
  }, [activeScenario])

  const listDrafts = useCallback(async () => {
    if ('error' in runtime) {
      throw runtime.error
    }
    return runtime.store.list()
  }, [runtime])

  useEffect(() => {
    let active = true
    gateLifecycleRef.current += 1
    const lifecycle = gateLifecycleRef.current
    const cleanup = (): void => {
      active = false
      asyncGate.invalidate()
      if (!('error' in runtime)) {
        runtime.autosave.cancel()
      }
      queueMicrotask(() => {
        if (gateLifecycleRef.current === lifecycle) {
          asyncGate.dispose()
        }
      })
    }
    const operation = asyncGate.begin({ allowLocked: true })
    if ('error' in runtime) {
      setMessage(runtime.error.message)
      return cleanup
    }
    if (operation === null) {
      return cleanup
    }

    void listDrafts()
      .then(async (summaries) => {
        if (
          !active
          || !asyncGate.isCurrent(operation, { allowLocked: true })
        ) {
          return
        }
        setDrafts(summaries)
        const first = summaries[0]
        if (first === undefined) {
          setMessage('尚无本地草稿，请导入规范场景 JSON。')
          return
        }
        const loaded = await runtime.store.get(first.id)
        if (
          active
          && asyncGate.isCurrent(operation, { allowLocked: true })
        ) {
          setScenario(loaded)
          setMessage(loaded === null ? '本地草稿已不存在。' : '')
        }
      })
      .catch((error: unknown) => {
        if (
          active
          && asyncGate.isCurrent(operation, { allowLocked: true })
        ) {
          setMessage(
            error instanceof Error ? error.message : '读取本地草稿失败',
          )
        }
      })

    return cleanup
  }, [asyncGate, listDrafts, runtime])

  const issues = useMemo(
    () => activeScenario === null ? [] : validateScenario(activeScenario),
    [activeScenario],
  )
  const hasBlockingIssue = !canRunScenario(issues)
  const hasLocalOnlyAction = activeScenario?.timeline.some(
    (action) => !VALIDATION_PUBLIC_ACTIONS.has(action.type),
  ) ?? false
  const canRequestRun = (
    activeScenario !== null
    && !locked
    && runRequestState === 'IDLE'
    && (
      localExpected.status === 'CALCULATED'
      || localExpected.status === 'BLOCKED'
    )
    && !hasBlockingIssue
    && !hasLocalOnlyAction
    && onRunRequest !== undefined
  )

  const replaceScenario = useCallback((
    update: (current: TradingLabScenario) => TradingLabScenario,
  ) => {
    if (
      locked
      || scenarioOverride !== undefined
      || 'error' in runtime
    ) {
      return
    }
    setScenario((current) => {
      if (current === null) {
        return current
      }
      const next = update(current)
      runtime.autosave.schedule(next)
      return next
    })
  }, [locked, runtime, scenarioOverride])

  const handleTimelineChange = useCallback((actions: TimelineAction[]) => {
    replaceScenario((current) => ({
      ...current,
      timeline: actions,
    }))
  }, [replaceScenario])

  const handleDraftSelection = useCallback(async (
    event: ChangeEvent<HTMLSelectElement>,
  ) => {
    const operation = asyncGate.begin()
    if ('error' in runtime || operation === null) {
      return
    }
    const id = event.currentTarget.value
    if (id.length === 0) {
      setScenario(null)
      setMessage('请选择或导入一个本地草稿。')
      return
    }
    try {
      const loaded = await runtime.store.get(id)
      if (!asyncGate.isCurrent(operation)) {
        return
      }
      setScenario(loaded)
      setMessage(loaded === null ? '所选草稿已不存在。' : '')
    } catch (error) {
      if (asyncGate.isCurrent(operation)) {
        setMessage(error instanceof Error ? error.message : '读取草稿失败')
      }
    }
  }, [asyncGate, runtime])

  const handleImport = useCallback(async (
    event: ChangeEvent<HTMLInputElement>,
  ) => {
    const input = event.currentTarget
    const file = input.files?.[0]
    const operation = asyncGate.begin()
    if (
      file === undefined
      || 'error' in runtime
      || operation === null
    ) {
      return
    }
    try {
      const source = await file.text()
      if (!asyncGate.isCurrent(operation)) {
        return
      }
      const imported = await importTradingLabScenarioJson(source)
      if (!asyncGate.isCurrent(operation)) {
        return
      }
      await runtime.store.put(imported)
      if (!asyncGate.isCurrent(operation)) {
        return
      }
      setScenario(imported)
      setMessage('')
      const summaries = await listDrafts()
      if (asyncGate.isCurrent(operation)) {
        setDrafts(summaries)
      }
    } catch (error) {
      if (asyncGate.isCurrent(operation)) {
        setMessage(error instanceof Error ? error.message : '导入场景失败')
      }
    } finally {
      input.value = ''
    }
  }, [asyncGate, listDrafts, runtime])

  const handleDownload = useCallback(async () => {
    if (activeScenario === null) {
      return
    }
    try {
      await downloadTradingLabScenarioJson(activeScenario)
      setMessage('场景 JSON 已导出。')
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '导出场景失败')
    }
  }, [activeScenario])

  const generateFromSeed = useCallback((seed: string) => {
    if (activeScenario === null || seed.trim().length === 0) {
      setMessage('随机种子不能为空。')
      return
    }
    try {
      const maximumLeverage = activeScenario.symbols
        .filter((symbol) => symbol.productType === 'LINEAR_PERP')
        .map((symbol) => activeScenario.configSnapshot.instruments.find(
          (instrument) =>
            instrument.productType === symbol.productType
            && instrument.symbol === symbol.symbol,
        )?.maxLeverage ?? 1)
        .reduce((maximum, value) => Math.min(maximum, value), 10)
      const generated = generateRandomScenario({
        baseScenario: activeScenario,
        seed,
        actionCount: 6,
        durationSeconds: 24,
        realistic: true,
        negativeMode: activeScenario.negativeMode,
        priceRange: { min: '100', max: '200' },
        leverageRange: {
          min: 1,
          max: Math.max(1, maximumLeverage),
        },
        fundingRateRange: { min: '-0.001', max: '0.001' },
        feeRateRange: { min: '0.0001', max: '0.001' },
        offsetRangeSteps: { min: 0, max: 2 },
        volatilitySteps: { min: 1, max: 3 },
      })
      replaceScenario(() => generated)
      setMessage('随机场景已生成，可继续手工编辑。')
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '随机场景生成失败')
    }
  }, [activeScenario, replaceScenario])

  const handleGenerateRandom = useCallback(() => {
    generateFromSeed(randomSeed)
  }, [generateFromSeed, randomSeed])

  const handleRerandomize = useCallback(() => {
    try {
      const seed = newRandomSeed(randomSeed)
      setRandomSeed(seed)
      generateFromSeed(seed)
    } catch (error) {
      setMessage(error instanceof Error ? error.message : '随机种子生成失败')
    }
  }, [generateFromSeed, randomSeed])

  const handleRunRequest = useCallback(() => {
    if (
      !canRequestRun
      || activeScenario === null
      || (
        localExpected.status !== 'CALCULATED'
        && localExpected.status !== 'BLOCKED'
      )
      || onRunRequest === undefined
    ) {
      return
    }
    onRunRequest({
      scenario: activeScenario,
      localCalculation: localExpected.outcome,
    })
  }, [activeScenario, canRequestRun, localExpected, onRunRequest])

  return (
    <div className="trading-lab-desktop-shell">
      <header className="trading-lab-page-header">
        <div>
          <span className="trading-lab-eyebrow">Demo validation workspace</span>
          <h1 id="trading-lab-title">交易路径实验室</h1>
        </div>
        <span className="trading-lab-bootstrap-status">
          {locked ? '场景已锁定' : '本地草稿模式'}
        </span>
      </header>

      <div className="trading-lab-toolbar" aria-label="本地草稿工具栏">
        <label>
          本地草稿
          <select
            value={activeScenario?.id ?? ''}
            onChange={handleDraftSelection}
            disabled={locked || 'error' in runtime}
          >
            <option value="">请选择</option>
            {drafts.map((draft) => (
              <option key={draft.id} value={draft.id}>
                {draft.name || draft.id}
              </option>
            ))}
          </select>
        </label>
        <input
          ref={importInputRef}
          data-testid="trading-lab-scenario-import-input"
          className="trading-lab-file-input"
          type="file"
          accept="application/json,.json"
          onChange={handleImport}
          disabled={locked || 'error' in runtime}
        />
        <button
          type="button"
          onClick={() => importInputRef.current?.click()}
          disabled={locked || 'error' in runtime}
        >
          导入 JSON
        </button>
        <button
          type="button"
          onClick={handleDownload}
          disabled={activeScenario === null}
        >
          导出 JSON
        </button>
        <button
          type="button"
          onClick={handleRunRequest}
          disabled={!canRequestRun}
        >
          {runRequestState === 'CREATING' ? '正在创建…' : '保存并运行'}
        </button>
        <span role="status" className="trading-lab-toolbar-message">
          {runRequestState === 'CREATE_UNKNOWN'
            ? '创建结果不确定；为避免重复 Run，场景保持锁定。'
            : hasLocalOnlyAction
              ? '当前场景含 LOCAL-only 动作，不能提交真实 validation Run。'
              : message}
        </span>
      </div>

      {activeScenario === null ? (
        <div className="state-block trading-lab-empty-state">
          {scenarioOverride === undefined
            ? '当前没有可编辑场景。请导入规范场景 JSON，或先在本浏览器保存一个本地草稿。'
            : '正在恢复服务端冻结场景…'}
        </div>
      ) : (
        <div className="trading-lab-workspace-stack">
          <div className="trading-lab-workspace-grid">
            <aside className="trading-lab-panel trading-lab-settings-panel">
              <h2>场景</h2>
              <label>
                随机种子
                <input
                  data-testid="trading-lab-random-seed"
                  value={randomSeed}
                  maxLength={256}
                  disabled={locked}
                  onChange={(event) => {
                    setRandomSeed(event.currentTarget.value)
                  }}
                />
              </label>
              <div>
                <button
                  data-testid="trading-lab-generate-random"
                  type="button"
                  disabled={locked}
                  onClick={handleGenerateRandom}
                >
                  随机生成场景
                </button>
                <button
                  data-testid="trading-lab-rerandomize"
                  type="button"
                  disabled={locked}
                  onClick={handleRerandomize}
                >
                  重新随机
                </button>
              </div>
              <label>
                场景 SHA-256
                <output data-testid="trading-lab-scenario-hash">
                  {scenarioHash || '正在计算…'}
                </output>
              </label>
              <label>
                名称
                <input
                  value={activeScenario.name}
                  disabled={locked}
                  onChange={(event) => {
                    const name = event.currentTarget.value
                    replaceScenario((current) => ({ ...current, name }))
                  }}
                />
              </label>
              <label>
                说明
                <textarea
                  value={activeScenario.description}
                  disabled={locked}
                  onChange={(event) => {
                    const description = event.currentTarget.value
                    replaceScenario((current) => ({
                      ...current,
                      description,
                    }))
                  }}
                />
              </label>
              <label className="trading-lab-checkbox-row">
                <input
                  type="checkbox"
                  checked={activeScenario.negativeMode}
                  disabled={locked}
                  onChange={(event) => {
                    const negativeMode = event.currentTarget.checked
                    replaceScenario((current) => ({
                      ...current,
                      negativeMode,
                    }))
                  }}
                />
                负向模式
              </label>
              <dl className="trading-lab-scenario-summary">
                <div>
                  <dt>品种</dt>
                  <dd>{activeScenario.symbols.length}</dd>
                </div>
                <div>
                  <dt>动作</dt>
                  <dd>{activeScenario.timeline.length}</dd>
                </div>
                <div>
                  <dt>校验问题</dt>
                  <dd>{issues.length}</dd>
                </div>
              </dl>
            </aside>

            <main className="trading-lab-panel trading-lab-timeline-panel">
              <TimelineEditor
                actions={activeScenario.timeline}
                locked={locked}
                issues={issues}
                onChange={handleTimelineChange}
              />
            </main>

            <aside className="trading-lab-panel trading-lab-expected-panel">
              {activeScenario.negativeMode ? <NegativeModeBanner /> : null}
              <LocalExpectedPanel state={localExpected} />
            </aside>
          </div>

          <TradingLabChartSection
            scenario={activeScenario}
            localExpected={localExpected}
            actualTicks={chartEvidence.actualTicks}
            markers={chartEvidence.markers}
          />
        </div>
      )}
    </div>
  )
}
