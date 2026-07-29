import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'

const currentDir = dirname(fileURLToPath(import.meta.url))
const projectRoot = resolve(currentDir, '../../../..')
const componentSource = readFileSync(resolve(currentDir, 'StateSurface.tsx'), 'utf8')
const componentStyles = readFileSync(resolve(currentDir, 'StateSurface.module.css'), 'utf8')
const packageIndexSource = readFileSync(resolve(currentDir, '../index.ts'), 'utf8')
const consumerSource = readFileSync(resolve(projectRoot, 'apps/web/src/shared-widgets/data/PageState.tsx'), 'utf8')

describe('StateSurface component contract', () => {
  it('owns generic state semantics while all user-facing strings remain explicit props', () => {
    assert.match(componentSource, /export type StateSurfaceVariant = 'default' \| 'empty' \| 'login' \| 'error' \| 'loading'/u)
    assert.match(componentSource, /title:\s*string/u)
    assert.match(componentSource, /message\?:\s*string/u)
    assert.match(componentSource, /actionLabel\?:\s*string/u)
    assert.match(componentSource, /variant === 'error' \? 'alert'/u)
    assert.match(componentSource, /aria-live=/u)
    assert.match(componentSource, /data-state-variant=\{variant\}/u)
    assert.match(componentSource, /<Skeleton/u)
    assert.doesNotMatch(componentSource, /react-i18next/u)
  })

  it('owns state variants, action focus and reduced-motion-safe loading layout in a CSS Module', () => {
    assert.match(componentSource, /import styles from '\.\/StateSurface\.module\.css'/u)
    assert.match(componentStyles, /\.surface/u)
    assert.match(componentStyles, /\.error/u)
    assert.match(componentStyles, /\.login/u)
    assert.match(componentStyles, /\.loading/u)
    assert.match(componentStyles, /\.action:focus-visible/u)
    assert.doesNotMatch(componentStyles, /:global/u)
  })

  it('is exported publicly and consumed through the current translating Web adapter', () => {
    assert.match(packageIndexSource, /export \* from '\.\/state-surface\/StateSurface'/u)
    assert.match(consumerSource, /import \{ StateSurface \} from '@fx-platform\/ui'/u)
    assert.match(consumerSource, /useTranslation/u)
    assert.match(consumerSource, /<StateSurface/u)
    assert.doesNotMatch(consumerSource, /state-panel/u)
  })
})
