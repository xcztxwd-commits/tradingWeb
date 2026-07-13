import type { components, paths } from './generated/openapi.ts'

type JsonContent<T> = T extends { content: infer Content }
  ? Content extends { 'application/json': infer Body }
    ? Body
    : Content extends { '*/*': infer Body }
      ? Body
      : never
  : never

type OperationAt<Path extends string, Method extends string> =
  Path extends keyof paths
    ? Method extends keyof paths[Path]
      ? NonNullable<paths[Path][Method]>
      : never
    : never

type SuccessResponse<Operation> = Operation extends { responses: infer Responses }
  ? 200 extends keyof Responses
    ? Responses[200]
    : never
  : never

type ApiData<T> = T extends { data?: infer Data } ? NonNullable<Data> : never

export type ApiPathData<Path extends string, Method extends string> = ApiData<
  JsonContent<SuccessResponse<OperationAt<Path, Method>>>
>

export type BackendSchemas = components['schemas']
export type BackendSchema<Name extends string> =
  Name extends keyof BackendSchemas ? BackendSchemas[Name] : never

export type AuthResponse = ApiPathData<'/api/auth/login', 'post'>
export type SessionStatus = ApiPathData<'/api/auth/session', 'get'>

export type ApiResponse<T> = {
  success: boolean
  code: string
  message: string
  data: T
  timestamp?: string
  requestId?: string
}
