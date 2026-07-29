export type OverlayCloseSource = 'escape' | 'backdrop'
export type OverlayPriority = 'standard' | 'critical'

export type OverlaySnapshot = {
  layer: number
  top: boolean
}

type OverlayId = string | symbol | object
type OverlayListener = (snapshot: OverlaySnapshot) => void
type OverlayActivator = (preferredFocus: HTMLElement | null) => void
type OverlayFocusOwner = (target: HTMLElement) => boolean
type OverlayScope = object

type OverlayEntry = {
  id: OverlayId
  priority: OverlayPriority
  order: number
  listener: OverlayListener
  activate?: OverlayActivator
  restoreFocus: HTMLElement | null
  ownsFocus?: OverlayFocusOwner
  scope: OverlayScope
}

type OverlayRemoval = {
  wasTop: boolean
  activateNext?: OverlayActivator
  restoreFocus?: HTMLElement | null
}

export type OverlayCloseOptions = {
  source: OverlayCloseSource
  pending?: boolean
  closeOnEscape?: boolean
  closeOnBackdrop?: boolean
}

export function shouldCloseOverlay({
  source,
  pending = false,
  closeOnEscape = true,
  closeOnBackdrop = true
}: OverlayCloseOptions) {
  if (pending) return false
  return source === 'escape' ? closeOnEscape : closeOnBackdrop
}

const focusableSelector = [
  'a[href]',
  'area[href]',
  'button:not(:disabled)',
  'input:not(:disabled):not([type="hidden"])',
  'select:not(:disabled)',
  'textarea:not(:disabled)',
  'summary',
  'audio[controls]',
  'video[controls]',
  'iframe',
  'object',
  'embed',
  '[contenteditable]:not([contenteditable="false"])',
  '[tabindex]:not([tabindex="-1"])'
].join(',')

export function getFocusableElements(panel: HTMLElement) {
  const focusable = Array.from(panel.querySelectorAll<HTMLElement>(focusableSelector)).filter((element) => {
    if (element.closest?.('[hidden], [inert], [aria-hidden="true"]')) return false
    if (typeof element.tabIndex === 'number' && element.tabIndex < 0) return false
    const style = element.ownerDocument?.defaultView?.getComputedStyle?.(element)
    if (style?.display === 'none' || style?.visibility === 'hidden') return false
    return !element.getClientRects || element.getClientRects().length > 0
  })
  const radioGroups = new Map<object | null, Map<string, HTMLInputElement>>()
  focusable.forEach((element) => {
    const radio = asNamedRadio(element)
    if (!radio) return
    const form = radio.form ?? null
    const group = radioGroups.get(form) ?? new Map<string, HTMLInputElement>()
    const current = group.get(radio.name)
    if (!current || radio.checked) group.set(radio.name, radio)
    radioGroups.set(form, group)
  })
  return focusable.filter((element) => {
    const radio = asNamedRadio(element)
    return !radio || radioGroups.get(radio.form ?? null)?.get(radio.name) === radio
  })
}

function asNamedRadio(element: HTMLElement) {
  if (element.tagName !== 'INPUT') return null
  const input = element as HTMLInputElement
  return input.type === 'radio' && input.name ? input : null
}

export function focusInitialElement(panel: HTMLElement, requested: HTMLElement | null = null) {
  const focusable = getFocusableElements(panel)
  const target = requested && focusable.includes(requested) ? requested : (focusable[0] ?? panel)
  target.focus()
}

export function trapTabKey(event: KeyboardEvent, panel: HTMLElement) {
  if (event.key !== 'Tab') return
  const focusable = getFocusableElements(panel)
  if (focusable.length === 0) {
    event.preventDefault()
    panel.focus()
    return
  }

  const active = panel.ownerDocument.activeElement
  const activeIndex = focusable.indexOf(active as HTMLElement)
  const shouldWrapBackward = event.shiftKey && activeIndex <= 0
  const shouldWrapForward = !event.shiftKey && (activeIndex === -1 || activeIndex === focusable.length - 1)
  if (!shouldWrapBackward && !shouldWrapForward) return

  event.preventDefault()
  ;(event.shiftKey ? focusable.at(-1) : focusable[0])?.focus()
}

export function createOverlayStack() {
  const entries = new Map<OverlayId, OverlayEntry>()
  const storeListeners = new Set<() => void>()
  const defaultScope = {}
  let nextOrder = 0

  const orderedEntries = (scope: OverlayScope) => [...entries.values()].filter((entry) => entry.scope === scope).sort((left, right) => {
    const priorityDifference = priorityRank(left.priority) - priorityRank(right.priority)
    return priorityDifference || left.order - right.order
  })

  const notify = () => {
    const scopes = new Set([...entries.values()].map((entry) => entry.scope))
    scopes.forEach((scope) => {
      const ordered = orderedEntries(scope)
      ordered.forEach((entry, layer) => entry.listener({ layer, top: layer === ordered.length - 1 }))
    })
    storeListeners.forEach((listener) => listener())
  }

  const isTop = (id: OverlayId) => {
    const entry = entries.get(id)
    return Boolean(entry && orderedEntries(entry.scope).at(-1)?.id === id)
  }

  return {
    register(
      id: OverlayId,
      priority: OverlayPriority,
      listener: OverlayListener,
      activate?: OverlayActivator,
      restoreFocus: HTMLElement | null = null,
      ownsFocus?: OverlayFocusOwner,
      scope: OverlayScope = defaultScope
    ) {
      const existing = entries.get(id)
      entries.set(id, existing
        ? { ...existing, priority, listener, activate, restoreFocus, ownsFocus, scope }
        : { id, priority, listener, activate, restoreFocus, ownsFocus, scope, order: nextOrder++ })
      notify()

      let active = true
      return (): OverlayRemoval => {
        if (!active || !entries.has(id)) return { wasTop: false }
        active = false
        const wasTop = isTop(id)
        const removed = entries.get(id)
        if (!removed) return { wasTop: false }
        entries.forEach((entry) => {
          if (
            entry.id !== id
            && entry.scope === removed.scope
            && entry.restoreFocus
            && removed.ownsFocus?.(entry.restoreFocus)
          ) {
            entry.restoreFocus = removed.restoreFocus
          }
        })
        entries.delete(id)
        const nextTop = orderedEntries(removed.scope).at(-1)
        notify()
        return {
          wasTop,
          activateNext: wasTop ? nextTop?.activate : undefined,
          restoreFocus: removed.restoreFocus
        }
      }
    },
    subscribe(listener: () => void) {
      storeListeners.add(listener)
      return () => {
        storeListeners.delete(listener)
      }
    },
    updatePriority(id: OverlayId, priority: OverlayPriority) {
      const entry = entries.get(id)
      if (!entry || entry.priority === priority) return
      const previousTopId = orderedEntries(entry.scope).at(-1)?.id
      entry.priority = priority
      notify()
      const nextTop = orderedEntries(entry.scope).at(-1)
      return nextTop?.id === previousTopId ? undefined : nextTop?.activate
    },
    isTop,
    hasPriority: (priority: OverlayPriority) => [...entries.values()].some((entry) => entry.priority === priority),
    size: () => entries.size
  }
}

const dialogOverlayStack = createOverlayStack()

export function registerDialogOverlay(
  id: OverlayId,
  priority: OverlayPriority,
  listener: OverlayListener,
  activate?: OverlayActivator,
  restoreFocus: HTMLElement | null = null,
  ownsFocus?: OverlayFocusOwner,
  scope?: OverlayScope
) {
  return dialogOverlayStack.register(id, priority, listener, activate, restoreFocus, ownsFocus, scope)
}

export function updateDialogOverlayPriority(id: OverlayId, priority: OverlayPriority) {
  return dialogOverlayStack.updatePriority(id, priority)
}

export function isTopDialogOverlay(id: OverlayId) {
  return dialogOverlayStack.isTop(id)
}

export function subscribeDialogOverlay(listener: () => void): () => void {
  return dialogOverlayStack.subscribe(listener)
}

export function getCriticalDialogOpen(): boolean {
  return dialogOverlayStack.hasPriority('critical')
}

function priorityRank(priority: OverlayPriority) {
  return priority === 'critical' ? 1 : 0
}

type ScrollLockState = {
  count: number
  overflow: string
}

const scrollLocks = new WeakMap<HTMLElement, ScrollLockState>()

export function lockBodyScroll(ownerDocument: Document, panel: HTMLElement | null = null) {
  const targets = new Set<HTMLElement>([ownerDocument.body])
  let ancestor = panel?.parentElement ?? null
  while (ancestor && ancestor !== ownerDocument.body) {
    if (isScrollable(ownerDocument, ancestor)) targets.add(ancestor)
    ancestor = ancestor.parentElement
  }

  const descendants = ownerDocument.body.querySelectorAll
    ? ownerDocument.body.querySelectorAll<HTMLElement>('*')
    : []
  descendants.forEach((element) => {
    if (element === panel || panel?.contains?.(element) || element.closest?.('[role="dialog"]')) return
    if (isScrollable(ownerDocument, element)) targets.add(element)
  })

  targets.forEach(lockScrollElement)

  let locked = true
  return () => {
    if (!locked) return
    locked = false
    targets.forEach(unlockScrollElement)
  }
}

function isScrollable(ownerDocument: Document, element: HTMLElement) {
  if (scrollLocks.has(element)) return true
  const style = ownerDocument.defaultView?.getComputedStyle(element)
  return Boolean(style && [style.overflow, style.overflowX, style.overflowY]
    .some((value) => /(^|\s)(auto|scroll|overlay)(\s|$)/u.test(value)))
}

function lockScrollElement(element: HTMLElement) {
  const current = scrollLocks.get(element)
  if (current) {
    current.count += 1
    return
  }
  scrollLocks.set(element, { count: 1, overflow: element.style.overflow })
  element.style.overflow = 'hidden'
}

function unlockScrollElement(element: HTMLElement) {
  const state = scrollLocks.get(element)
  if (!state) return
  state.count -= 1
  if (state.count > 0) return
  element.style.overflow = state.overflow
  scrollLocks.delete(element)
}
