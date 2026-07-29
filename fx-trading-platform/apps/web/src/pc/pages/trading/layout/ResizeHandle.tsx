import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'

import styles from './ResizeHandle.module.css'

type ResizeHandleOrientation = 'vertical' | 'horizontal'

type ResizeDelta = {
  deltaX: number
  deltaY: number
  clientX: number
  clientY: number
}

type Props = {
  orientation: ResizeHandleOrientation
  ariaLabel: string
  onDragStart?: () => void
  onDrag: (delta: ResizeDelta) => void
  onDragEnd?: () => void
  onReset?: () => void
}

type DragState = {
  pointerId: number
  target: HTMLButtonElement
  startX: number
  startY: number
  latestX: number
  latestY: number
  frameId: number
}

export function ResizeHandle({ orientation, ariaLabel, onDragStart, onDrag, onDragEnd, onReset }: Props) {
  const dragRef = useRef<DragState | null>(null)
  const previousBodyCursorRef = useRef('')
  const [dragging, setDragging] = useState(false)

  const flushDrag = useCallback(() => {
    const drag = dragRef.current
    if (!drag) return

    drag.frameId = 0
    onDrag({
      deltaX: drag.latestX - drag.startX,
      deltaY: drag.latestY - drag.startY,
      clientX: drag.latestX,
      clientY: drag.latestY
    })
  }, [onDrag])

  const scheduleDrag = useCallback(() => {
    const drag = dragRef.current
    if (!drag || drag.frameId) return
    drag.frameId = window.requestAnimationFrame(flushDrag)
  }, [flushDrag])

  const stopDrag = useCallback(
    (pointerId: number) => {
      const drag = dragRef.current
      if (!drag || drag.pointerId !== pointerId) return

      if (drag.frameId) window.cancelAnimationFrame(drag.frameId)
      flushDrag()
      dragRef.current = null
      setDragging(false)
      document.body.classList.remove('dragging')
      document.body.style.cursor = previousBodyCursorRef.current
      if (drag.target.hasPointerCapture(pointerId)) drag.target.releasePointerCapture(pointerId)
      onDragEnd?.()
    },
    [flushDrag, onDragEnd]
  )

  const updateDragPosition = useCallback(
    (pointerId: number, clientX: number, clientY: number) => {
      const drag = dragRef.current
      if (!drag || drag.pointerId !== pointerId) return

      drag.latestX = clientX
      drag.latestY = clientY
      scheduleDrag()
    },
    [scheduleDrag]
  )

  const handlePointerDown = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (event.pointerType === 'mouse' && event.button !== 0) return

    event.preventDefault()
    event.currentTarget.setPointerCapture(event.pointerId)
    dragRef.current = {
      pointerId: event.pointerId,
      target: event.currentTarget,
      startX: event.clientX,
      startY: event.clientY,
      latestX: event.clientX,
      latestY: event.clientY,
      frameId: 0
    }
    setDragging(true)
    previousBodyCursorRef.current = document.body.style.cursor
    document.body.classList.add('dragging')
    document.body.style.cursor = orientation === 'vertical' ? 'col-resize' : 'row-resize'
    onDragStart?.()
  }

  const handlePointerMove = (event: ReactPointerEvent<HTMLButtonElement>) => {
    updateDragPosition(event.pointerId, event.clientX, event.clientY)
  }

  const handlePointerUp = (event: ReactPointerEvent<HTMLButtonElement>) => {
    stopDrag(event.pointerId)
  }

  useEffect(() => {
    if (!dragging) return

    const handleWindowPointerMove = (event: PointerEvent) => {
      updateDragPosition(event.pointerId, event.clientX, event.clientY)
    }

    const handleWindowPointerUp = (event: PointerEvent) => {
      stopDrag(event.pointerId)
    }

    window.addEventListener('pointermove', handleWindowPointerMove)
    window.addEventListener('pointerup', handleWindowPointerUp)
    window.addEventListener('pointercancel', handleWindowPointerUp)

    return () => {
      window.removeEventListener('pointermove', handleWindowPointerMove)
      window.removeEventListener('pointerup', handleWindowPointerUp)
      window.removeEventListener('pointercancel', handleWindowPointerUp)
    }
  }, [dragging, stopDrag, updateDragPosition])

  useEffect(() => {
    return () => {
      const drag = dragRef.current
      if (drag?.frameId) window.cancelAnimationFrame(drag.frameId)
      document.body.classList.remove('dragging')
      document.body.style.cursor = previousBodyCursorRef.current
    }
  }, [])

  return (
    <button
      type="button"
      aria-label={ariaLabel}
      className={`${styles.handle} ${styles[orientation]} ${dragging ? styles.dragging : ''}`}
      onDoubleClick={(event) => {
        event.preventDefault()
        onReset?.()
      }}
      onPointerCancel={handlePointerUp}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={handlePointerUp}
    />
  )
}
