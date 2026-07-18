import { authRoutes } from '../navigation.ts'

export function getShellRouteFlags(pathname: string) {
  return {
    isTerminalRoute: /^\/trade\/(spot|perpetual)(?:\/|$)/.test(pathname),
    isAuthRoute: authRoutes.includes(pathname as (typeof authRoutes)[number])
  }
}
