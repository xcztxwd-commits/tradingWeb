import { useCallback, useEffect, useRef, useState } from 'react'

import {
  controlTradingLabEnvironment,
  getTradingLabEnvironment,
  type TradingLabEnvironmentStatusResponse,
} from '../api/tradingLabApi.ts'
import { getValidAdminToken } from '../../../services/adminToken.ts'

export type EnvironmentStatusProps = Readonly<{
  canSuperAdmin: boolean
}>

type EnvironmentAction = 'start' | 'stop' | 'restart'

export function EnvironmentStatus({
  canSuperAdmin,
}: EnvironmentStatusProps) {
  const [status, setStatus] =
    useState<TradingLabEnvironmentStatusResponse | null>(null)
  const [message, setMessage] = useState('正在读取隔离环境状态…')
  const [pending, setPending] = useState<EnvironmentAction | null>(null)
  const generationRef = useRef(0)

  const refresh = useCallback(async (generation: number) => {
    const token = getValidAdminToken()
    if (token === null) {
      if (generationRef.current === generation) {
        setMessage('管理员会话不可用。')
      }
      return
    }
    try {
      const next = await getTradingLabEnvironment(token)
      if (generationRef.current === generation) {
        setStatus(next)
        setMessage('')
      }
    } catch (error) {
      if (generationRef.current === generation) {
        setMessage(
          error instanceof Error ? error.message : '读取隔离环境状态失败',
        )
      }
    }
  }, [])

  useEffect(() => {
    generationRef.current += 1
    const generation = generationRef.current
    void refresh(generation)
    return () => {
      if (generationRef.current === generation) {
        generationRef.current += 1
      }
    }
  }, [refresh])

  const act = useCallback(async (action: EnvironmentAction) => {
    if (!canSuperAdmin || pending !== null) {
      return
    }
    const token = getValidAdminToken()
    if (token === null) {
      setMessage('管理员会话不可用。')
      return
    }
    generationRef.current += 1
    const generation = generationRef.current
    setPending(action)
    try {
      await controlTradingLabEnvironment(action, token)
      if (generationRef.current === generation) {
        await refresh(generation)
      }
    } catch (error) {
      if (generationRef.current === generation) {
        setMessage(
          error instanceof Error ? error.message : '隔离环境操作失败',
        )
      }
    } finally {
      if (generationRef.current === generation) {
        setPending(null)
      }
    }
  }, [canSuperAdmin, pending, refresh])

  return (
    <section
      className="trading-lab-environment-panel"
      aria-labelledby="trading-lab-environment-title"
    >
      <div>
        <span className="trading-lab-eyebrow">Isolated demo runtime</span>
        <h2 id="trading-lab-environment-title">Validation 环境</h2>
      </div>
      <dl className="trading-lab-environment-summary">
        <div>
          <dt>Relay</dt>
          <dd>{status === null ? '未知' : status.relayRunning ? '运行中' : '已停止'}</dd>
        </div>
        <div>
          <dt>Validation health</dt>
          <dd>{status?.validationHealth ?? '未知'}</dd>
        </div>
      </dl>
      <div className="trading-lab-environment-controls">
        <button
          type="button"
          disabled={
            !canSuperAdmin
            || pending !== null
            || status?.relayRunning !== false
          }
          onClick={() => void act('start')}
        >
          启动
        </button>
        <button
          type="button"
          disabled={
            !canSuperAdmin
            || pending !== null
            || status?.relayRunning !== true
          }
          onClick={() => void act('stop')}
        >
          停止
        </button>
        <button
          type="button"
          disabled={
            !canSuperAdmin
            || pending !== null
            || status?.relayRunning !== true
          }
          onClick={() => void act('restart')}
        >
          重启
        </button>
      </div>
      <p role="status" className="trading-lab-environment-message">
        {message || (
          canSuperAdmin
            ? '环境动作仍由后端 idle gate 最终裁决。'
            : '仅 SUPER_ADMIN 可执行环境动作。'
        )}
      </p>
    </section>
  )
}
