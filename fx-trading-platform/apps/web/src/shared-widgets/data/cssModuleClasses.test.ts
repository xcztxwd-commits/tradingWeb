import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { cssModuleClasses } from './cssModuleClasses.ts'

describe('cssModuleClasses', () => {
  it('maps local keys and ignores disabled tokens', () => {
    assert.equal(
      cssModuleClasses(
        { root: 'root_hash', active: 'active_hash' },
        'root',
        false,
        null,
        undefined,
        'active'
      ),
      'root_hash active_hash'
    )
  })

  it('fails closed when a CSS Module key is missing', () => {
    assert.throws(
      () => cssModuleClasses({ root: 'root_hash' }, 'missing'),
      /Missing CSS Module class: missing/
    )
  })
})
