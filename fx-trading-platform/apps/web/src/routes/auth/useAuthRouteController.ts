import { useMemo, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'

import { login, register, writeStoredAuthTokens } from '@fx-platform/frontend-core'
import {
  getAuthSuccessPath,
  normalizeRegistrationIdentifier,
  resolveSafeAuthRedirect
} from './authRouteModel'
import type { AuthChannel, AuthRouteMode, AuthRouteModel } from './authRoute.types'

export function useAuthRouteController(mode: AuthRouteMode): AuthRouteModel {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const redirectPath = useMemo(
    () => resolveSafeAuthRedirect(searchParams.get('redirect')),
    [searchParams]
  )
  const [channel, setChannel] = useState<AuthChannel>('email')
  const [countryCode, setCountryCode] = useState('+60')
  const [identifier, setIdentifier] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [confirmed, setConfirmed] = useState(false)
  const submissionRef = useRef(false)

  const submit = async () => {
    if (submitting) return
    if (submissionRef.current) return
    if (mode !== 'login' && mode !== 'register') return

    submissionRef.current = true
    setSubmitting(true)
    setError(null)

    try {
      const auth = mode === 'login'
        ? await login(email.trim(), password)
        : await register(
            normalizeRegistrationIdentifier(channel, countryCode, identifier),
            password,
            channel
          )
      const storedEmail = auth.email || (mode === 'login'
        ? email.trim()
        : normalizeRegistrationIdentifier(channel, countryCode, identifier))
      writeStoredAuthTokens(auth.accessToken, auth.refreshToken)
      writeStoredEmail(storedEmail)
      const successPath = getAuthSuccessPath(mode, redirectPath)
      navigate(successPath, { replace: true })
    } catch (nextError) {
      setError(formatAuthError(nextError))
    } finally {
      submissionRef.current = false
      setSubmitting(false)
    }
  }

  return {
    mode,
    channel,
    countryCode,
    identifier,
    email,
    password,
    submitting,
    error,
    confirmed,
    redirectPath,
    setChannel,
    setCountryCode,
    setIdentifier,
    setEmail,
    setPassword,
    submit,
    confirmSupport: () => setConfirmed(true)
  }
}

function writeStoredEmail(email: string) {
  try {
    globalThis.localStorage?.setItem('fx-platform-user-email', email)
  } catch {
    // Account chrome can use its fallback profile copy when storage is unavailable.
  }
}

function formatAuthError(error: unknown) {
  if (error instanceof Error && error.message) return error.message
  if (typeof error === 'string' && error) return error
  return ''
}
