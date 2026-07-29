type DisposableController = { dispose(): void }
type ScheduleCleanup = (cleanup: () => void) => void

export function createControllerLeaseRegistry(
  scheduleCleanup: ScheduleCleanup = (cleanup) => queueMicrotask(cleanup)
) {
  const leaseCounts = new Map<DisposableController, number>()

  return {
    acquire(controller: DisposableController) {
      leaseCounts.set(controller, (leaseCounts.get(controller) ?? 0) + 1)
      let released = false
      return () => {
        if (released) return
        released = true
        const remaining = Math.max(0, (leaseCounts.get(controller) ?? 1) - 1)
        leaseCounts.set(controller, remaining)
        scheduleCleanup(() => {
          if ((leaseCounts.get(controller) ?? 0) !== 0) return
          leaseCounts.delete(controller)
          controller.dispose()
        })
      }
    }
  }
}
