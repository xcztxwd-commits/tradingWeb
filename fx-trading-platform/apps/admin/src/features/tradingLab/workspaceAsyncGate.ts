export type TradingLabWorkspaceAsyncToken = Readonly<{
  generation: number
}>

export type TradingLabWorkspaceAsyncOptions = Readonly<{
  allowLocked?: boolean
}>

export interface TradingLabWorkspaceAsyncGate {
  setLocked(locked: boolean): void
  invalidate(): void
  begin(
    options?: TradingLabWorkspaceAsyncOptions,
  ): TradingLabWorkspaceAsyncToken | null
  isCurrent(
    token: TradingLabWorkspaceAsyncToken,
    options?: TradingLabWorkspaceAsyncOptions,
  ): boolean
  dispose(): void
}

export function createTradingLabWorkspaceAsyncGate(
  initiallyLocked: boolean,
): TradingLabWorkspaceAsyncGate {
  let locked = initiallyLocked
  let generation = 0
  let disposed = false

  return {
    setLocked(nextLocked) {
      if (disposed || locked === nextLocked) {
        return
      }
      locked = nextLocked
      generation += 1
    },
    invalidate() {
      if (!disposed) {
        generation += 1
      }
    },
    begin(options = {}) {
      if (disposed || (locked && options.allowLocked !== true)) {
        return null
      }
      generation += 1
      return { generation }
    },
    isCurrent(token, options = {}) {
      return (
        !disposed
        && generation === token.generation
        && (!locked || options.allowLocked === true)
      )
    },
    dispose() {
      if (disposed) {
        return
      }
      disposed = true
      generation += 1
    },
  }
}
