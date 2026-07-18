import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { afterEach, describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'

import { findFrontendBoundaryViolations } from './verify-frontend-boundaries.mjs'

const fixtureRoots = []

afterEach(() => {
  while (fixtureRoots.length > 0) {
    rmSync(fixtureRoots.pop(), { force: true, recursive: true })
  }
})

describe('frontend dependency boundaries', () => {
  it('rejects every forbidden layer edge across supported import forms', () => {
    const root = createFixture()

    writeFixture(root, 'packages/ui/src/static.ts', "import '@fx-platform/frontend-core'\n")
    writeFixture(root, 'packages/ui/src/export.ts', "export { Link } from 'react-router-dom'\n")
    writeFixture(root, 'packages/ui/src/app.ts', "import '../../../apps/web/src/app/App'\n")
    writeFixture(root, 'packages/frontend-core/src/dynamic.ts', "const ui = import('@fx-platform/ui')\n")
    writeFixture(root, 'packages/frontend-core/src/styles.ts', "import './styles.css'\n")
    writeFixture(root, 'packages/frontend-core/src/router.ts', "export * from 'react-i18next'\n")
    writeFixture(root, 'apps/web/src/pc/Cross.tsx', "import '../mobile/View'\n")
    writeFixture(root, 'apps/web/src/mobile/Windows.tsx', String.raw`import '..\pc\View'`)
    writeFixture(root, 'apps/web/src/shared-widgets/Cross.ts', "export * from '../pc/View'\n")
    writeFixture(root, 'apps/web/src/routes/uiDeep.ts', "import '@fx-platform/ui/src/theme/themes'\n")
    writeFixture(root, 'apps/web/src/routes/coreDeep.ts', "const core = import('@fx-platform/frontend-core/src/api/apiClient')\n")

    const violations = findFrontendBoundaryViolations(root)

    assert.equal(violations.length, 11)
    assert.deepEqual(violations, [...violations].sort())
    assertViolation(violations, 'packages/ui/src/static.ts', '@fx-platform/frontend-core')
    assertViolation(violations, 'packages/ui/src/export.ts', 'react-router-dom')
    assertViolation(violations, 'packages/ui/src/app.ts', 'apps/web/src/app/App')
    assertViolation(violations, 'packages/frontend-core/src/dynamic.ts', '@fx-platform/ui')
    assertViolation(violations, 'packages/frontend-core/src/styles.ts', 'styles.css')
    assertViolation(violations, 'packages/frontend-core/src/router.ts', 'react-i18next')
    assertViolation(violations, 'apps/web/src/pc/Cross.tsx', 'apps/web/src/mobile/View')
    assertViolation(violations, 'apps/web/src/mobile/Windows.tsx', 'apps/web/src/pc/View')
    assertViolation(violations, 'apps/web/src/shared-widgets/Cross.ts', 'apps/web/src/pc/View')
    assertViolation(violations, 'apps/web/src/routes/uiDeep.ts', '@fx-platform/ui/src/theme/themes')
    assertViolation(violations, 'apps/web/src/routes/coreDeep.ts', '@fx-platform/frontend-core/src/api/apiClient')
  })

  it('allows package internals and the documented downward dependency graph', () => {
    const root = createFixture()

    writeFixture(root, 'packages/ui/src/components/Button.tsx', [
      "import React from 'react'",
      "import { createPortal } from 'react-dom'",
      "import { Check } from 'lucide-react'",
      "import './Button.css'",
      "import { tokens } from '../theme/tokens'"
    ].join('\n'))
    writeFixture(root, 'packages/frontend-core/src/market/store.ts', [
      "import React from 'react'",
      "import { create } from 'zustand'",
      "import { Client } from '@stomp/stompjs'",
      "import type { components } from '@fx-platform/shared-types'",
      "import { normalize } from './normalize'"
    ].join('\n'))
    writeFixture(root, 'apps/web/src/pc/Page.tsx', [
      "import { Button } from '@fx-platform/ui'",
      "import { api } from '@fx-platform/frontend-core/api'",
      "import { Asset } from '../shared-widgets/Asset'",
      "import { PcOnly } from './PcOnly'"
    ].join('\n'))
    writeFixture(root, 'apps/web/src/mobile/Page.tsx', [
      "import { Button } from '@fx-platform/ui'",
      "import { api } from '@fx-platform/frontend-core/api'",
      "import { Asset } from '../shared-widgets/Asset'",
      "import { MobileOnly } from './MobileOnly'"
    ].join('\n'))
    writeFixture(root, 'apps/web/src/shared-widgets/Asset.tsx', [
      "import { Button } from '@fx-platform/ui'",
      "import type { Account } from '@fx-platform/frontend-core/models'",
      "import { format } from './format'"
    ].join('\n'))
    writeFixture(root, 'apps/web/src/routes/HomeRoute.tsx', [
      "import { lazy } from 'react'",
      "import { useNavigate } from 'react-router-dom'",
      "const Pc = lazy(() => import('../pc/Page'))",
      "const Mobile = lazy(() => import('../mobile/Page'))"
    ].join('\n'))

    assert.deepEqual(findFrontendBoundaryViolations(root), [])
  })

  it('scans supported source extensions and ignores generated, dependency and declaration files', () => {
    const root = createFixture()

    for (const extension of ['js', 'jsx', 'mjs', 'ts', 'tsx']) {
      writeFixture(root, `apps/web/src/pc/z-${extension}.${extension}`, "import '../mobile/View'\n")
    }
    writeFixture(root, 'apps/web/src/pc/ignored.d.ts', "export * from '../mobile/View'\n")
    writeFixture(root, 'apps/web/src/pc/dist/ignored.ts', "import '../../mobile/View'\n")
    writeFixture(root, 'apps/web/src/pc/node_modules/ignored.ts', "import '../../mobile/View'\n")
    writeFixture(root, 'apps/web/src/pc/coverage/ignored.ts', "import '../../mobile/View'\n")

    const violations = findFrontendBoundaryViolations(root)

    assert.equal(violations.length, 5)
    assert.deepEqual(violations, [...violations].sort())
    for (const extension of ['js', 'jsx', 'mjs', 'ts', 'tsx']) {
      assertViolation(violations, `apps/web/src/pc/z-${extension}.${extension}`, 'apps/web/src/mobile/View')
    }
  })

  it('registers the frontend gates in architecture verification and durable docs', () => {
    const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')
    const packageJson = JSON.parse(readFileSync(resolve(projectRoot, 'package.json'), 'utf8'))
    const architectureVerifier = readFileSync(resolve(projectRoot, 'scripts/verify-architecture.mjs'), 'utf8')
    const architectureDoc = readFileSync(resolve(projectRoot, 'docs/architecture.md'), 'utf8')

    assert.equal(packageJson.scripts['test:frontend-boundaries'], 'node --test scripts/verify-frontend-boundaries.test.mjs')
    assert.equal(packageJson.scripts['verify:frontend-boundaries'], 'node scripts/verify-frontend-boundaries.mjs')
    assert.equal(packageJson.scripts['ui:test'], 'npm --workspace packages/ui run test')
    assert.equal(packageJson.scripts['ui:typecheck'], 'npm --workspace packages/ui run typecheck')
    assert.equal(packageJson.scripts['frontend-core:test'], 'npm --workspace packages/frontend-core run test')
    assert.equal(packageJson.scripts['frontend-core:typecheck'], 'npm --workspace packages/frontend-core run typecheck')
    assert.equal(
      packageJson.scripts['frontend:check'],
      'npm run ui:test && npm run ui:typecheck && npm run frontend-core:test && npm run frontend-core:typecheck && npm run web:test && npm run web:build && npm run verify:frontend-boundaries && npm run verify:architecture && npm run web:bundle-budget && npm run audit:large-files'
    )
    assert.match(architectureVerifier, /findFrontendBoundaryViolations/u)
    assert.match(architectureVerifier, /Frontend dependency boundary/u)
    assert.match(architectureDoc, /@fx-platform\/ui/u)
    assert.match(architectureDoc, /@fx-platform\/frontend-core/u)
    assert.match(architectureDoc, /900px/u)
    assert.match(architectureDoc, /901px/u)
  })
})

function createFixture() {
  const root = mkdtempSync(join(tmpdir(), 'fx-frontend-boundaries-'))
  fixtureRoots.push(root)
  return root
}

function writeFixture(root, relativePath, content) {
  const target = join(root, relativePath)
  mkdirSync(dirname(target), { recursive: true })
  writeFileSync(target, content, 'utf8')
}

function assertViolation(violations, source, target) {
  assert(
    violations.some((violation) => violation.includes(source) && violation.includes(target)),
    `Expected violation from ${source} to ${target}, received:\n${violations.join('\n')}`
  )
}
