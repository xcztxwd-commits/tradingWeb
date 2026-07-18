import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { createControllerLeaseRegistry } from './controllerLeaseRegistry.ts'

describe('account controller effect leases', () => {
  it('survives the StrictMode cleanup/setup probe for the same controller', async () => {
    let disposals = 0
    const controller = { dispose: () => { disposals += 1 } }
    const leases = createControllerLeaseRegistry()

    const releaseFirstEffect = leases.acquire(controller)
    releaseFirstEffect()
    const releaseStrictModeSetup = leases.acquire(controller)
    await Promise.resolve()
    assert.equal(disposals, 0)

    releaseStrictModeSetup()
    await Promise.resolve()
    assert.equal(disposals, 1)
  })

  it('disposes an old controller when a dependency change installs a new one', async () => {
    let oldDisposals = 0
    let nextDisposals = 0
    const oldController = { dispose: () => { oldDisposals += 1 } }
    const nextController = { dispose: () => { nextDisposals += 1 } }
    const leases = createControllerLeaseRegistry()

    const releaseOld = leases.acquire(oldController)
    releaseOld()
    const releaseNext = leases.acquire(nextController)
    await Promise.resolve()
    assert.equal(oldDisposals, 1)
    assert.equal(nextDisposals, 0)

    releaseNext()
    await Promise.resolve()
    assert.equal(nextDisposals, 1)
  })
})
