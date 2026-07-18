import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'

const currentDir = dirname(fileURLToPath(import.meta.url))
const projectRoot = resolve(currentDir, '../../../..')
const componentSource = readFileSync(resolve(currentDir, 'Skeleton.tsx'), 'utf8')
const componentStyles = readFileSync(resolve(currentDir, 'Skeleton.module.css'), 'utf8')
const terminalSource = readFileSync(resolve(projectRoot, 'apps/web/src/components/loading/TerminalSkeleton.tsx'), 'utf8')

describe('Skeleton component contract', () => {
  it('is a visual-only primitive with explicit shape and optional dimensions', () => {
    assert.match(componentSource, /export type SkeletonShape = 'line' \| 'block' \| 'circle'/u)
    assert.match(componentSource, /width\?:\s*string \| number/u)
    assert.match(componentSource, /height\?:\s*string \| number/u)
    assert.match(componentSource, /aria-hidden="true"/u)
    assert.match(componentSource, /--skeleton-width/u)
    assert.match(componentSource, /--skeleton-height/u)
    assert.match(componentSource, /styles\[shape\]/u)
    assert.doesNotMatch(componentSource, /react-i18next/u)
  })

  it('owns line, block and circle shapes plus reduced-motion behavior', () => {
    assert.match(componentStyles, /\.line/u)
    assert.match(componentStyles, /\.block/u)
    assert.match(componentStyles, /\.circle/u)
    assert.match(componentStyles, /@keyframes\s+skeleton-shimmer/u)
    assert.match(componentStyles, /@media \(prefers-reduced-motion:\s*reduce\)/u)
  })

  it('is composed by the app-owned terminal loading surfaces', () => {
    assert.match(terminalSource, /import \{ Skeleton \} from '@fx-platform\/ui'/u)
    assert.ok((terminalSource.match(/<Skeleton/gu) ?? []).length >= 3)
    assert.doesNotMatch(terminalSource, /<span\s*\/>/u)
  })
})
