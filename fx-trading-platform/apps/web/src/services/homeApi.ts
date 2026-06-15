import { apiGet } from './apiClient'

export type HomeCounters = {
  users: number
  activeTraders: number
  dailyTrades: number
}

export function getHomeCounters() {
  return apiGet<HomeCounters>('/api/public/home-counters')
}
