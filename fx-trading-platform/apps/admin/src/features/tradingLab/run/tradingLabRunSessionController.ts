import { ApiClientError } from '../../../services/apiClient.ts'
import {
  TradingLabApiContractError,
  type TradingLabJsonObject,
  type TradingLabRunControlAction,
  type TradingLabRunControlResult,
  type TradingLabRunCreateInput,
  type TradingLabRunResponse,
  type TradingLabScenarioCreateInput,
  type TradingLabScenarioResponse,
} from '../api/tradingLabApi.ts'
import {
  TradingLabStreamError,
  type TradingLabStreamHandlers,
  type TradingLabStreamOptions,
  type TradingLabStreamResult,
} from '../api/tradingLabStream.ts'
import type {
  BlockedOutcome,
  CalculatedOutcome,
} from '../localExpected/localOracleScheduler.ts'
import type { TradingLabScenario } from '../model/types.ts'
import {
  canRunScenario,
  validateScenario,
} from '../model/validation.ts'
import {
  createTradingLabRunSession,
  reduceTradingLabRunSession,
  type TradingLabRunSession,
  type TradingLabRunSessionAction,
  type TradingLabRunSessionRun,
} from './tradingLabRunSession.ts'
import type { TradingLabRunLocation } from './tradingLabRunUrl.ts'

const RETRY_DELAYS_MILLISECONDS = [250, 500, 1_000, 2_000, 5_000] as const
const TERMINAL_RUN_STATES = new Set(['COMPLETED', 'FAILED', 'CANCELLED'])
const ACTIVE_REFRESH_RUN_STATES = new Set([
  'QUEUED',
  'RESETTING',
  'RUNNING',
  'PAUSED',
  'CANCELLING',
])
const ACTIVE_REFRESH_CONNECTION_STATES = new Set([
  'CONNECTING',
  'OPEN',
  'RETRY_WAIT',
])
const VALIDATION_PUBLIC_ACTIONS = new Set([
  'PLACE_ORDER',
  'CANCEL_ORDER',
  'CANCEL_ALL',
  'SET_POSITION_MODE',
  'SET_MARGIN_MODE',
  'SET_LEVERAGE',
])

export type TradingLabRunRequestState =
  | 'IDLE'
  | 'CREATING'
  | 'CREATE_UNKNOWN'

export type TradingLabRunRequest = Readonly<{
  scenario: TradingLabScenario
  localCalculation: CalculatedOutcome | BlockedOutcome
}>

export type TradingLabRunSessionAccess = Readonly<{
  canView: boolean
  canExecute: boolean
}>

export type TradingLabRunSessionControllerSnapshot = Readonly<{
  runId: string | null
  locationError: string | null
  session: TradingLabRunSession | null
  runRequestState: TradingLabRunRequestState
  controlPending: TradingLabRunControlAction | null
  message: string | null
}>

export type TradingLabRunSessionControllerDependencies = Readonly<{
  getToken(): string | null
  getRun(runId: string, token: string): Promise<TradingLabRunResponse>
  getScenario(
    scenarioId: string,
    token: string,
  ): Promise<TradingLabScenarioResponse>
  createScenario(
    input: TradingLabScenarioCreateInput,
    token: string,
  ): Promise<TradingLabScenarioResponse>
  createRun(
    scenarioId: string,
    input: TradingLabRunCreateInput,
    token: string,
  ): Promise<TradingLabRunResponse>
  controlRun(
    runId: string,
    action: TradingLabRunControlAction,
    token: string,
  ): Promise<TradingLabRunControlResult>
  streamRun(
    runId: string,
    handlers: TradingLabStreamHandlers,
    options: TradingLabStreamOptions,
  ): Promise<TradingLabStreamResult>
  normalizeScenario(input: unknown): Promise<TradingLabScenario>
  writeRunLocation(runId: string): TradingLabRunLocation
  setTimer(callback: () => void, milliseconds: number): unknown
  clearTimer(handle: unknown): void
  enqueueMicrotask(callback: () => void): void
}>

export type TradingLabRunSessionController = Readonly<{
  getSnapshot(): TradingLabRunSessionControllerSnapshot
  subscribe(listener: () => void): () => void
  setAccess(access: TradingLabRunSessionAccess): void
  setLocation(location: TradingLabRunLocation): void
  requestRun(request: TradingLabRunRequest): Promise<void>
  controlRun(action: TradingLabRunControlAction): Promise<void>
  acceptAuthoritativeRun(run: TradingLabRunSessionRun): void
  dispose(): void
}>

export function createTradingLabRunSessionController(
  dependencies: TradingLabRunSessionControllerDependencies,
): TradingLabRunSessionController {
  requireDependencies(dependencies)

  const listeners = new Set<() => void>()
  let access: TradingLabRunSessionAccess = {
    canView: false,
    canExecute: false,
  }
  let location: TradingLabRunLocation = {
    runId: null,
    error: null,
  }
  let snapshot: TradingLabRunSessionControllerSnapshot = Object.freeze({
    runId: null,
    locationError: null,
    session: null,
    runRequestState: 'IDLE',
    controlPending: null,
    message: null,
  })
  let session: TradingLabRunSession | null = null
  let ownerGeneration = 0
  let abortController: AbortController | null = null
  let retryTimer: unknown | null = null
  let retryAttempt = 0
  let terminalDrainAttempted = false
  let createPending = false
  let controlPending: TradingLabRunControlAction | null = null
  let controlAcceptedRunVersion: number | null = null
  let activeControlAttempt: Readonly<{
    ownerGeneration: number
    runId: string
    action: TradingLabRunControlAction
  }> | null = null
  let activeRefreshOwner: {
    generation: number
    runId: string
    inFlight: boolean
    pending: boolean
    initialActivityObserved: boolean
  } | null = null
  let disposed = false

  const publish = (
    patch: Partial<TradingLabRunSessionControllerSnapshot>,
  ): void => {
    if (disposed) {
      return
    }
    snapshot = Object.freeze({
      ...snapshot,
      ...patch,
    })
    for (const listener of listeners) {
      listener()
    }
  }

  const replaceSession = (next: TradingLabRunSession | null): void => {
    session = next
    publish({ session: next })
  }

  const settleAcceptedControl = (
    next: TradingLabRunSession,
  ): void => {
    if (
      controlPending === null
      || controlAcceptedRunVersion === null
      || next.connection === 'BLOCKED'
      || next.run === null
      || next.run.version < controlAcceptedRunVersion
      || (
        !controlFlagsConfirm(next.run, controlPending)
        && (
          controlPending === 'cancel'
          || next.run.version === controlAcceptedRunVersion
        )
      )
    ) {
      return
    }
    controlPending = null
    controlAcceptedRunVersion = null
    activeControlAttempt = null
    publish({
      controlPending: null,
      message: null,
    })
  }

  const applySessionAction = (
    action: TradingLabRunSessionAction,
  ): TradingLabRunSession | null => {
    if (session === null) {
      return null
    }
    const next = reduceTradingLabRunSession(session, action)
    if (next !== session) {
      session = next
      publish({ session: next })
    }
    settleAcceptedControl(next)
    return next
  }

  const stopTransport = (): void => {
    if (retryTimer !== null) {
      dependencies.clearTimer(retryTimer)
      retryTimer = null
    }
    if (abortController !== null) {
      abortController.abort()
      abortController = null
    }
  }

  const isCurrentOwner = (
    expectedGeneration: number,
    expectedRunId: string,
  ): boolean => (
    !disposed
    && ownerGeneration === expectedGeneration
    && location.runId === expectedRunId
    && location.error === null
    && access.canView
  )

  const scoped = <Action extends Omit<
    TradingLabRunSessionAction,
    'ownerGeneration' | 'runId'
  >>(
    expectedGeneration: number,
    expectedRunId: string,
    action: Action,
  ): TradingLabRunSessionAction => ({
    ...action,
    ownerGeneration: expectedGeneration,
    runId: expectedRunId,
  } as TradingLabRunSessionAction)

  const dispatch = <Action extends Omit<
    TradingLabRunSessionAction,
    'ownerGeneration' | 'runId'
  >>(
    expectedGeneration: number,
    expectedRunId: string,
    action: Action,
  ): TradingLabRunSession | null => (
    isCurrentOwner(expectedGeneration, expectedRunId)
      ? applySessionAction(scoped(
          expectedGeneration,
          expectedRunId,
          action,
        ))
      : null
  )

  const block = (
    expectedGeneration: number,
    expectedRunId: string,
    code: string,
    detail: string,
  ): void => {
    if (!isCurrentOwner(expectedGeneration, expectedRunId)) {
      return
    }
    dispatch(expectedGeneration, expectedRunId, {
      type: 'STREAM_BLOCKED',
      issue: { code, message: detail },
    })
    publish({ message: detail })
  }

  const token = (
    expectedGeneration: number,
    expectedRunId: string,
  ): string | null => {
    let value: string | null
    try {
      value = dependencies.getToken()
    } catch {
      block(
        expectedGeneration,
        expectedRunId,
        'TRADING_LAB_ADMIN_SESSION_REQUIRED',
        '管理员会话不可用，无法恢复 Trading Lab Run。',
      )
      return null
    }
    if (value === null) {
      block(
        expectedGeneration,
        expectedRunId,
        'TRADING_LAB_ADMIN_SESSION_REQUIRED',
        '管理员会话不可用，无法恢复 Trading Lab Run。',
      )
    }
    return value
  }

  const requestActiveRunRefresh = (
    expectedGeneration: number,
    expectedRunId: string,
  ): void => {
    const refreshOwner = activeRefreshOwner
    const runAtRequest = session?.run ?? null
    if (
      refreshOwner === null
      || refreshOwner.generation !== expectedGeneration
      || refreshOwner.runId !== expectedRunId
      || session === null
      || !ACTIVE_REFRESH_CONNECTION_STATES.has(session.connection)
      || runAtRequest === null
      || !ACTIVE_REFRESH_RUN_STATES.has(runAtRequest.state)
      || !isCurrentOwner(expectedGeneration, expectedRunId)
    ) {
      return
    }
    if (refreshOwner.inFlight) {
      refreshOwner.pending = true
      return
    }

    refreshOwner.inFlight = true
    void (async () => {
      const currentToken = token(expectedGeneration, expectedRunId)
      if (
        currentToken === null
        || !isCurrentOwner(expectedGeneration, expectedRunId)
        || activeRefreshOwner !== refreshOwner
      ) {
        return
      }
      try {
        const refreshed = await dependencies.getRun(
          expectedRunId,
          currentToken,
        )
        const current = session
        if (
          !isCurrentOwner(expectedGeneration, expectedRunId)
          || activeRefreshOwner !== refreshOwner
          || current === null
          || !ACTIVE_REFRESH_CONNECTION_STATES.has(current.connection)
        ) {
          return
        }
        const next = dispatch(expectedGeneration, expectedRunId, {
          type: 'ATTACH_RUN',
          run: refreshed,
        })
        if (next?.connection === 'BLOCKED') {
          publish({
            message:
              next.transportIssue?.message
              ?? 'durable 活动后的权威 Run 响应不能附加。',
          })
        }
      } catch (error) {
        const current = session
        if (
          !isCurrentOwner(expectedGeneration, expectedRunId)
          || activeRefreshOwner !== refreshOwner
          || current === null
          || !ACTIVE_REFRESH_CONNECTION_STATES.has(current.connection)
          || current.run !== runAtRequest
        ) {
          return
        }
        if (isNonRetryableApiFailure(error)) {
          block(
            expectedGeneration,
            expectedRunId,
            'TRADING_LAB_ACTIVE_REFRESH_BLOCKED',
            errorMessage(error, 'durable 活动后的 Run 状态读取失败'),
          )
          return
        }
        refreshOwner.initialActivityObserved = false
        publish({
          message:
            `${errorMessage(error, '主 Run 状态暂时不可用')}；`
            + '等待下一条 durable event 重试。',
        })
      }
    })().finally(() => {
      if (activeRefreshOwner !== refreshOwner) {
        return
      }
      refreshOwner.inFlight = false
      const shouldRefreshAgain = (
        refreshOwner.pending
        && session !== null
        && ACTIVE_REFRESH_CONNECTION_STATES.has(session.connection)
        && session.run !== null
        && ACTIVE_REFRESH_RUN_STATES.has(session.run.state)
      )
      refreshOwner.pending = false
      if (shouldRefreshAgain) {
        requestActiveRunRefresh(expectedGeneration, expectedRunId)
      }
    })
  }

  const observeDurableActivity = (
    expectedGeneration: number,
    expectedRunId: string,
    eventName: string,
    next: TradingLabRunSession | null,
  ): void => {
    const refreshOwner = activeRefreshOwner
    if (
      refreshOwner === null
      || refreshOwner.generation !== expectedGeneration
      || refreshOwner.runId !== expectedRunId
      || next === null
      || next.run === null
      || !ACTIVE_REFRESH_RUN_STATES.has(next.run.state)
    ) {
      return
    }
    const initialActivity = !refreshOwner.initialActivityObserved
    if (initialActivity) {
      refreshOwner.initialActivityObserved = true
    }
    if (initialActivity || eventName === 'state') {
      requestActiveRunRefresh(expectedGeneration, expectedRunId)
    }
  }

  const refreshFinalRun = async (
    expectedGeneration: number,
    expectedRunId: string,
    attempt = 0,
  ): Promise<void> => {
    const currentToken = token(expectedGeneration, expectedRunId)
    if (
      currentToken === null
      || !isCurrentOwner(expectedGeneration, expectedRunId)
    ) {
      return
    }
    try {
      const refreshed = await dependencies.getRun(
        expectedRunId,
        currentToken,
      )
      if (!isCurrentOwner(expectedGeneration, expectedRunId)) {
        return
      }
      const next = dispatch(expectedGeneration, expectedRunId, {
        type: 'FINAL_RUN_REFRESH',
        run: refreshed,
      })
      if (next?.connection === 'CLOSED') {
        publish({ message: null })
      } else if (next?.transportIssue != null) {
        publish({ message: next.transportIssue.message })
      }
    } catch (error) {
      if (!isCurrentOwner(expectedGeneration, expectedRunId)) {
        return
      }
      if (
        isTransientApiFailure(error)
        && attempt < RETRY_DELAYS_MILLISECONDS.length
      ) {
        const delay = RETRY_DELAYS_MILLISECONDS[attempt]
        publish({
          message:
            `${errorMessage(error, '最终 Run 状态暂时不可用')}；`
            + `${delay}ms 后重试 final GET。`,
        })
        if (retryTimer !== null) {
          dependencies.clearTimer(retryTimer)
        }
        retryTimer = dependencies.setTimer(() => {
          retryTimer = null
          if (isCurrentOwner(expectedGeneration, expectedRunId)) {
            void refreshFinalRun(
              expectedGeneration,
              expectedRunId,
              attempt + 1,
            )
          }
        }, delay)
        return
      }
      block(
        expectedGeneration,
        expectedRunId,
        'TRADING_LAB_FINAL_REFRESH_FAILED',
        `${errorMessage(error, '最终 Run 状态读取失败')}；请刷新页面重新恢复。`,
      )
    }
  }

  const scheduleRetry = (
    expectedGeneration: number,
    expectedRunId: string,
    lastEventId: string | undefined,
    issue: Readonly<{ code: string; message: string }>,
  ): void => {
    if (!isCurrentOwner(expectedGeneration, expectedRunId)) {
      return
    }
    if (retryAttempt >= RETRY_DELAYS_MILLISECONDS.length) {
      block(
        expectedGeneration,
        expectedRunId,
        'TRADING_LAB_STREAM_RETRY_EXHAUSTED',
        'Trading Lab durable stream 在限定重连次数内未恢复。',
      )
      return
    }
    const delay = RETRY_DELAYS_MILLISECONDS[retryAttempt]
    retryAttempt += 1
    dispatch(expectedGeneration, expectedRunId, {
      type: 'STREAM_RETRY_WAIT',
      issue,
    })
    publish({ message: `${issue.message}；${delay}ms 后重连。` })
    if (retryTimer !== null) {
      dependencies.clearTimer(retryTimer)
    }
    retryTimer = dependencies.setTimer(() => {
      retryTimer = null
      if (isCurrentOwner(expectedGeneration, expectedRunId)) {
        void connect(expectedGeneration, expectedRunId, lastEventId)
      }
    }, delay)
  }

  const refreshBeforeReconnect = async (
    expectedGeneration: number,
    expectedRunId: string,
    failure: TradingLabStreamError,
    lastEventId: string | undefined,
  ): Promise<void> => {
    const currentToken = token(expectedGeneration, expectedRunId)
    if (
      currentToken === null
      || !isCurrentOwner(expectedGeneration, expectedRunId)
    ) {
      return
    }

    let refreshed: TradingLabRunResponse
    try {
      refreshed = await dependencies.getRun(
        expectedRunId,
        currentToken,
      )
    } catch (error) {
      if (!isCurrentOwner(expectedGeneration, expectedRunId)) {
        return
      }
      if (isNonRetryableApiFailure(error)) {
        block(
          expectedGeneration,
          expectedRunId,
          'TRADING_LAB_RUN_REFRESH_BLOCKED',
          errorMessage(error, '重连前的 Run 状态读取失败'),
        )
        return
      }
      scheduleRetry(
        expectedGeneration,
        expectedRunId,
        lastEventId,
        {
          code: 'TRADING_LAB_RUN_REFRESH_TRANSIENT',
          message: errorMessage(error, '重连前的 Run 状态暂时不可用'),
        },
      )
      return
    }

    if (!isCurrentOwner(expectedGeneration, expectedRunId)) {
      return
    }
    const attached = dispatch(expectedGeneration, expectedRunId, {
      type: 'ATTACH_RUN',
      run: refreshed,
    })
    if (attached?.connection === 'BLOCKED') {
      publish({
        message:
          attached.transportIssue?.message
          ?? '重连前的权威 Run 响应不能附加。',
      })
      return
    }
    if (TERMINAL_RUN_STATES.has(refreshed.state)) {
      if (terminalDrainAttempted) {
        block(
          expectedGeneration,
          expectedRunId,
          'TRADING_LAB_TERMINAL_COMPLETE_MISSING',
          '主 Run 已终态，但 durable stream 未提供 complete 控制帧。',
        )
        return
      }
      terminalDrainAttempted = true
      void connect(expectedGeneration, expectedRunId, lastEventId)
      return
    }

    scheduleRetry(
      expectedGeneration,
      expectedRunId,
      lastEventId,
      {
        code: `TRADING_LAB_STREAM_${failure.kind}`,
        message: failure.message,
      },
    )
  }

  async function connect(
    expectedGeneration: number,
    expectedRunId: string,
    lastEventId?: string,
  ): Promise<void> {
    if (!isCurrentOwner(expectedGeneration, expectedRunId)) {
      return
    }
    if (retryTimer !== null) {
      dependencies.clearTimer(retryTimer)
      retryTimer = null
    }
    if (abortController !== null) {
      abortController.abort()
    }

    const controller = new AbortController()
    abortController = controller
    dispatch(expectedGeneration, expectedRunId, {
      type: 'STREAM_CONNECTING',
    })

    try {
      const result = await dependencies.streamRun(
        expectedRunId,
        {
          onEvent(event) {
            if (
              !isCurrentOwner(expectedGeneration, expectedRunId)
              || abortController !== controller
            ) {
              return
            }
            if (event.id !== null) {
              retryAttempt = 0
            }
            dispatch(expectedGeneration, expectedRunId, {
              type: 'STREAM_OPEN',
            })
            const next = dispatch(expectedGeneration, expectedRunId, {
              type: 'STREAM_EVENT',
              event: {
                id: event.id,
                name: event.event,
                data: event.data,
              },
            })
            if (next?.connection === 'BLOCKED') {
              throw new Error(
                next.transportIssue?.message
                ?? 'Trading Lab durable event projection failed',
              )
            }
            if (event.id !== null) {
              observeDurableActivity(
                expectedGeneration,
                expectedRunId,
                event.event,
                next,
              )
            }
          },
        },
        {
          signal: controller.signal,
          ...(lastEventId === undefined ? {} : { lastEventId }),
        },
      )

      if (
        !isCurrentOwner(expectedGeneration, expectedRunId)
        || abortController !== controller
      ) {
        return
      }
      abortController = null
      if (result.kind === 'FINAL_REFRESH_REQUIRED') {
        await refreshFinalRun(expectedGeneration, expectedRunId)
      }
    } catch (error) {
      if (
        !isCurrentOwner(expectedGeneration, expectedRunId)
        || abortController !== controller
      ) {
        return
      }
      abortController = null
      if (controller.signal.aborted) {
        return
      }
      if (error instanceof TradingLabStreamError && error.retryable) {
        const cursor = error.lastEventId
          ?? session?.highestDurableEventId
          ?? undefined
        await refreshBeforeReconnect(
          expectedGeneration,
          expectedRunId,
          error,
          cursor,
        )
        return
      }
      const issue = error instanceof TradingLabStreamError
        ? {
            code: `TRADING_LAB_STREAM_${error.kind}`,
            message: error.message,
          }
        : {
            code: 'TRADING_LAB_STREAM_PROTOCOL',
            message: errorMessage(
              error,
              'Trading Lab durable stream 无法继续',
            ),
          }
      block(
        expectedGeneration,
        expectedRunId,
        issue.code,
        issue.message,
      )
    }
  }

  const attach = async (
    expectedGeneration: number,
    expectedRunId: string,
  ): Promise<void> => {
    const currentToken = token(expectedGeneration, expectedRunId)
    if (
      currentToken === null
      || !isCurrentOwner(expectedGeneration, expectedRunId)
    ) {
      return
    }
    try {
      const run = await dependencies.getRun(expectedRunId, currentToken)
      if (!isCurrentOwner(expectedGeneration, expectedRunId)) {
        return
      }
      const attached = dispatch(expectedGeneration, expectedRunId, {
        type: 'ATTACH_RUN',
        run,
      })
      if (attached?.connection === 'BLOCKED') {
        publish({
          message:
            attached.transportIssue?.message
            ?? '权威 Run 响应不能附加。',
        })
        return
      }

      const scenarioToken = token(expectedGeneration, expectedRunId)
      if (
        scenarioToken === null
        || !isCurrentOwner(expectedGeneration, expectedRunId)
      ) {
        return
      }
      const frozen = await dependencies.getScenario(
        run.scenarioId,
        scenarioToken,
      )
      if (!isCurrentOwner(expectedGeneration, expectedRunId)) {
        return
      }
      if (frozen.id !== run.scenarioId) {
        block(
          expectedGeneration,
          expectedRunId,
          'TRADING_LAB_SCENARIO_ID_MISMATCH',
          'Run 与服务端冻结场景的 identity 不一致。',
        )
        return
      }
      if (frozen.status !== 'FROZEN') {
        block(
          expectedGeneration,
          expectedRunId,
          'TRADING_LAB_SCENARIO_NOT_FROZEN',
          'Run 引用的服务端场景尚未冻结。',
        )
        return
      }

      const normalized = await dependencies.normalizeScenario(
        frozen.scenario,
      )
      if (!isCurrentOwner(expectedGeneration, expectedRunId)) {
        return
      }
      if (
        normalized.configSnapshotHash !== frozen.configSnapshotHash
        || normalized.modelVersion !== frozen.modelVersion
        || run.configSnapshotHash !== frozen.configSnapshotHash
        || run.modelVersion !== frozen.modelVersion
        || run.symbolConfigVersion !== frozen.symbolConfigVersion
        || run.codeVersion !== frozen.codeVersion
      ) {
        block(
          expectedGeneration,
          expectedRunId,
          'TRADING_LAB_SCENARIO_METADATA_MISMATCH',
          '服务端冻结场景与其权威 metadata 不一致。',
        )
        return
      }
      const restored = dispatch(expectedGeneration, expectedRunId, {
        type: 'RESTORE_SCENARIO',
        scenario: normalized,
      })
      if (restored?.connection === 'BLOCKED') {
        publish({
          message:
            restored.transportIssue?.message
            ?? '服务端冻结场景不能恢复。',
        })
        return
      }
      publish({ message: null })
      await connect(expectedGeneration, expectedRunId)
    } catch (error) {
      if (isCurrentOwner(expectedGeneration, expectedRunId)) {
        block(
          expectedGeneration,
          expectedRunId,
          'TRADING_LAB_RESTORE_FAILED',
          errorMessage(error, 'Trading Lab Run 恢复失败'),
        )
      }
    }
  }

  const replaceOwner = (): void => {
    ownerGeneration += 1
    const expectedGeneration = ownerGeneration
    const expectedRunId = location.runId

    retryAttempt = 0
    terminalDrainAttempted = false
    activeRefreshOwner = null
    stopTransport()
    controlPending = null
    controlAcceptedRunVersion = null
    activeControlAttempt = null
    if (
      disposed
      || ownerGeneration !== expectedGeneration
      || location.runId !== expectedRunId
    ) {
      return
    }

    if (
      !access.canView
      || location.error !== null
      || expectedRunId === null
    ) {
      session = null
      const messagePatch: Partial<TradingLabRunSessionControllerSnapshot> = (
        location.error !== null
          ? { message: location.error }
          : !createPending
            ? { message: null }
            : {}
      )
      publish({
        session: null,
        controlPending: null,
        ...messagePatch,
      })
      return
    }

    session = createTradingLabRunSession(
      expectedRunId,
      expectedGeneration,
    )
    activeRefreshOwner = {
      generation: expectedGeneration,
      runId: expectedRunId,
      inFlight: false,
      pending: false,
      initialActivityObserved: false,
    }
    publish({
      session,
      controlPending: null,
      message: '正在恢复 Run 与冻结场景…',
    })
    if (!isCurrentOwner(expectedGeneration, expectedRunId)) {
      return
    }
    dependencies.enqueueMicrotask(() => {
      if (isCurrentOwner(expectedGeneration, expectedRunId)) {
        void attach(expectedGeneration, expectedRunId)
      }
    })
  }

  const setAccess = (next: TradingLabRunSessionAccess): void => {
    if (disposed) {
      return
    }
    requireAccess(next)
    if (
      access.canView === next.canView
      && access.canExecute === next.canExecute
    ) {
      return
    }
    const viewChanged = access.canView !== next.canView
    access = Object.freeze({ ...next })
    if (viewChanged) {
      replaceOwner()
    }
  }

  const setLocation = (next: TradingLabRunLocation): void => {
    if (disposed) {
      return
    }
    requireLocation(next)
    if (
      location.runId === next.runId
      && location.error === next.error
    ) {
      return
    }
    location = Object.freeze({ ...next })
    publish({
      runId: location.runId,
      locationError: location.error,
    })
    replaceOwner()
  }

  const requestRun = async (
    request: TradingLabRunRequest,
  ): Promise<void> => {
    if (
      disposed
      || !access.canExecute
      || !access.canView
      || location.error !== null
      || location.runId !== null
      || createPending
    ) {
      return
    }

    const expectedGeneration = ownerGeneration
    const ownsCreateResultContext = (): boolean => (
      !disposed
      && createPending
      && ownerGeneration === expectedGeneration
      && access.canView
      && location.error === null
      && location.runId === null
    )
    const canSendCreateMutation = (): boolean => (
      ownsCreateResultContext()
      && access.canExecute
    )
    const creatingMessage = '正在校验并创建冻结场景…'
    const stopBeforeRunPost = (
      message: string | null = null,
    ): void => {
      const messagePatch: Partial<
        TradingLabRunSessionControllerSnapshot
      > = (
        snapshot.runRequestState === 'CREATING'
        && snapshot.message === creatingMessage
          ? { message }
          : {}
      )
      createPending = false
      publish({
        runRequestState: 'IDLE',
        ...messagePatch,
      })
    }
    createPending = true
    publish({
      runRequestState: 'CREATING',
      message: creatingMessage,
    })
    if (!canSendCreateMutation()) {
      stopBeforeRunPost()
      return
    }
    let requestSent = false

    try {
      const normalized = await dependencies.normalizeScenario(
        request.scenario,
      )
      if (!canSendCreateMutation()) {
        stopBeforeRunPost()
        return
      }
      requireRunnableRequest(normalized, request.localCalculation)

      const currentToken = dependencies.getToken()
      if (currentToken === null) {
        throw new Error('管理员会话不可用，不能创建 Trading Lab Run。')
      }
      if (!canSendCreateMutation()) {
        stopBeforeRunPost()
        return
      }

      const scenarioInput = {
        name: normalized.name,
        description: normalized.description,
        negativeMode: normalized.negativeMode,
        seed: normalized.seed,
        modelVersion: normalized.modelVersion,
        scenario: jsonObject(normalized),
        configSnapshot: jsonObject(normalized.configSnapshot),
        configSnapshotHash: normalized.configSnapshotHash,
      }
      if (!canSendCreateMutation()) {
        stopBeforeRunPost()
        return
      }
      requestSent = true
      const createdScenario = await dependencies.createScenario(
        scenarioInput,
        currentToken,
      )
      if (
        createdScenario.status !== 'DRAFT'
        || createdScenario.configSnapshotHash
          !== normalized.configSnapshotHash
        || createdScenario.modelVersion !== normalized.modelVersion
      ) {
        throw new TradingLabApiContractError(
          '已创建场景的权威 metadata 与提交内容不一致。',
        )
      }
      if (!canSendCreateMutation()) {
        stopBeforeRunPost()
        return
      }
      requestSent = false

      const runToken = dependencies.getToken()
      if (runToken === null) {
        stopBeforeRunPost('管理员会话不可用，Run 尚未创建。')
        return
      }
      if (!canSendCreateMutation()) {
        stopBeforeRunPost()
        return
      }
      const runInput = {
        scenarioVersion: createdScenario.version,
        configSnapshotHash: createdScenario.configSnapshotHash,
        localCalculation: jsonObject(request.localCalculation),
      }
      if (!canSendCreateMutation()) {
        stopBeforeRunPost()
        return
      }
      requestSent = true
      const run = await dependencies.createRun(
        createdScenario.id,
        runInput,
        runToken,
      )
      if (disposed) {
        return
      }
      if (!ownsCreateResultContext()) {
        publish({
          runRequestState: 'CREATE_UNKNOWN',
          message:
            'Run 已返回但页面 owner 已变化；'
            + '为避免重复创建，提交保持锁定。',
        })
        return
      }
      if (
        run.scenarioId !== createdScenario.id
        || run.configSnapshotHash !== createdScenario.configSnapshotHash
        || run.modelVersion !== createdScenario.modelVersion
        || run.symbolConfigVersion !== createdScenario.symbolConfigVersion
        || run.codeVersion !== createdScenario.codeVersion
      ) {
        throw new TradingLabApiContractError(
          '已创建 Run 的权威 metadata 与冻结场景不一致。',
        )
      }

      const nextLocation = dependencies.writeRunLocation(run.id)
      if (nextLocation.runId !== run.id || nextLocation.error !== null) {
        throw new TradingLabApiContractError(
          'Trading Lab created Run location mismatch',
        )
      }
      if (!ownsCreateResultContext()) {
        publish({
          runRequestState: 'CREATE_UNKNOWN',
          message:
            'Run 已返回但页面 owner 已变化；'
            + '为避免重复创建，提交保持锁定。',
        })
        return
      }
      createPending = false
      setLocation(nextLocation)
      publish({ runRequestState: 'IDLE' })
    } catch (error) {
      if (disposed) {
        return
      }
      if (!requestSent && !canSendCreateMutation()) {
        stopBeforeRunPost()
        return
      }
      if (!requestSent || isExplicitApiFailure(error)) {
        createPending = false
        publish({
          runRequestState: 'IDLE',
          message: errorMessage(error, 'Trading Lab Run 创建失败'),
        })
        return
      }
      publish({
        runRequestState: 'CREATE_UNKNOWN',
        message:
          `${errorMessage(error, '创建响应结果不确定')}；`
          + '为避免重复 Run，禁止再次提交。',
      })
    }
  }

  const controlRun = async (
    action: TradingLabRunControlAction,
  ): Promise<void> => {
    const current = session
    if (
      disposed
      || !access.canExecute
      || !access.canView
      || current === null
      || current.run === null
      || current.ownerGeneration !== ownerGeneration
      || current.runId !== location.runId
      || !ACTIVE_REFRESH_CONNECTION_STATES.has(current.connection)
      || controlPending !== null
      || !controlAllowed(current.run, action)
    ) {
      return
    }

    let currentToken: string | null
    try {
      currentToken = dependencies.getToken()
    } catch (error) {
      publish({
        message: errorMessage(error, '管理员会话不可用，不能发送运行控制。'),
      })
      return
    }
    if (currentToken === null) {
      publish({ message: '管理员会话不可用，不能发送运行控制。' })
      return
    }

    const expectedGeneration = current.ownerGeneration
    const expectedRunId = current.runId
    const requestRunVersion = current.run.version
    const expectedControlAttempt = Object.freeze({
      ownerGeneration: expectedGeneration,
      runId: expectedRunId,
      action,
    })
    controlPending = action
    controlAcceptedRunVersion = null
    activeControlAttempt = expectedControlAttempt
    publish({
      controlPending: action,
      message: `正在提交 ${action} 请求…`,
    })
    if (
      disposed
      || !access.canExecute
      || !access.canView
      || session !== current
      || !isCurrentOwner(expectedGeneration, expectedRunId)
      || activeControlAttempt !== expectedControlAttempt
    ) {
      if (activeControlAttempt === expectedControlAttempt) {
        controlPending = null
        controlAcceptedRunVersion = null
        activeControlAttempt = null
        publish({
          controlPending: null,
          message: null,
        })
      }
      return
    }
    let requestSent = false
    let controlAccepted = false
    try {
      requestSent = true
      const result = await dependencies.controlRun(
        expectedRunId,
        action,
        currentToken,
      )
      if (!isCurrentOwner(expectedGeneration, expectedRunId)) {
        return
      }
      if (result.runId !== expectedRunId) {
        throw new TradingLabApiContractError(
          'Trading Lab control response runId mismatch',
        )
      }
      if (
        !Number.isSafeInteger(result.runVersion)
        || result.runVersion <= requestRunVersion
      ) {
        throw new TradingLabApiContractError(
          'Trading Lab control response did not advance the authoritative Run version',
        )
      }
      if (!controlFlagsConfirm(result, action)) {
        throw new TradingLabApiContractError(
          'Trading Lab control response flags do not confirm the requested action',
        )
      }
      controlAccepted = true
      controlAcceptedRunVersion = result.runVersion
      if (session !== null) {
        settleAcceptedControl(session)
      }
      if (activeControlAttempt !== expectedControlAttempt) {
        return
      }

      const refreshToken = dependencies.getToken()
      if (refreshToken === null) {
        throw new Error('管理员会话不可用，控制后的 Run 状态尚未确认。')
      }
      const refreshed = await dependencies.getRun(
        expectedRunId,
        refreshToken,
      )
      if (
        !isCurrentOwner(expectedGeneration, expectedRunId)
        || activeControlAttempt !== expectedControlAttempt
      ) {
        return
      }
      const currentRunVersion = session?.run?.version ?? null
      if (
        refreshed.version < result.runVersion
        && (
          currentRunVersion === null
          || currentRunVersion < result.runVersion
        )
      ) {
        throw new TradingLabApiContractError(
          'Trading Lab control follow-up Run version predates the accepted mutation watermark',
        )
      }
      const next = dispatch(expectedGeneration, expectedRunId, {
        type: 'ATTACH_RUN',
        run: refreshed,
      })
      if (next === null) {
        return
      }
      if (next.connection === 'BLOCKED') {
        publish({
          message:
            next.transportIssue?.message
            ?? '控制后的权威 Run 响应不能附加。',
        })
        return
      }
      if (activeControlAttempt === expectedControlAttempt) {
        throw new TradingLabApiContractError(
          'Trading Lab control follow-up Run flags do not confirm the accepted action',
        )
      }
    } catch (error) {
      if (
        isCurrentOwner(expectedGeneration, expectedRunId)
        && activeControlAttempt === expectedControlAttempt
      ) {
        const knownRejected = (
          requestSent
          && !controlAccepted
          && isExplicitApiFailure(error)
        )
        if (!requestSent || knownRejected) {
          controlPending = null
          controlAcceptedRunVersion = null
          activeControlAttempt = null
          publish({
            controlPending: null,
            message: errorMessage(error, 'Trading Lab 运行控制失败'),
          })
        } else {
          publish({
            message:
              `${errorMessage(error, '控制结果尚未完成权威 GET 确认')}；`
              + '控制保持锁定，请刷新恢复。',
          })
        }
      }
    }
  }

  const acceptAuthoritativeRun = (run: TradingLabRunSessionRun): void => {
    if (
      session === null
      || location.runId === null
      || run.id !== location.runId
    ) {
      return
    }
    dispatch(ownerGeneration, location.runId, {
      type: 'ATTACH_RUN',
      run,
    })
  }

  return Object.freeze({
    getSnapshot: () => snapshot,
    subscribe(listener: () => void) {
      if (disposed) {
        return () => {}
      }
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    setAccess,
    setLocation,
    requestRun,
    controlRun,
    acceptAuthoritativeRun,
    dispose() {
      if (disposed) {
        return
      }
      disposed = true
      ownerGeneration += 1
      activeRefreshOwner = null
      activeControlAttempt = null
      stopTransport()
      listeners.clear()
    },
  })
}

function requireRunnableRequest(
  scenario: TradingLabScenario,
  localCalculation: CalculatedOutcome | BlockedOutcome,
): void {
  const issues = validateScenario(scenario)
  if (!canRunScenario(issues)) {
    throw new Error('当前场景仍有阻塞校验问题，不能创建 Run。')
  }
  if (
    localCalculation.status !== 'CALCULATED'
    && localCalculation.status !== 'BLOCKED'
  ) {
    throw new Error('本地 Oracle 尚未形成可冻结结果。')
  }
  if (
    scenario.timeline.some(
      (action) => !VALIDATION_PUBLIC_ACTIONS.has(action.type),
    )
  ) {
    throw new Error('当前场景包含 validation 不支持的 LOCAL-only 动作。')
  }
  if (
    localCalculation.status === 'CALCULATED'
    && (
      localCalculation.result.configSnapshotHash
        !== scenario.configSnapshotHash
      || localCalculation.result.modelVersion !== scenario.modelVersion
    )
  ) {
    throw new Error('本地 Oracle 结果与当前场景 generation 不一致。')
  }
}

function jsonObject(value: unknown): TradingLabJsonObject {
  return JSON.parse(JSON.stringify(value)) as TradingLabJsonObject
}

function controlAllowed(
  run: NonNullable<TradingLabRunSession['run']>,
  action: TradingLabRunControlAction,
): boolean {
  if (action === 'pause') {
    return (
      run.state === 'RUNNING'
      && !run.pauseRequested
      && !run.cancelRequested
    )
  }
  if (action === 'resume') {
    return (
      run.state === 'PAUSED'
      && run.pauseRequested
      && !run.cancelRequested
    )
  }
  return (
    (
      run.state === 'QUEUED'
      || run.state === 'RESETTING'
      || run.state === 'RUNNING'
      || run.state === 'PAUSED'
      || run.state === 'CANCELLING'
    )
    && !run.cancelRequested
  )
}

function controlFlagsConfirm(
  value: Readonly<{
    pauseRequested: boolean
    cancelRequested: boolean
  }>,
  action: TradingLabRunControlAction,
): boolean {
  if (action === 'pause') {
    return value.pauseRequested
  }
  if (action === 'resume') {
    return !value.pauseRequested
  }
  return value.cancelRequested
}

function isExplicitApiFailure(error: unknown): boolean {
  return (
    error instanceof ApiClientError
    && error.status >= 400
    && error.status <= 599
  )
}

function isNonRetryableApiFailure(error: unknown): boolean {
  return (
    error instanceof TradingLabApiContractError
    || (
      error instanceof ApiClientError
      && error.status !== 408
      && error.status !== 429
      && error.status < 500
    )
  )
}

function isTransientApiFailure(error: unknown): boolean {
  return (
    error instanceof TypeError
    || (
      error instanceof ApiClientError
      && (
        error.status === 408
        || error.status === 429
        || error.status >= 500
      )
    )
  )
}

function errorMessage(error: unknown, fallback: string): string {
  const raw = error instanceof Error ? error.message : fallback
  const normalized = raw.trim()
  return (normalized.length === 0 ? fallback : normalized).slice(0, 240)
}

function requireDependencies(
  dependencies: TradingLabRunSessionControllerDependencies,
): void {
  if (dependencies === null || typeof dependencies !== 'object') {
    throw new TypeError('Trading Lab controller dependencies are required')
  }
  for (const value of Object.values(dependencies)) {
    if (typeof value !== 'function') {
      throw new TypeError('Trading Lab controller dependency must be a function')
    }
  }
}

function requireAccess(access: TradingLabRunSessionAccess): void {
  if (
    access === null
    || typeof access !== 'object'
    || typeof access.canView !== 'boolean'
    || typeof access.canExecute !== 'boolean'
  ) {
    throw new TypeError('Trading Lab controller access is invalid')
  }
}

function requireLocation(location: TradingLabRunLocation): void {
  if (
    location === null
    || typeof location !== 'object'
    || (location.runId !== null && typeof location.runId !== 'string')
    || (location.error !== null && typeof location.error !== 'string')
    || (location.runId !== null && location.error !== null)
  ) {
    throw new TypeError('Trading Lab controller location is invalid')
  }
}
