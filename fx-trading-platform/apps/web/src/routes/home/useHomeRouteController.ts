import { useEffect, useState } from 'react'

import {
  authSessionChangedEvent,
  getHomeCounters,
  readStoredAuthToken,
  type HomeCounters
} from '@fx-platform/frontend-core'
import { resolveHomeAuthVariant } from './homeRouteModel'
import type { HomeRouteModel } from './homeRoute.types'

const fallbackCounters: HomeCounters = {
  users: 321443508,
  activeTraders: 18420,
  dailyTrades: 726340,
  metricCards: [
    {
      slot: 'asset',
      frontRank: 'No.1',
      frontLabel: '客户资产',
      backTitle: '资产',
      backValue: '$134,166,872,529'
    },
    {
      slot: 'volume',
      frontRank: 'No.1',
      frontLabel: '交易量',
      backTitle: '24H',
      backValue: '$44,301,728,218'
    }
  ]
}

const markets: HomeRouteModel['markets'] = [
  { symbol: 'BTC', name: 'Bitcoin', price: '$64,483.88', change: '+1.27%', tone: 'positive' },
  { symbol: 'ETH', name: 'Ethereum', price: '$1,682.29', change: '+0.83%', tone: 'positive' },
  { symbol: 'BNB', name: 'BNB', price: '$609.30', change: '+0.89%', tone: 'positive' },
  { symbol: 'XRP', name: 'XRP', price: '$1.15', change: '+1.46%', tone: 'positive' },
  { symbol: 'ASTER', name: 'Aster', price: '$0.636', change: '+0.32%', tone: 'positive' }
]

const news: HomeRouteModel['news'] = [
  '瑞士夺世界杯B组概率降至47%',
  '开源权重模型或加大交易模型竞争压力',
  '加密 ETF 申请进入新一轮审查窗口',
  '永续合约部署权限进入更开放的产品周期'
]

export function useHomeRouteController(): HomeRouteModel {
  const [authVariant, setAuthVariant] = useState(() => readAuthVariant())
  const [counterState, setCounterState] = useState<Pick<HomeRouteModel, 'counters' | 'status' | 'error'>>({
    counters: fallbackCounters,
    status: 'loading',
    error: null
  })

  useEffect(() => {
    const refreshVariant = () => setAuthVariant(readAuthVariant())

    refreshVariant()
    window.addEventListener(authSessionChangedEvent, refreshVariant)
    return () => window.removeEventListener(authSessionChangedEvent, refreshVariant)
  }, [])

  useEffect(() => {
    let active = true

    const loadCounters = () => {
      getHomeCounters()
        .then((nextCounters) => {
          if (!active) return
          setCounterState({
            counters: {
              ...nextCounters,
              metricCards: Array.isArray(nextCounters.metricCards) ? nextCounters.metricCards : []
            },
            status: 'ready',
            error: null
          })
        })
        .catch(() => {
          if (!active) return
          setCounterState((current) => ({
            ...current,
            status: 'error',
            error: 'home-counters-unavailable'
          }))
        })
    }

    loadCounters()
    const timer = window.setInterval(loadCounters, 1000)
    return () => {
      active = false
      window.clearInterval(timer)
    }
  }, [])

  return {
    authVariant,
    ...counterState,
    markets,
    news
  }
}

function readAuthVariant() {
  const token = readStoredAuthToken()
  let kycStatus: string | null = null
  try {
    kycStatus = globalThis.localStorage?.getItem('fx-platform-kyc-status') ?? null
  } catch {
    // An authenticated user remains unverified when browser storage is unavailable.
  }
  return resolveHomeAuthVariant(token, kycStatus)
}
