import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'

import {
  createOverlayStack,
  focusInitialElement,
  getCriticalDialogOpen,
  registerDialogOverlay,
  lockBodyScroll,
  shouldCloseOverlay,
  subscribeDialogOverlay,
  trapTabKey,
  updateDialogOverlayPriority
} from './overlayState.ts'

const currentDir = dirname(fileURLToPath(import.meta.url))
const componentSource = readFileSync(resolve(currentDir, 'Dialog.tsx'), 'utf8')
const styles = readFileSync(resolve(currentDir, 'Dialog.module.css'), 'utf8')

describe('overlay close decisions', () => {
  it('allows configured Escape and backdrop closes', () => {
    assert.equal(shouldCloseOverlay({ source: 'escape' }), true)
    assert.equal(shouldCloseOverlay({ source: 'backdrop' }), true)
    assert.equal(shouldCloseOverlay({ source: 'escape', closeOnEscape: false }), false)
    assert.equal(shouldCloseOverlay({ source: 'backdrop', closeOnBackdrop: false }), false)
  })

  it('blocks every close source while a domain action is pending', () => {
    assert.equal(shouldCloseOverlay({ source: 'escape', pending: true }), false)
    assert.equal(shouldCloseOverlay({ source: 'backdrop', pending: true }), false)
  })
})

describe('overlay focus behavior', () => {
  it('cycles Tab and Shift+Tab inside the panel', () => {
    const documentState: { activeElement: unknown } = { activeElement: null }
    const focused: string[] = []
    const element = (name: string) => ({
      focus() {
        focused.push(name)
        documentState.activeElement = this
      }
    })
    const first = element('first')
    const middle = element('middle')
    const last = element('last')
    const panel = {
      ownerDocument: documentState,
      querySelectorAll: () => [first, middle, last],
      focus: () => focused.push('panel')
    }

    documentState.activeElement = last
    let prevented = false
    trapTabKey({ key: 'Tab', shiftKey: false, preventDefault: () => { prevented = true } } as KeyboardEvent, panel as unknown as HTMLElement)
    assert.equal(prevented, true)
    assert.deepEqual(focused, ['first'])

    documentState.activeElement = first
    prevented = false
    trapTabKey({ key: 'Tab', shiftKey: true, preventDefault: () => { prevented = true } } as KeyboardEvent, panel as unknown as HTMLElement)
    assert.equal(prevented, true)
    assert.deepEqual(focused, ['first', 'last'])

    documentState.activeElement = middle
    prevented = false
    trapTabKey({ key: 'Tab', shiftKey: false, preventDefault: () => { prevented = true } } as KeyboardEvent, panel as unknown as HTMLElement)
    assert.equal(prevented, false)
    assert.deepEqual(focused, ['first', 'last'])
  })

  it('keeps focus inside when focus starts outside or the panel has no controls', () => {
    const documentState: { activeElement: unknown } = { activeElement: {} }
    const focused: string[] = []
    const first = { focus: () => focused.push('first') }
    const last = { focus: () => focused.push('last') }
    const panel = {
      ownerDocument: documentState,
      querySelectorAll: () => [first, last],
      focus: () => focused.push('panel')
    }

    trapTabKey({ key: 'Tab', shiftKey: false, preventDefault() {} } as KeyboardEvent, panel as unknown as HTMLElement)
    trapTabKey({ key: 'Tab', shiftKey: true, preventDefault() {} } as KeyboardEvent, panel as unknown as HTMLElement)
    assert.deepEqual(focused, ['first', 'last'])

    const emptyPanel = { ...panel, querySelectorAll: () => [] }
    trapTabKey({ key: 'Tab', shiftKey: false, preventDefault() {} } as KeyboardEvent, emptyPanel as unknown as HTMLElement)
    assert.deepEqual(focused, ['first', 'last', 'panel'])
  })

  it('treats a radio group as one tab stop', () => {
    const documentState: { activeElement: unknown } = { activeElement: null }
    const focused: string[] = []
    const radio = (name: string, checked: boolean) => ({
      tagName: 'INPUT',
      type: 'radio',
      name: 'delivery',
      checked,
      tabIndex: 0,
      focus: () => focused.push(name)
    })
    const unchecked = radio('unchecked', false)
    const checked = radio('checked', true)
    const cancel = { tabIndex: 0, focus: () => focused.push('cancel') }
    const panel = {
      ownerDocument: documentState,
      querySelectorAll: () => [unchecked, checked, cancel],
      focus: () => focused.push('panel')
    }

    documentState.activeElement = checked
    trapTabKey({ key: 'Tab', shiftKey: true, preventDefault() {} } as KeyboardEvent, panel as unknown as HTMLElement)
    assert.deepEqual(focused, ['cancel'])
  })

  it('skips controls inside hidden or inert ancestors', () => {
    const focused: string[] = []
    const blocked = {
      tabIndex: 0,
      closest: () => ({}),
      getClientRects: () => [{}],
      focus: () => focused.push('blocked')
    }
    const visible = {
      tabIndex: 0,
      closest: () => null,
      getClientRects: () => [{}],
      focus: () => focused.push('visible')
    }
    const panel = {
      querySelectorAll: () => [blocked, visible],
      focus: () => focused.push('panel')
    }

    focusInitialElement(panel as unknown as HTMLElement)
    assert.deepEqual(focused, ['visible'])
  })

  it('honors an in-panel initial focus target and otherwise uses the first control', () => {
    const focused: string[] = []
    const first = { focus: () => focused.push('first') }
    const requested = { focus: () => focused.push('requested') }
    const panel = {
      querySelectorAll: () => [first, requested],
      focus: () => focused.push('panel')
    }

    focusInitialElement(panel as unknown as HTMLElement, requested as unknown as HTMLElement)
    focusInitialElement(panel as unknown as HTMLElement, { focus() {} } as HTMLElement)
    assert.deepEqual(focused, ['requested', 'first'])
  })
})

describe('overlay stack and scroll lock', () => {
  it('keeps the newest critical overlay above later standard overlays', () => {
    const stack = createOverlayStack()
    const snapshots = new Map<string, { layer: number; top: boolean }>()
    const activations: Array<{ id: string; preferred: HTMLElement | null }> = []
    const register = (id: string, priority: 'standard' | 'critical') =>
      stack.register(
        id,
        priority,
        (snapshot) => snapshots.set(id, snapshot),
        (preferred) => { activations.push({ id, preferred }) }
      )

    const unregisterFirst = register('first', 'standard')
    const unregisterCritical = register('critical', 'critical')
    const unregisterLaterStandard = register('later-standard', 'standard')

    assert.deepEqual(snapshots.get('first'), { layer: 0, top: false })
    assert.deepEqual(snapshots.get('later-standard'), { layer: 1, top: false })
    assert.deepEqual(snapshots.get('critical'), { layer: 2, top: true })
    assert.equal(stack.isTop('critical'), true)
    assert.equal(unregisterLaterStandard().wasTop, false)
    const criticalResult = unregisterCritical()
    const lowerControl = {} as HTMLElement
    assert.equal(criticalResult.wasTop, true)
    criticalResult.activateNext?.(lowerControl)
    assert.deepEqual(activations, [{ id: 'first', preferred: lowerControl }])
    assert.deepEqual(snapshots.get('first'), { layer: 0, top: true })
    assert.equal(unregisterFirst().wasTop, true)
  })

  it('updates priority without duplicate registrations and cleanup is idempotent', () => {
    const stack = createOverlayStack()
    const snapshots: Array<{ layer: number; top: boolean }> = []
    const unregister = stack.register('dialog', 'standard', (snapshot) => snapshots.push(snapshot))
    stack.register('dialog', 'critical', (snapshot) => snapshots.push(snapshot))
    stack.updatePriority('dialog', 'standard')

    assert.equal(stack.size(), 1)
    assert.deepEqual(snapshots.at(-1), { layer: 0, top: true })
    assert.equal(unregister().wasTop, true)
    assert.equal(unregister().wasTop, false)
    assert.equal(stack.size(), 0)
  })

  it('activates the new top overlay when a live priority changes', () => {
    const stack = createOverlayStack()
    const activations: string[] = []
    const unregisterFirst = stack.register('first', 'standard', () => undefined, () => { activations.push('first') })
    const unregisterSecond = stack.register('second', 'standard', () => undefined, () => { activations.push('second') })

    stack.updatePriority('first', 'critical')?.(null)
    assert.deepEqual(activations, ['first'])
    assert.equal(stack.isTop('first'), true)

    unregisterFirst()
    unregisterSecond()
  })

  it('preserves the page restore target when a lower overlay unmounts first', () => {
    const stack = createOverlayStack()
    const pageTrigger = {} as HTMLElement
    const lowerControl = {} as HTMLElement
    const unregisterLower = stack.register(
      'lower',
      'standard',
      () => undefined,
      undefined,
      pageTrigger,
      (target) => target === lowerControl
    )
    const unregisterTop = stack.register(
      'top',
      'critical',
      () => undefined,
      undefined,
      lowerControl
    )

    assert.equal(unregisterLower().wasTop, false)
    const topRemoval = unregisterTop()
    assert.equal(topRemoval.wasTop, true)
    assert.equal(topRemoval.restoreFocus, pageTrigger)
  })

  it('keeps overlay interaction independent across owner documents', () => {
    const stack = createOverlayStack()
    const mainDocument = {}
    const frameDocument = {}
    let mainSnapshot = { layer: -1, top: false }
    let frameSnapshot = { layer: -1, top: false }
    const unregisterMain = stack.register(
      'main', 'standard', (snapshot) => { mainSnapshot = snapshot }, undefined, null, undefined, mainDocument
    )
    const unregisterFrame = stack.register(
      'frame', 'critical', (snapshot) => { frameSnapshot = snapshot }, undefined, null, undefined, frameDocument
    )

    assert.deepEqual(mainSnapshot, { layer: 0, top: true })
    assert.deepEqual(frameSnapshot, { layer: 0, top: true })
    assert.equal(stack.isTop('main'), true)
    assert.equal(stack.isTop('frame'), true)
    unregisterMain()
    unregisterFrame()
  })

  it('publishes a stable critical-dialog snapshot for the application shell', () => {
    let notifications = 0
    const unsubscribe = subscribeDialogOverlay(() => { notifications += 1 })
    const unregisterStandard = registerDialogOverlay('store-standard', 'standard', () => undefined)
    assert.equal(getCriticalDialogOpen(), false)
    const unregisterCritical = registerDialogOverlay('store-critical', 'critical', () => undefined)
    assert.equal(getCriticalDialogOpen(), true)
    updateDialogOverlayPriority('store-critical', 'standard')
    assert.equal(getCriticalDialogOpen(), false)
    updateDialogOverlayPriority('store-critical', 'critical')
    unregisterCritical()
    assert.equal(getCriticalDialogOpen(), false)
    unregisterStandard()
    unsubscribe()
    assert.equal(notifications, 6)
  })

  it('reference-counts body scroll locking and restores the original value', () => {
    const documentState = { body: { style: { overflow: 'scroll' } } } as unknown as Document
    const unlockFirst = lockBodyScroll(documentState)
    const unlockSecond = lockBodyScroll(documentState)

    assert.equal(documentState.body.style.overflow, 'hidden')
    unlockFirst()
    assert.equal(documentState.body.style.overflow, 'hidden')
    unlockFirst()
    assert.equal(documentState.body.style.overflow, 'hidden')
    unlockSecond()
    assert.equal(documentState.body.style.overflow, 'scroll')
  })

  it('reference-counts scrollable ancestors without locking the dialog panel', () => {
    const body = fakeScrollElement('scroll')
    const mainRegion = fakeScrollElement('clip', 'auto', body)
    const layer = fakeScrollElement('', 'visible', mainRegion)
    const panel = fakeScrollElement('auto', 'auto', layer)
    const documentState = fakeScrollDocument(body)

    const unlockFirst = lockBodyScroll(documentState, panel as unknown as HTMLElement)
    const unlockSecond = lockBodyScroll(documentState, panel as unknown as HTMLElement)

    assert.equal(body.style.overflow, 'hidden')
    assert.equal(mainRegion.style.overflow, 'hidden')
    assert.equal(layer.style.overflow, '')
    assert.equal(panel.style.overflow, 'auto')

    unlockFirst()
    unlockFirst()
    assert.equal(mainRegion.style.overflow, 'hidden')
    unlockSecond()
    assert.equal(body.style.overflow, 'scroll')
    assert.equal(mainRegion.style.overflow, 'clip')
    assert.equal(panel.style.overflow, 'auto')
  })

  it('locks sibling page scroll containers without locking dialog content', () => {
    const body = fakeScrollElement('')
    const mainRegion = fakeScrollElement('auto', 'auto', body)
    const layer = fakeScrollElement('', 'visible', body)
    const panel = fakeScrollElement('', 'visible', layer)
    const dialogBody = fakeScrollElement('auto', 'auto', panel)
    const candidates = [mainRegion, layer, panel, dialogBody]

    ;(body as FakeScrollElement & { querySelectorAll(): FakeScrollElement[] }).querySelectorAll = () => candidates
    ;(panel as FakeScrollElement & { contains(element: FakeScrollElement): boolean }).contains =
      (element) => element === dialogBody

    const unlock = lockBodyScroll(fakeScrollDocument(body), panel as unknown as HTMLElement)

    assert.equal(mainRegion.style.overflow, 'hidden')
    assert.equal(dialogBody.style.overflow, 'auto')
    unlock()
    assert.equal(mainRegion.style.overflow, 'auto')
    assert.equal(dialogBody.style.overflow, 'auto')
  })

  it('keeps ancestor locks isolated between owner documents', () => {
    const firstBody = fakeScrollElement('')
    const firstRegion = fakeScrollElement('auto', 'auto', firstBody)
    const firstPanel = fakeScrollElement('', 'visible', firstRegion)
    const secondBody = fakeScrollElement('scroll')
    const secondRegion = fakeScrollElement('overlay', 'overlay', secondBody)
    const secondPanel = fakeScrollElement('', 'visible', secondRegion)

    const unlockFirst = lockBodyScroll(
      fakeScrollDocument(firstBody),
      firstPanel as unknown as HTMLElement
    )
    const unlockSecond = lockBodyScroll(
      fakeScrollDocument(secondBody),
      secondPanel as unknown as HTMLElement
    )

    unlockFirst()
    assert.equal(firstRegion.style.overflow, 'auto')
    assert.equal(secondRegion.style.overflow, 'hidden')
    assert.equal(secondBody.style.overflow, 'hidden')
    unlockSecond()
    assert.equal(secondRegion.style.overflow, 'overlay')
    assert.equal(secondBody.style.overflow, 'scroll')
  })
})

type FakeScrollElement = {
  style: { overflow: string }
  computedOverflow: string
  parentElement: FakeScrollElement | null
}

function fakeScrollElement(
  inlineOverflow: string,
  computedOverflow = inlineOverflow,
  parentElement: FakeScrollElement | null = null
): FakeScrollElement {
  return {
    style: { overflow: inlineOverflow },
    computedOverflow,
    parentElement
  }
}

function fakeScrollDocument(body: FakeScrollElement) {
  return {
    body,
    defaultView: {
      getComputedStyle(element: FakeScrollElement) {
        const overflow = element.style.overflow === 'hidden'
          ? 'hidden'
          : element.computedOverflow
        return {
          overflow,
          overflowX: overflow,
          overflowY: overflow
        }
      }
    }
  } as unknown as Document
}

describe('Dialog component contract', () => {
  it('owns modal semantics, Escape, backdrop policy and focus restoration', () => {
    assert.match(componentSource, /export type DialogProps/u)
    assert.match(componentSource, /role="dialog"/u)
    assert.match(componentSource, /aria-modal="true"/u)
    assert.match(componentSource, /aria-labelledby=\{labelledBy\}/u)
    assert.match(componentSource, /event\.key !== 'Escape'/u)
    assert.match(componentSource, /shouldCloseOverlay/u)
    assert.match(componentSource, /ownerDocument\.activeElement/u)
    assert.match(componentSource, /\.focus\(\)/u)
    assert.match(componentSource, /trapTabKey/u)
    assert.match(componentSource, /lockBodyScroll/u)
    assert.match(componentSource, /priority\??:\s*DialogPriority/u)
    assert.match(componentSource, /initialFocusRef\??:/u)
    assert.match(componentSource, /typeof document === 'undefined'/u)
    assert.match(componentSource, /isTopDialogOverlay/u)
    assert.match(componentSource, /event\.stopPropagation\(\)/u)
    assert.doesNotMatch(componentSource, /react-i18next/u)
  })

  it('owns only generic overlay and panel visuals', () => {
    assert.match(styles, /\.layer/u)
    assert.match(styles, /\.backdrop/u)
    assert.match(styles, /\.panel/u)
    assert.match(styles, /@media \(prefers-reduced-motion:\s*reduce\)/u)
  })

})
