import { type RefObject, useEffect, useRef } from 'react'

export function useResizeObserver<TElement extends Element>(
  targetRef: RefObject<TElement | null>,
  onResize: (entry: ResizeObserverEntry) => void
) {
  const onResizeRef = useRef(onResize)

  useEffect(() => {
    onResizeRef.current = onResize
  }, [onResize])

  useEffect(() => {
    const target = targetRef.current
    if (!target || typeof ResizeObserver === 'undefined') return

    let frameId = 0
    const observer = new ResizeObserver((entries) => {
      const entry = entries[0]
      if (!entry) return

      window.cancelAnimationFrame(frameId)
      frameId = window.requestAnimationFrame(() => onResizeRef.current(entry))
    })

    observer.observe(target)

    return () => {
      window.cancelAnimationFrame(frameId)
      observer.disconnect()
    }
  }, [targetRef])
}
