import type { components, paths } from './generated/openapi.ts'

type JsonContent<T> = T extends { content: infer Content }
  ? Content extends { 'application/json': infer Body }
    ? Body
    : Content extends { '*/*': infer Body }
      ? Body
      : never
  : never

type ResponseBody<
  Path extends keyof paths,
  Method extends keyof paths[Path]
> = paths[Path][Method] extends { responses: { 200: infer Response } }
  ? JsonContent<Response>
  : never

type ApiData<T> = T extends { data?: infer Data } ? NonNullable<Data> : never

export type AuthResponse = ApiData<ResponseBody<'/api/auth/login', 'post'>>
export type SessionStatus = ApiData<ResponseBody<'/api/auth/session', 'get'>>

export type ApiResponse<T> = {
  success: boolean
  code: string
  message: string
  data: T
  timestamp?: string
  requestId?: string
}

export type BackendSchemas = components['schemas']
