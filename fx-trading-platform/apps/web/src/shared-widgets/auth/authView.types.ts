export type AuthViewMode = 'login' | 'register' | 'forgot-password' | 'two-factor-help'
export type AuthViewChannel = 'email' | 'phone'

export type AuthViewModel = {
  mode: AuthViewMode
  channel: AuthViewChannel
  countryCode: string
  identifier: string
  email: string
  password: string
  submitting: boolean
  error: string | null
  confirmed: boolean
  redirectPath: string
  setChannel(channel: AuthViewChannel): void
  setCountryCode(value: string): void
  setIdentifier(value: string): void
  setEmail(value: string): void
  setPassword(value: string): void
  submit(): Promise<void>
  confirmSupport(): void
}
