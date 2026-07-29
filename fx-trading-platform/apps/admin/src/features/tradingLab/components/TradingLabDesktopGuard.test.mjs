import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const currentDir = dirname(fileURLToPath(import.meta.url))
const componentSource = readFileSync(
  join(currentDir, 'TradingLabDesktopGuard.tsx'),
  'utf8',
)
const widthSource = readFileSync(join(currentDir, '..', 'desktopWidth.ts'), 'utf8')

test('uses the runtime media-query source through useSyncExternalStore', () => {
  assert.match(componentSource, /createTradingLabDesktopWidthSource/)
  assert.match(componentSource, /window\.matchMedia\(query\)/)
  assert.match(componentSource, /useSyncExternalStore\(/)
  assert.match(
    widthSource,
    /TRADING_LAB_DESKTOP_MEDIA_QUERY\s*=\s*'\(min-width: 1280px\)'/,
  )
  assert.match(
    widthSource,
    /matchMedia\(TRADING_LAB_DESKTOP_MEDIA_QUERY\)/,
  )
})

test('returns the exact narrow state before the separate children branch', () => {
  assert.match(
    widthSource,
    /交易路径实验室首版仅支持宽度不低于 1280px 的桌面端。/,
  )
  assert.match(componentSource, /if\s*\(!isDesktop\)\s*\{/)
  assert.match(componentSource, /role="status"/)
  assert.match(componentSource, /\{TRADING_LAB_NARROW_MESSAGE\}/)
  assert.match(componentSource, /return <>\{children\}<\/>/)

  const narrowBranchStart = componentSource.indexOf('if (!isDesktop)')
  const childrenBranchStart = componentSource.indexOf('return <>{children}</>')
  assert.notEqual(narrowBranchStart, -1)
  assert.notEqual(childrenBranchStart, -1)
  assert.ok(narrowBranchStart < childrenBranchStart)
  assert.doesNotMatch(
    componentSource.slice(narrowBranchStart, childrenBranchStart),
    /\{children\}/,
  )
})
