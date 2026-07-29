import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { afterEach, describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'

import { findFrontendStyleViolations } from './verify-frontend-styles.mjs'

const fixtureRoots = []

afterEach(() => {
  while (fixtureRoots.length > 0) rmSync(fixtureRoots.pop(), { force: true, recursive: true })
})

describe('frontend style ownership', () => {
  it('rejects an oversized global stylesheet and route-specific class selectors', () => {
    const root = createFixture()
    writeFixture(root, 'apps/web/src/styles.css', [
      ':root { color: var(--theme-text-primary); }',
      '.sr-only { position: absolute; }',
      '.route-page { display: grid; }',
      ...Array.from({ length: 399 }, () => '')
    ].join('\n'))

    const violations = findFrontendStyleViolations(root)

    assert(violations.some((violation) => violation.includes('402 lines') && violation.includes('maximum is 400')))
    assert(violations.some((violation) => violation.includes('.route-page') && violation.includes('styles.css')))
    assert.equal(violations.some((violation) => violation.includes('.sr-only')), false)
  })

  it('rejects direct hex, rgb and hsl colors in app and UI component CSS', () => {
    const root = createFixture()
    writeFixture(root, 'apps/web/src/Widget.module.css', '.a { color: #fff; background: rgb(1 2 3 / 40%); }')
    writeFixture(root, 'packages/ui/src/Button.module.css', '.button { border-color: hsl(2 3% 4%); }')
    writeFixture(root, 'packages/ui/src/theme/theme.css', ':root { --theme-text-primary: #ffffff; --theme-overlay: rgba(0, 0, 0, 0.4); }')

    const violations = findFrontendStyleViolations(root)

    assert.equal(violations.filter((violation) => violation.includes('direct color literal')).length, 3)
    assert.equal(violations.some((violation) => violation.includes('theme/theme.css')), false)
  })

  it('rejects direct reference palette tokens outside the theme stylesheet', () => {
    const root = createFixture()
    writeFixture(root, 'apps/web/src/Widget.module.css', '.root { color: var(--theme-reference-legacy-blue); }')

    const violations = findFrontendStyleViolations(root)

    assert(violations.some((violation) => violation.includes('Widget.module.css')
      && violation.includes('reference palette token --theme-reference-legacy-blue')))
  })

  it('only exempts direct colors in the theme stylesheet', () => {
    const root = createFixture()
    writeFixture(root, 'packages/ui/src/theme/theme.css', [
      ':root { --theme-text-primary: #ffffff; }',
      ':global { .legacy { color: var(--theme-reference-legacy-blue); } }',
      '@media (max-width: 768px) { :root { --theme-text-primary: #000000; } }'
    ].join('\n'))

    const violations = findFrontendStyleViolations(root)

    assert.equal(violations.some((violation) => violation.includes('direct color literal')), false)
    assert(violations.some((violation) => violation.includes('theme/theme.css') && violation.includes('block-form :global')))
    assert(violations.some((violation) => violation.includes('theme/theme.css')
      && violation.includes('reference palette token --theme-reference-legacy-blue')))
    assert(violations.some((violation) => violation.includes('theme/theme.css')
      && violation.includes('retired 768/769 breakpoint')))
  })

  it('rejects retired 768/769 structural breakpoint branches', () => {
    const root = createFixture()
    writeFixture(root, 'apps/web/src/A.module.css', '@media (max-width: 768px) { .a { display: none; } }')
    writeFixture(root, 'packages/ui/src/B.module.css', '@media (min-width: 769px) { .b { display: block; } }')

    const violations = findFrontendStyleViolations(root)

    assert.equal(violations.filter((violation) => violation.includes('retired 768/769 breakpoint')).length, 2)
  })

  it('rejects block-form :global wrappers that produce invalid CSS module output', () => {
    const root = createFixture()
    writeFixture(root, 'apps/web/src/ApplicationSurfaces.module.css', [
      '.root { isolation: isolate; }',
      ':global {',
      '  .mobile-tabs { position: fixed; }',
      '}'
    ].join('\n'))

    const violations = findFrontendStyleViolations(root)

    assert(violations.some((violation) => violation.includes('ApplicationSurfaces.module.css') && violation.includes('block-form :global')))
  })

  it('rejects global class bridges after component ownership has migrated', () => {
    const root = createFixture()
    writeFixture(root, 'apps/web/src/Route.module.css', [
      '.root :global(.legacy-route) { display: grid; }',
      ':global(:root[data-theme="dark"]) .root { color: var(--theme-text-primary); }'
    ].join('\n'))

    const violations = findFrontendStyleViolations(root)

    assert(violations.some((violation) => violation.includes('Route.module.css') && violation.includes('global class bridge .legacy-route')))
    assert.equal(violations.some((violation) => violation.includes('data-theme')), false)
  })

  it('allows reset selectors, semantic variables, transparent/currentColor and chart colors outside CSS', () => {
    const root = createFixture()
    writeFixture(root, 'apps/web/src/styles.css', [
      ':root { color: var(--theme-text-primary); }',
      '* { box-sizing: border-box; }',
      'html, body, #root { min-height: 100%; }',
      'button, input, select { font: inherit; }',
      '.sr-only { clip-path: inset(50%); }'
    ].join('\n'))
    writeFixture(root, 'apps/web/src/Widget.module.css', [
      '.root { color: currentColor; background: transparent; }',
      '.root:hover { color: var(--theme-primary); }',
      '.root:focus { outline-color: color-mix(in srgb, var(--theme-primary) 40%, transparent); }'
    ].join('\n'))
    writeFixture(root, 'apps/web/src/shared-widgets/trading/chartTheme.ts', "export const chartUp = '#2ebd85'\n")

    assert.deepEqual(findFrontendStyleViolations(root), [])
  })

  it('registers fixture tests, the verifier and the total frontend gate', () => {
    const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
    const packageJson = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'))
    const viteConfig = readFileSync(join(root, 'apps', 'web', 'vite.config.ts'), 'utf8')

    assert.equal(packageJson.scripts['test:frontend-styles'], 'node --test scripts/verify-frontend-styles.test.mjs')
    assert.equal(packageJson.scripts['verify:frontend-styles'], 'node scripts/verify-frontend-styles.mjs')
    assert.match(packageJson.scripts['frontend:check'], /verify:frontend-boundaries && npm run verify:frontend-styles && npm run verify:architecture/u)
    assert.match(viteConfig, /['"]css-syntax-error['"]\s*:\s*['"]error['"]/u)
  })
})

function createFixture() {
  const root = mkdtempSync(join(tmpdir(), 'fx-frontend-styles-'))
  fixtureRoots.push(root)
  return root
}

function writeFixture(root, relativePath, content) {
  const target = join(root, relativePath)
  mkdirSync(dirname(target), { recursive: true })
  writeFileSync(target, content, 'utf8')
}
