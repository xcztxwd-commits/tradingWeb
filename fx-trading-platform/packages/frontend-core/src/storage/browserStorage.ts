export type KeyValueStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

export function getBrowserStorage(): KeyValueStorage | undefined {
  try {
    return globalThis.localStorage
  } catch {
    return undefined
  }
}
