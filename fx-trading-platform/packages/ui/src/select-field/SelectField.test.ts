import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'

import { getSelectFieldKeyTransition } from './selectFieldState.ts'

const currentDir = dirname(fileURLToPath(import.meta.url))
const projectRoot = resolve(currentDir, '../../../..')
const componentSource = readFileSync(resolve(currentDir, 'SelectField.tsx'), 'utf8')
const componentStyles = readFileSync(resolve(currentDir, 'SelectField.module.css'), 'utf8')
const packageIndexSource = readFileSync(resolve(currentDir, '../index.ts'), 'utf8')
const languageSwitcherSource = readFileSync(resolve(projectRoot, 'apps/web/src/components/LanguageSwitcher.tsx'), 'utf8')
const marketsSource = readFileSync(resolve(projectRoot, 'apps/web/src/shared-widgets/market/MarketsContent.tsx'), 'utf8')
const webStyles = readFileSync(resolve(projectRoot, 'apps/web/src/styles.css'), 'utf8')

describe('SelectField keyboard state transitions', () => {
  it('wraps ArrowDown and ArrowUp from both closed and open states', () => {
    assert.deepEqual(
      getSelectFieldKeyTransition({ key: 'ArrowDown', open: false, activeIndex: 2, selectedIndex: 2, optionCount: 3 }),
      { open: true, activeIndex: 0, selectIndex: null, preventDefault: true }
    )
    assert.deepEqual(
      getSelectFieldKeyTransition({ key: 'ArrowUp', open: false, activeIndex: 0, selectedIndex: 0, optionCount: 3 }),
      { open: true, activeIndex: 2, selectIndex: null, preventDefault: true }
    )
    assert.equal(
      getSelectFieldKeyTransition({ key: 'ArrowDown', open: true, activeIndex: 2, selectedIndex: 1, optionCount: 3 }).activeIndex,
      0
    )
    assert.equal(
      getSelectFieldKeyTransition({ key: 'ArrowUp', open: true, activeIndex: 0, selectedIndex: 1, optionCount: 3 }).activeIndex,
      2
    )
  })

  it('opens Home and End on the first and last option', () => {
    assert.deepEqual(
      getSelectFieldKeyTransition({ key: 'Home', open: false, activeIndex: 2, selectedIndex: 2, optionCount: 4 }),
      { open: true, activeIndex: 0, selectIndex: null, preventDefault: true }
    )
    assert.deepEqual(
      getSelectFieldKeyTransition({ key: 'End', open: true, activeIndex: 0, selectedIndex: 0, optionCount: 4 }),
      { open: true, activeIndex: 3, selectIndex: null, preventDefault: true }
    )
  })

  it('opens on Enter or Space and selects the active option when already open', () => {
    assert.deepEqual(
      getSelectFieldKeyTransition({ key: 'Enter', open: false, activeIndex: 0, selectedIndex: 1, optionCount: 3 }),
      { open: true, activeIndex: 1, selectIndex: null, preventDefault: true }
    )
    assert.deepEqual(
      getSelectFieldKeyTransition({ key: ' ', open: true, activeIndex: 2, selectedIndex: 1, optionCount: 3 }),
      { open: false, activeIndex: 2, selectIndex: 2, preventDefault: true }
    )
  })

  it('closes on Escape and Tab without selecting', () => {
    for (const key of ['Escape', 'Tab']) {
      assert.deepEqual(
        getSelectFieldKeyTransition({ key, open: true, activeIndex: 1, selectedIndex: 0, optionCount: 3 }),
        { open: false, activeIndex: 1, selectIndex: null, preventDefault: false }
      )
    }
  })

  it('ignores modified keys and never opens an empty select', () => {
    assert.deepEqual(
      getSelectFieldKeyTransition({
        key: 'ArrowDown',
        open: false,
        activeIndex: 0,
        selectedIndex: 0,
        optionCount: 3,
        modified: true
      }),
      { open: false, activeIndex: 0, selectIndex: null, preventDefault: false }
    )
    assert.deepEqual(
      getSelectFieldKeyTransition({ key: 'Enter', open: false, activeIndex: 0, selectedIndex: -1, optionCount: 0 }),
      { open: false, activeIndex: 0, selectIndex: null, preventDefault: false }
    )
  })
})

describe('SelectField component contract', () => {
  it('owns listbox semantics, labelled-by behavior and outside-click closing', () => {
    assert.match(componentSource, /export type SelectFieldOption/u)
    assert.match(componentSource, /export type SelectFieldProps/u)
    assert.match(componentSource, /aria-haspopup="listbox"/u)
    assert.match(componentSource, /aria-expanded=\{open\}/u)
    assert.match(componentSource, /role="listbox"/u)
    assert.match(componentSource, /role="option"/u)
    assert.match(componentSource, /aria-selected=\{option\.value === value\}/u)
    assert.match(componentSource, /aria-label=\{labelledBy \? undefined : ariaLabel\}/u)
    assert.match(componentSource, /aria-labelledby=\{labelledBy \? `\$\{labelledBy\} \$\{buttonId\}` : undefined\}/u)
    assert.match(componentSource, /disabled=\{options\.length === 0\}/u)
    assert.match(componentSource, /window\.addEventListener\('pointerdown', handlePointerDown\)/u)
    assert.match(componentSource, /rootRef\.current\?\.contains/u)
    assert.doesNotMatch(componentSource, /react-i18next/u)
  })

  it('owns visual states in a CSS Module with preserved values and reduced motion', () => {
    assert.match(componentSource, /import styles from '\.\/SelectField\.module\.css'/u)
    assert.match(componentSource, /styles\.root/u)
    assert.match(componentSource, /styles\.button/u)
    assert.match(componentSource, /styles\.menu/u)
    assert.match(componentSource, /styles\.option/u)
    assert.match(componentStyles, /\.root\s*\{[\s\S]*position:\s*relative/u)
    assert.match(componentStyles, /\.button\s*\{[\s\S]*min-height:\s*var\(--select-button-min-height, 36px\)/u)
    assert.match(componentStyles, /\.root\[data-open='true'\]\s+\.chevron/u)
    assert.match(
      componentStyles,
      /\.menu\s*\{[\s\S]*border:\s*1px solid var\(--select-menu-border, color-mix\(in srgb, var\(--theme-border\) 74%, transparent\)\)/u
    )
    assert.doesNotMatch(componentStyles, /--bn-/u)
    assert.match(componentStyles, /\.option\[aria-selected='true'\]/u)
    assert.match(componentStyles, /@media \(prefers-reduced-motion:\s*reduce\)[\s\S]*\.button/u)
    assert.doesNotMatch(componentStyles, /:global/u)
  })

  it('exports from the package and leaves app consumers on root-only layout hooks', () => {
    assert.match(packageIndexSource, /export \* from '\.\/select-field\/SelectField'/u)
    assert.match(languageSwitcherSource, /from '@fx-platform\/ui'/u)
    assert.match(languageSwitcherSource, /className=\{`language-switcher__select/u)
    assert.match(marketsSource, /from '@fx-platform\/ui'/u)
    assert.match(marketsSource, /className="market-sort-field__select"/u)
    assert.doesNotMatch(`${languageSwitcherSource}\n${marketsSource}`, /components\/SelectField/u)
    assert.doesNotMatch(webStyles, /\.select-field(?:__|\s|\[|\{|\.)/u)
  })
})
