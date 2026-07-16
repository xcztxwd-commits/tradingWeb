import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const hookPath = new URL('../.husky/commit-msg', import.meta.url)

test('commit-msg is a tracked executable Husky hook that preserves commit lint', () => {
  const indexEntry = execFileSync(
    'git',
    ['ls-files', '--stage', '--', '.husky/commit-msg'],
    { encoding: 'utf8' }
  )
  const hook = readFileSync(hookPath, 'utf8')

  assert.match(indexEntry, /^100755\s/)
  assert.match(hook, /^#!\/usr\/bin\/env sh\r?\n/)
  assert.match(hook, /^\. "\$\(dirname -- "\$0"\)\/_\/husky\.sh"$/m)
  assert.match(hook, /^npm run commit-lint$/m)
})
