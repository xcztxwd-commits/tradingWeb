import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { createPendingOperationGuard } from './pendingOperationGuard.ts'

describe('pending operation guard', () => {
  it('coalesces the same in-flight mutation across a platform view switch', async () => {
    const guard = createPendingOperationGuard()
    let resolveMutation!: (value: string) => void
    let submissions = 0
    const mutation = () => {
      submissions += 1
      return new Promise<string>((resolve) => {
        resolveMutation = resolve
      })
    }

    const fromPc = guard.run('order:cancel:order-1', mutation)
    const fromMobile = guard.run('order:cancel:order-1', mutation)

    assert.equal(submissions, 1)
    assert.strictEqual(fromMobile, fromPc)
    assert.equal(guard.isPending('order:cancel:order-1'), true)

    resolveMutation('done')
    assert.equal(await fromPc, 'done')
    assert.equal(await fromMobile, 'done')
    assert.equal(guard.isPending('order:cancel:order-1'), false)
  })

  it('allows independent operations and releases failed mutations', async () => {
    const guard = createPendingOperationGuard()
    const first = guard.run('position:close:one', async () => 'one')
    const second = guard.run('position:close:two', async () => 'two')
    assert.deepEqual(await Promise.all([first, second]), ['one', 'two'])

    await assert.rejects(guard.run('wallet:transfer', async () => {
      throw new Error('failed')
    }), /failed/)
    assert.equal(guard.isPending('wallet:transfer'), false)
  })
})
