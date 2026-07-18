import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'

const currentDir = dirname(fileURLToPath(import.meta.url))
const projectRoot = resolve(currentDir, '../../../..')
const componentSource = readFileSync(resolve(currentDir, 'IconButton.tsx'), 'utf8')
const componentStyles = readFileSync(resolve(currentDir, 'IconButton.module.css'), 'utf8')
const packageIndexSource = readFileSync(resolve(currentDir, '../index.ts'), 'utf8')
const toolbarSource = readFileSync(resolve(projectRoot, 'apps/web/src/shared-widgets/trading/components/ChartTopToolbar.tsx'), 'utf8')

describe('IconButton component contract', () => {
  it('renders a non-submit accessible button and forwards disabled/custom attributes', () => {
    assert.match(componentSource, /export type IconButtonProps/u)
    assert.match(componentSource, /ButtonHTMLAttributes<HTMLButtonElement>/u)
    assert.match(componentSource, /icon:\s*ReactNode/u)
    assert.match(componentSource, /label:\s*string/u)
    assert.match(componentSource, /tone\?:\s*'neutral' \| 'primary'/u)
    assert.match(componentSource, /className/u)
    assert.match(componentSource, /\.\.\.buttonProps/u)
    assert.match(componentSource, /<button[\s\S]*type="button"[\s\S]*aria-label=\{label\}[\s\S]*title=\{title \?\? label\}/u)
    assert.doesNotMatch(componentSource, /react-i18next/u)
  })

  it('preserves neutral, primary, focus, active and reduced-motion styles', () => {
    assert.match(componentSource, /import styles from '\.\/IconButton\.module\.css'/u)
    assert.match(componentSource, /styles\.button/u)
    assert.match(componentSource, /styles\.primary/u)
    assert.match(
      componentStyles,
      /\.button\s*\{[\s\S]*min-height:\s*var\(--icon-button-min-height, calc\(var\(--space-8\) \+ var\(--space-3\)\)\)/u
    )
    assert.match(componentStyles, /\.button:hover,[\s\S]*\.button:focus-visible/u)
    assert.match(componentStyles, /\.button:active\s*\{[\s\S]*transform:\s*scale\(0\.96\)/u)
    assert.match(componentStyles, /\.primary\s*\{[\s\S]*background:\s*var\(--trading-accent\)/u)
    assert.match(componentStyles, /@media \(prefers-reduced-motion:\s*reduce\)/u)
  })

  it('is exported publicly and used by trading toolbar icon actions', () => {
    assert.match(packageIndexSource, /export \* from '\.\/icon-button\/IconButton'/u)
    assert.match(toolbarSource, /import \{ IconButton \} from '@fx-platform\/ui'/u)
    assert.ok((toolbarSource.match(/<IconButton/gu) ?? []).length >= 4)
    assert.doesNotMatch(toolbarSource, /TerminalIconButton/u)
  })
})
