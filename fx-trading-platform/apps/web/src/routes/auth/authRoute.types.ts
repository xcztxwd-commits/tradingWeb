import type {
  AuthViewChannel,
  AuthViewMode,
  AuthViewModel
} from '../../shared-widgets/auth/authView.types'

export type AuthRouteMode = AuthViewMode
export type AuthChannel = AuthViewChannel
export type AuthRouteModel = AuthViewModel

export type AuthRouteProps = {
  mode: AuthRouteMode
}

export type AuthViewProps = {
  model: AuthRouteModel
}
