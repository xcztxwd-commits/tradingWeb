import assert from 'node:assert/strict'
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, it } from 'node:test'

const srcRoot = join(dirname(fileURLToPath(import.meta.url)), '..')

const retiredOwnershipDirectories = [
  'services',
  'features/market',
  'features/trading-session',
  'features/trading',
  'design-system',
  'components/asset',
  'components/user-page',
  'components/layout',
  'components/market-side-panel',
  'components/tables',
  'pages'
]

describe('final web ownership', () => {
  it('removes every retired implementation and compatibility ownership path', () => {
    for (const relativePath of retiredOwnershipDirectories) {
      assert.deepEqual(listFiles(join(srcRoot, relativePath)), [], `${relativePath} still owns source files`)
    }
    assert.equal(existsSync(join(srcRoot, 'types', 'trading.ts')), false)
  })

  it('keeps translated page states and their behavior tests under shared-widgets', () => {
    assert.equal(existsSync(join(srcRoot, 'shared-widgets', 'data', 'PageState.tsx')), true)
    assert.equal(existsSync(join(srcRoot, 'shared-widgets', 'data', 'userPageModels.test.ts')), true)
    assert.equal(existsSync(join(srcRoot, 'shared-widgets', 'data', 'userPageUi.test.ts')), true)

    for (const locale of ['zh-CN.ts', 'en-US.ts', 'ja-JP.ts']) {
      assert.doesNotMatch(readSource(`i18n/locales/${locale}`), /placeholderPageMessage/)
    }
  })

  it('removes the trading skeleton compatibility shim and inert legacy class tokens', () => {
    const bottomAccountPanel = readSource('shared-widgets/trading/components/BottomAccountPanel.tsx')
    const securityContent = readSource('shared-widgets/account/SecurityContent.tsx')
    const mobileDataCollection = readSource('mobile/components/MobileDataCollection.tsx')
    const pcDataCollection = readSource('pc/components/PcDataCollection.tsx')

    assert.equal(existsSync(join(srcRoot, 'shared-widgets', 'trading', 'components', 'TerminalSkeleton.tsx')), false)
    assert.match(bottomAccountPanel, /components\/loading\/TerminalSkeleton/)
    assert.doesNotMatch(securityContent, /security-timeline/)
    assert.doesNotMatch(mobileDataCollection, /route-data-collection/)
    assert.doesNotMatch(pcDataCollection, /route-data-collection/)
  })
})

function readSource(relativePath: string): string {
  return readFileSync(join(srcRoot, relativePath), 'utf8')
}

function listFiles(directory: string): string[] {
  if (!existsSync(directory)) return []
  return readdirSync(directory).flatMap((entry) => {
    const child = join(directory, entry)
    return statSync(child).isDirectory() ? listFiles(child) : [child]
  })
}
