import { useEffect, useState } from 'react'

import { getHomeCounters, type HomeCounters } from '../../../services/homeApi'

const fallbackCounters: HomeCounters = {
  users: 321320363,
  activeTraders: 18420,
  dailyTrades: 726340
}

export function useHomeCounters() {
  const [counters, setCounters] = useState<HomeCounters>(fallbackCounters)

  useEffect(() => {
    let active = true

    const loadCounters = () => {
      getHomeCounters()
        .then((nextCounters) => {
          if (active) setCounters(nextCounters)
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
