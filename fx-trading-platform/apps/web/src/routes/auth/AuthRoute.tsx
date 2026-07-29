import { lazy } from 'react'

import { PlatformView } from '../../app/platform/PlatformView'
import { ExchangeLoading } from '../../components/loading/ExchangeLoading'
import type { AuthRouteMode, AuthRouteProps } from './authRoute.types'
import { useAuthRouteController } from './useAuthRouteController'

const PcLoginPage = lazy(() =>
  import('../../pc/pages/auth/PcLoginPage').then((module) => ({ default: module.PcLoginPage }))
)
const PcRegisterPage = lazy(() =>
  import('../../pc/pages/auth/PcRegisterPage').then((module) => ({ default: module.PcRegisterPage }))
)
const PcForgotPasswordPage = lazy(() =>
  import('../../pc/pages/auth/PcForgotPasswordPage').then((module) => ({ default: module.PcForgotPasswordPage }))
)
const PcTwoFactorHelpPage = lazy(() =>
  import('../../pc/pages/auth/PcTwoFactorHelpPage').then((module) => ({ default: module.PcTwoFactorHelpPage }))
)
const MobileLoginPage = lazy(() =>
  import('../../mobile/pages/auth/MobileLoginPage').then((module) => ({ default: module.MobileLoginPage }))
)
const MobileRegisterPage = lazy(() =>
  import('../../mobile/pages/auth/MobileRegisterPage').then((module) => ({ default: module.MobileRegisterPage }))
)
const MobileForgotPasswordPage = lazy(() =>
  import('../../mobile/pages/auth/MobileForgotPasswordPage').then((module) => ({ default: module.MobileForgotPasswordPage }))
)
const MobileTwoFactorHelpPage = lazy(() =>
  import('../../mobile/pages/auth/MobileTwoFactorHelpPage').then((module) => ({ default: module.MobileTwoFactorHelpPage }))
)

const authViews = {
  'login': { pc: PcLoginPage, mobile: MobileLoginPage },
  'register': { pc: PcRegisterPage, mobile: MobileRegisterPage },
  'forgot-password': { pc: PcForgotPasswordPage, mobile: MobileForgotPasswordPage },
  'two-factor-help': { pc: PcTwoFactorHelpPage, mobile: MobileTwoFactorHelpPage }
} satisfies Record<AuthRouteMode, { pc: typeof PcLoginPage; mobile: typeof MobileLoginPage }>

export function AuthRoute({ mode }: AuthRouteProps) {
  const model = useAuthRouteController(mode)
  const views = authViews[mode]

  return (
    <PlatformView
      model={model}
      pc={views.pc}
      mobile={views.mobile}
      fallback={<ExchangeLoading />}
    />
  )
}
