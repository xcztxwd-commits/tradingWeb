import { apiGet } from './apiClient'

export type HomeMetricCard = {
  slot: string
  frontRank: string
  frontLabel: string
  backTitle: string
  backValue: string
}

export type HomeCounters = {
  users: number
  activeTraders: number
  dailyTrades: number
  metricCards: HomeMetricCard[]
}

export function getHomeCounters() {
  return apiGet<HomeCounters>('/api/public/home-counters')
}
