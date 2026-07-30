import assert from 'node:assert/strict'
import test from 'node:test'

import { createLatestRequestGate } from './latestRequestGate.ts'

test('an older symbol binding response cannot replace the latest selection', async () => {
  const gate = createLatestRequestGate()
  const first = deferred<string>()
  const second = deferred<string>()
  const applied: string[] = []

  const firstLoad = applyWhenCurrent(first.promise)
  const secondLoad = applyWhenCurrent(second.promise)

  second.resolve('BTCUSDT-PERP')
  await secondLoad
  first.resolve('BTCUSDT')
  await firstLoad

  assert.deepEqual(applied, ['BTCUSDT-PERP'])

  async function applyWhenCurrent(response: Promise<string>) {
    const request = gate.begin()
    const symbol = await response
    if (gate.isCurrent(request)) {
      applied.push(symbol)
    }
  }
})

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((complete) => {
    resolve = complete
  })
  return { promise, resolve }
}
