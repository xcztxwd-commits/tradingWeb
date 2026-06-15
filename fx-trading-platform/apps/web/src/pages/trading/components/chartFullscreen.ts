type ShortcutEvent = {
  key: string
  shiftKey: boolean
  ctrlKey: boolean
  metaKey: boolean
  altKey: boolean
}

type ShortcutTarget = {
  tagName?: string
  isContentEditable?: boolean
  closest?: (selector: string) => unknown
}

export function isChartFullscreenShortcut(event: ShortcutEvent) {
  return event.key.toLowerCase() === 'f' && event.shiftKey && !event.ctrlKey && !event.metaKey && !event.altKey
}

export function shouldIgnoreChartFullscreenShortcut(target: ShortcutTarget | null | undefined) {
  if (!target) return false
  const tagName = target.tagName?.toUpperCase()
  if (tagName === 'INPUT' || tagName === 'TEXTAREA' || tagName === 'SELECT') return true
  if (target.isContentEditable) return true

  return Boolean(target.closest?.('form, [role="dialog"], [data-shortcut-disabled="true"]'))
}
