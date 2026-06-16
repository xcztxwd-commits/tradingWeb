import { useEffect, useState } from 'react'

import { getHomeCounters, type HomeCounters } from '../../../services/homeApi'

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

export function useHomeCounters() {
  const [counters, setCounters] = useState<HomeCounters>(fallbackCounters)

  useEffect(() => {
    let active = true

    const loadCounters = () => {
      getHomeCounters()
        .then((nextCounters) => {
          if (active) {
            setCounters({
              ...nextCounters,
              metricCards: Array.isArray(nextCounters.metricCards) ? nextCounters.metricCards : []
            })
          }
        })
        .catch(() => {
          if (active) setCounters((current) => current)
        })
    }

    loadCounters()
    const timer = window.setInterval(loadCounters, 1000)
    return () => {
      active = false
      window.clearInterval(timer)
    }
  }, [])

  return counters
}
