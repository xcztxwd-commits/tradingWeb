import { useEffect, useRef, useState } from 'react'

import { getValidAdminToken } from '../../../services/adminToken.ts'
import { ApiClientError } from '../../../services/apiClient.ts'
import {
  deleteTradingLabReport,
  getTradingLabReport,
  getTradingLabRun,
  setTradingLabReportPermanent,
  type TradingLabReportResponse,
} from '../api/tradingLabApi.ts'
import {
  downloadReport,
  printRawReport,
} from '../api/tradingLabReportTransfer.ts'
import type { TradingLabRunSessionRun } from '../run/tradingLabRunSession.ts'
import {
  currentTradingLabReport,
  reconcileTradingLabReportDeletion,
  reconcileTradingLabReportPermanent,
  type TradingLabReportActionDependencies,
  type TradingLabReportActionRun,
} from '../report/tradingLabReportActions.ts'

const TERMINAL_RUN_STATES = new Set([
  'COMPLETED',
  'FAILED',
  'CANCELLED',
])

const REPORT_SCHEMA_KEYS = [
  'metadata',
  'actor',
  'environment',
  'scenario',
  'modelVersion',
  'configSnapshot',
  'localCalculation',
  'lifecycle',
  'apiTrace',
  'marketTicks',
  'checkpoints',
  'actualState',
  'errors',
  'cleanup',
] as const

type ReportPanelStatus =
  | 'IDLE'
  | 'GENERATING'
  | 'LOADING'
  | 'READY'
  | 'ABSENT'
  | 'ERROR'
  | 'UNKNOWN'

type ReportPanelBusy = 'download' | 'print' | 'permanent' | 'delete' | null

export type ReportPanelProps = Readonly<{
  run: TradingLabRunSessionRun | null
  canView: boolean
  canExecute: boolean
  canSuperAdmin: boolean
  onRunRefresh(run: TradingLabReportActionRun): void
}>

const reportActionDependencies: TradingLabReportActionDependencies = {
  deleteReport: deleteTradingLabReport,
  getReport: getTradingLabReport,
  getRun: getTradingLabRun,
  setPermanent: setTradingLabReportPermanent,
  isNotFound(error) {
    return error instanceof ApiClientError && error.status === 404
  },
}

export function ReportPanel({
  run,
  canView,
  canExecute,
  canSuperAdmin,
  onRunRefresh,
}: ReportPanelProps) {
  const [status, setStatus] = useState<ReportPanelStatus>('IDLE')
  const [report, setReport] = useState<TradingLabReportResponse | null>(null)
  const [busy, setBusy] = useState<ReportPanelBusy>(null)
  const [message, setMessage] = useState<string | null>(null)
  const generationRef = useRef(0)
  const transferAbortRef = useRef<AbortController | null>(null)
  const readyReport = status === 'READY'
    ? currentTradingLabReport(run, report)
    : null

  useEffect(() => {
    generationRef.current += 1
    const expectedGeneration = generationRef.current
    const expectedRunId = run?.id ?? null
    const expectedReportId = run?.reportId ?? null
    transferAbortRef.current?.abort()
    transferAbortRef.current = null
    setBusy(null)
    setMessage(null)
    setReport(null)

    if (!canView || run === null) {
      setStatus('IDLE')
      return
    }
    if (!TERMINAL_RUN_STATES.has(run.state)) {
      setStatus('GENERATING')
      return
    }
    if (expectedReportId === null) {
      setStatus('ABSENT')
      return
    }

    const token = getValidAdminToken()
    if (token === null) {
      setStatus('ERROR')
      setMessage('管理员会话不可用，无法读取报告。')
      return
    }

    setStatus('LOADING')
    void getTradingLabReport(expectedReportId, token).then(
      (loaded) => {
        if (
          generationRef.current !== expectedGeneration
          || loaded.id !== expectedReportId
          || loaded.runId !== expectedRunId
          || loaded.scenarioId !== run.scenarioId
          || loaded.status !== run.state
        ) {
          if (generationRef.current === expectedGeneration) {
            setStatus('ERROR')
            setMessage('报告与当前权威 Run 的 identity 或终态不一致。')
          }
          return
        }
        setReport(loaded)
        setStatus('READY')
      },
      (error: unknown) => {
        if (generationRef.current !== expectedGeneration) {
          return
        }
        setStatus('ERROR')
        setMessage(errorMessage(error, '报告 metadata 读取失败。'))
      },
    )

    return () => {
      if (generationRef.current === expectedGeneration) {
        generationRef.current += 1
      }
      transferAbortRef.current?.abort()
      transferAbortRef.current = null
    }
  }, [
    canView,
    run?.id,
    run?.reportId,
    run?.scenarioId,
    run?.state,
  ])

  const startTransfer = async (kind: 'download' | 'print') => {
    if (
      busy !== null
      || readyReport === null
      || run === null
    ) {
      return
    }
    const expectedGeneration = generationRef.current
    const expectedReportId = readyReport.id
    const controller = new AbortController()
    transferAbortRef.current = controller
    setBusy(kind)
    setMessage(null)
    try {
      if (kind === 'download') {
        await downloadReport(expectedReportId, { signal: controller.signal })
      } else {
        await printRawReport(expectedReportId, { signal: controller.signal })
      }
      if (
        generationRef.current === expectedGeneration
        && !controller.signal.aborted
      ) {
        setMessage(
          kind === 'download'
            ? '下载流程已结束；若取消文件选择则未保存。'
            : '打印流程已结束；若取消大报告确认则未发送打印。',
        )
      }
    } catch (error) {
      if (
        generationRef.current === expectedGeneration
        && !controller.signal.aborted
      ) {
        setMessage(errorMessage(
          error,
          kind === 'download' ? '报告下载失败。' : '报告打印失败。',
        ))
      }
    } finally {
      if (
        generationRef.current === expectedGeneration
        && transferAbortRef.current === controller
      ) {
        transferAbortRef.current = null
        setBusy(null)
      }
    }
  }

  const changePermanent = async () => {
    if (
      !canExecute
      || busy !== null
      || readyReport === null
      || run === null
    ) {
      return
    }
    const token = getValidAdminToken()
    if (token === null) {
      setMessage('管理员会话不可用，无法修改保留状态。')
      return
    }
    const expectedGeneration = generationRef.current
    const expectedReportId = readyReport.id
    const desired = !readyReport.permanent
    setBusy('permanent')
    setMessage(null)
    const result = await reconcileTradingLabReportPermanent(
      {
        reportId: expectedReportId,
        runId: run.id,
        scenarioId: run.scenarioId,
        runState: run.state,
        permanent: desired,
        token,
      },
      reportActionDependencies,
    )
    if (generationRef.current !== expectedGeneration) {
      return
    }
    setBusy(null)
    if (result.kind === 'UNKNOWN') {
      setStatus('UNKNOWN')
      setMessage('保留状态结果未知；已禁止盲目重试，请重新进入当前 Run。')
      return
    }
    setReport(result.report as TradingLabReportResponse)
    setMessage(
      result.kind === 'CONFIRMED'
        ? desired ? '报告已永久保留。' : '报告已恢复按期限保留。'
        : '权威报告状态未改变。',
    )
  }

  const deleteReport = async () => {
    if (
      !canExecute
      || busy !== null
      || readyReport === null
      || run === null
      || !window.confirm('确认删除这份终态报告？运行历史将保留。')
    ) {
      return
    }
    const token = getValidAdminToken()
    if (token === null) {
      setMessage('管理员会话不可用，无法删除报告。')
      return
    }
    const expectedGeneration = generationRef.current
    const expectedReportId = readyReport.id
    setBusy('delete')
    setMessage(null)
    const result = await reconcileTradingLabReportDeletion(
      {
        reportId: expectedReportId,
        runId: run.id,
        scenarioId: run.scenarioId,
        runState: run.state,
        token,
      },
      reportActionDependencies,
    )
    if (generationRef.current !== expectedGeneration) {
      return
    }
    setBusy(null)
    if (result.kind === 'DELETED') {
      setReport(null)
      setStatus('ABSENT')
      setMessage('报告已删除；运行历史仍保留。')
      onRunRefresh(result.run)
      return
    }
    if (result.kind === 'PRESENT') {
      setReport(result.report as TradingLabReportResponse)
      setMessage('权威报告仍存在，未声明删除成功。')
      return
    }
    setStatus('UNKNOWN')
    setMessage('删除结果未知；已禁止盲目重试，请重新进入当前 Run。')
  }

  if (!canView) {
    return null
  }

  return (
    <section className="trading-lab-report-panel" aria-labelledby="trading-lab-report-title">
      <div className="trading-lab-section-heading">
        <div>
          <p className="trading-lab-eyebrow">Raw evidence</p>
          <h2 id="trading-lab-report-title">运行报告</h2>
        </div>
        {readyReport !== null ? (
          <span className="trading-lab-status-chip">{readyReport.status}</span>
        ) : null}
      </div>

      {status === 'GENERATING' ? (
        <p className="trading-lab-panel-note">报告生成中；终态关闭前不会读取报告。</p>
      ) : status === 'LOADING' ? (
        <p className="trading-lab-panel-note">正在读取有界报告 metadata…</p>
      ) : status === 'ABSENT' ? (
        <p className="trading-lab-panel-note">当前终态 Run 没有关联报告。</p>
      ) : status === 'UNKNOWN' || status === 'ERROR' ? (
        <p className="trading-lab-panel-error" role="alert">{message}</p>
      ) : readyReport === null ? (
        <p className="trading-lab-panel-note">运行结束后可查看和导出原始报告。</p>
      ) : (
        <>
          <dl className="trading-lab-report-metadata">
            <div><dt>Report ID</dt><dd>{readyReport.id}</dd></div>
            <div><dt>Run ID</dt><dd>{readyReport.runId}</dd></div>
            <div><dt>Scenario ID</dt><dd>{readyReport.scenarioId}</dd></div>
            <div><dt>Model</dt><dd>{readyReport.modelVersion}</dd></div>
            <div><dt>Config hash</dt><dd>{readyReport.configSnapshotHash}</dd></div>
            <div><dt>Code version</dt><dd>{readyReport.codeVersion}</dd></div>
            <div><dt>大小</dt><dd>{formatBytes(readyReport.uncompressedBytes)}</dd></div>
            <div><dt>压缩存储</dt><dd>{formatBytes(readyReport.compressedBytes)}</dd></div>
            <div><dt>Chunks</dt><dd>{readyReport.chunkCount}</dd></div>
            <div><dt>创建时间</dt><dd>{readyReport.createdAt}</dd></div>
            <div><dt>完成时间</dt><dd>{readyReport.completedAt}</dd></div>
            <div><dt>保留至</dt><dd>{readyReport.retainedUntil}</dd></div>
            <div><dt>永久保留</dt><dd>{readyReport.permanent ? '是' : '否'}</dd></div>
            <div><dt>Version</dt><dd>{readyReport.version}</dd></div>
          </dl>

          {readyReport.failureCode !== null ? (
            <p className="trading-lab-panel-error">
              {readyReport.failureCode}：{readyReport.failureMessage ?? '运行失败'}
            </p>
          ) : null}

          <div className="trading-lab-report-schema">
            <h3>固定报告 Schema</h3>
            <p>
              仅列出固定 Schema；当前有界 metadata API 未加载各 section 内容。
            </p>
            <ul>
              {REPORT_SCHEMA_KEYS.map((key) => <li key={key}>{key}</li>)}
            </ul>
            <p>
              errors 与 cleanup 的原始内容需显式下载或打印查看，不从终态推断环境已清理。
            </p>
          </div>

          <div className="trading-lab-report-actions">
            <button
              type="button"
              disabled={busy !== null}
              onClick={() => { void startTransfer('download') }}
            >
              {busy === 'download' ? '下载中…' : '下载原始 JSON'}
            </button>
            <button
              type="button"
              disabled={busy !== null}
              onClick={() => { void startTransfer('print') }}
              title={canSuperAdmin
                ? '大报告可进行 SUPER_ADMIN 二次确认'
                : '超过 50 MiB 的报告需要独立 SUPER_ADMIN 权限'}
            >
              {busy === 'print' ? '准备打印…' : '打印原始 JSON'}
            </button>
            <button
              type="button"
              disabled={!canExecute || busy !== null}
              onClick={() => { void changePermanent() }}
            >
              {busy === 'permanent'
                ? '核对中…'
                : readyReport.permanent ? '取消永久保留' : '永久保留'}
            </button>
            <button
              type="button"
              className="danger"
              disabled={!canExecute || busy !== null}
              onClick={() => { void deleteReport() }}
            >
              {busy === 'delete' ? '核对删除结果…' : '删除报告'}
            </button>
          </div>
        </>
      )}

      {message !== null && status !== 'ERROR' && status !== 'UNKNOWN' ? (
        <p className="trading-lab-panel-note" role="status">{message}</p>
      ) : null}
    </section>
  )
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`
  }
  const mebibytes = bytes / (1024 * 1024)
  return `${mebibytes.toFixed(mebibytes >= 10 ? 1 : 2)} MiB`
}

function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message.trim().length > 0
    ? error.message
    : fallback
}
