import assert from 'node:assert/strict'
import test from 'node:test'

import { createTradingLabWorkspaceAsyncGate } from './workspaceAsyncGate.ts'

test('a lock transition permanently fences an in-flight unlocked operation', () => {
  const gate = createTradingLabWorkspaceAsyncGate(false)
  const operation = gate.begin()

  assert.notEqual(operation, null)
  assert.equal(gate.isCurrent(operation!), true)

  gate.setLocked(true)
  assert.equal(gate.isCurrent(operation!), false)

  gate.setLocked(false)
  assert.equal(
    gate.isCurrent(operation!),
    false,
    'unlocking must not resurrect work that crossed a locked state',
  )
})

test('the latest async draft operation supersedes every older result', () => {
  const gate = createTradingLabWorkspaceAsyncGate(false)
  const first = gate.begin()
  const second = gate.begin()

  assert.notEqual(first, null)
  assert.notEqual(second, null)
  assert.equal(gate.isCurrent(first!), false)
  assert.equal(gate.isCurrent(second!), true)
})

test('cleanup invalidation fences old work but permits a StrictMode re-setup', () => {
  const gate = createTradingLabWorkspaceAsyncGate(false)
  const firstMount = gate.begin({ allowLocked: true })

  gate.invalidate()

  assert.equal(
    gate.isCurrent(firstMount!, { allowLocked: true }),
    false,
  )
  const strictModeRemount = gate.begin({ allowLocked: true })
  assert.notEqual(strictModeRemount, null)
  assert.equal(
    gate.isCurrent(strictModeRemount!, { allowLocked: true }),
    true,
  )
})

test('locked operations cannot begin while an initial read may be explicitly allowed', () => {
  const gate = createTradingLabWorkspaceAsyncGate(true)

  assert.equal(gate.begin(), null)
  const initialRead = gate.begin({ allowLocked: true })
  assert.notEqual(initialRead, null)
  assert.equal(
    gate.isCurrent(initialRead!, { allowLocked: true }),
    true,
  )
  assert.equal(gate.isCurrent(initialRead!), false)
})

test('dispose fences every operation and cannot be undone', () => {
  const gate = createTradingLabWorkspaceAsyncGate(false)
  const operation = gate.begin()

  gate.dispose()

  assert.equal(gate.isCurrent(operation!), false)
  assert.equal(gate.begin(), null)
  gate.setLocked(false)
  assert.equal(gate.begin(), null)
})
