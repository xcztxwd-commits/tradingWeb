import { hasAdminAuthority } from '../../services/adminToken.ts'
import { ActualStatePanel } from './components/ActualStatePanel.tsx'
import { EnvironmentStatus } from './components/EnvironmentStatus.tsx'
import { ReportPanel } from './components/ReportPanel.tsx'
import { RunProgress } from './components/RunProgress.tsx'
import { TradingLabDesktopGuard } from './components/TradingLabDesktopGuard.tsx'
import { TradingLabDesktopWorkspace } from './components/TradingLabDesktopWorkspace.tsx'
import { useTradingLabRunSession } from './run/useTradingLabRunSession.ts'
import './TradingLabPage.css'

export function TradingLabPage() {
  return (
    <section className="trading-lab-page" aria-labelledby="trading-lab-title">
      <TradingLabDesktopGuard>
        <TradingLabDesktopRuntime />
      </TradingLabDesktopGuard>
    </section>
  )
}

function TradingLabDesktopRuntime() {
  const canView = hasAdminAuthority('TRADING_LAB_VIEW')
  const canExecute = hasAdminAuthority('TRADING_LAB_EXECUTE')
  const canSuperAdmin = hasAdminAuthority('SUPER_ADMIN')
  const runSession = useTradingLabRunSession({
    canView,
    canExecute,
  })
  const attached = runSession.runId !== null
  const locked = (
    !canExecute
    || runSession.runRequestState !== 'IDLE'
    || attached
    || runSession.session?.scenario != null
  )

  return (
    !canView ? (
      <div className="state-block" role="alert">
        需要精确的 TRADING_LAB_VIEW 权限才能访问交易路径实验室。
      </div>
    ) : runSession.locationError !== null ? (
      <p className="trading-lab-location-error" role="alert">
        {runSession.locationError}
      </p>
    ) : (
      <>
        <div className="trading-lab-session-stack">
          <RunProgress
            run={runSession.session?.run ?? null}
            validationState={
              runSession.session?.validationState ?? null
            }
            connection={
              runSession.session?.connection
                ?? (attached ? 'CONNECTING' : 'IDLE')
            }
            highestObservedTick={
              runSession.session?.highestObservedTick ?? null
            }
            highestCompletedCheckpoint={
              runSession.session?.highestCompletedCheckpoint ?? null
            }
            canExecute={canExecute}
            controlPending={runSession.controlPending}
            message={runSession.message}
            onControl={(action) => {
              void runSession.controlRun(action)
            }}
          />
          <EnvironmentStatus canSuperAdmin={canSuperAdmin} />
        </div>
        <TradingLabDesktopWorkspace
          locked={locked}
          chartEvidence={runSession.session?.chartEvidence}
          scenarioOverride={
            attached
              ? runSession.session?.scenario ?? null
              : undefined
          }
          runRequestState={runSession.runRequestState}
          onRunRequest={(request) => {
            void runSession.requestRun(request)
          }}
        />
        <div className="trading-lab-evidence-grid">
          {runSession.session !== null ? (
            <ActualStatePanel actualState={runSession.session.actualState} />
          ) : null}
          <ReportPanel
            run={runSession.session?.run ?? null}
            canView={canView}
            canExecute={canExecute}
            canSuperAdmin={canSuperAdmin}
            onRunRefresh={runSession.acceptAuthoritativeRun}
          />
        </div>
      </>
    )
  )
}
