export const STANDARD_API_ERROR_CODES = [
  'AUTH_TOKEN_EXPIRED',
  'AUTH_REFRESH_TOKEN_INVALID',
  'USER_DISABLED',
  'ACCOUNT_NOT_FOUND',
  'ACCOUNT_NOT_ACTIVE',
  'SYMBOL_NOT_TRADABLE',
  'QUOTE_STALE',
  'INSUFFICIENT_BALANCE',
  'INSUFFICIENT_MARGIN',
  'ORDER_NOT_CANCELABLE',
  'ORDER_ALREADY_FILLED',
  'DUPLICATE_CLIENT_ORDER_ID',
  'EXECUTION_UNAVAILABLE'
] as const

export type StandardApiErrorCode = (typeof STANDARD_API_ERROR_CODES)[number]

export type ApiErrorCode =
  | StandardApiErrorCode
  | 'BAD_CREDENTIALS'
  | 'EMAIL_EXISTS'
  | 'PHONE_EXISTS'
  | 'VALIDATION_ERROR'
  | 'FORBIDDEN'
  | 'REQUEST_FAILED'
  | 'INTERNAL_ERROR'

export const DEFAULT_API_ERROR_MESSAGES: Record<ApiErrorCode, string> = {
  AUTH_TOKEN_EXPIRED: '登录已过期，请重新登录',
  AUTH_REFRESH_TOKEN_INVALID: '登录状态已失效，请重新登录',
  USER_DISABLED: '当前账号不可用，请联系管理员',
  ACCOUNT_NOT_FOUND: '账户不存在',
  ACCOUNT_NOT_ACTIVE: '账户未启用',
  SYMBOL_NOT_TRADABLE: '当前品种暂不可交易',
  QUOTE_STALE: '行情已过期，请稍后重试',
  INSUFFICIENT_BALANCE: '余额不足',
  INSUFFICIENT_MARGIN: '可用保证金不足',
  ORDER_NOT_CANCELABLE: '当前订单不可取消',
  ORDER_ALREADY_FILLED: '订单已成交',
  DUPLICATE_CLIENT_ORDER_ID: '订单请求重复，请勿重复提交',
  EXECUTION_UNAVAILABLE: '交易执行服务暂不可用',
  BAD_CREDENTIALS: '账号或密码不正确',
  EMAIL_EXISTS: '邮箱已被注册',
  PHONE_EXISTS: '手机号已被注册',
  VALIDATION_ERROR: '提交内容校验失败',
  FORBIDDEN: '当前账号权限不足',
  REQUEST_FAILED: '请求失败，请稍后重试',
  INTERNAL_ERROR: '服务器暂时不可用，请稍后重试'
}

export function friendlyApiErrorMessage(code: string, fallback = '请求失败，请稍后重试') {
  return DEFAULT_API_ERROR_MESSAGES[code as ApiErrorCode] ?? fallback
}
