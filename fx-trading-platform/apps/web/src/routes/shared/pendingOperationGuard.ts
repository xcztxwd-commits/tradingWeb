export type PendingOperationGuard = {
  run<T>(key: string, operation: () => Promise<T>): Promise<T>
  isPending(key: string): boolean
}

export function createPendingOperationGuard(): PendingOperationGuard {
  const pending = new Map<string, Promise<unknown>>()

  return {
    run<T>(key: string, operation: () => Promise<T>) {
      const current = pending.get(key)
      if (current) return current as Promise<T>

      let request: Promise<T>
      try {
        request = Promise.resolve(operation())
      } catch (error) {
        request = Promise.reject(error)
      }
      pending.set(key, request)
      void request.finally(() => {
        if (pending.get(key) === request) pending.delete(key)
      }).catch(() => undefined)
      return request
    },
    isPending(key: string) {
      return pending.has(key)
    }
  }
}
