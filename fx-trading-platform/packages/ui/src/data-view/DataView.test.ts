import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'

import { getNextDataSort, getNextPage } from './dataViewState.ts'

const currentDir = dirname(fileURLToPath(import.meta.url))
const tableSource = readFileSync(resolve(currentDir, 'DataTable.tsx'), 'utf8')
const cardSource = readFileSync(resolve(currentDir, 'DataCardList.tsx'), 'utf8')
const styles = readFileSync(resolve(currentDir, 'DataView.module.css'), 'utf8')

describe('data view state transitions', () => {
  it('starts ascending, toggles the active key and resets a changed key to ascending', () => {
    assert.deepEqual(getNextDataSort({ key: null, direction: 'asc' }, 'price'), { key: 'price', direction: 'asc' })
    assert.deepEqual(getNextDataSort({ key: 'price', direction: 'asc' }, 'price'), { key: 'price', direction: 'desc' })
    assert.deepEqual(getNextDataSort({ key: 'price', direction: 'desc' }, 'symbol'), { key: 'symbol', direction: 'asc' })
  })

  it('clamps pagination transitions to the available page range', () => {
    assert.equal(getNextPage(1, -1, 4), 1)
    assert.equal(getNextPage(2, 1, 4), 3)
    assert.equal(getNextPage(4, 1, 4), 4)
    assert.equal(getNextPage(3, -1, 0), 1)
  })
})

describe('DataTable and DataCardList contracts', () => {
  it('keeps the desktop table controlled and exposes semantic sorting', () => {
    assert.match(tableSource, /export type DataViewColumn<T extends object>/u)
    assert.match(tableSource, /sortKey\?:\s*string \| null/u)
    assert.match(tableSource, /onSort\?:/u)
    assert.match(tableSource, /getSortLabel:/u)
    assert.match(tableSource, /<table/u)
    assert.match(tableSource, /aria-sort=/u)
    assert.match(tableSource, /<tbody/u)
    assert.match(tableSource, /empty/u)
    assert.doesNotMatch(tableSource, /useState/u)
  })

  it('keeps table layout inside the table component instead of a global element reset', () => {
    assert.match(styles, /\.table\s*\{[^}]*border-collapse:\s*collapse/su)
    assert.match(styles, /\.table th,\s*\.table td\s*\{[^}]*white-space:\s*nowrap/su)
    assert.match(styles, /var\(--user-border,\s*var\(--theme-border\)\)/u)
  })

  it('renders the same columns as mobile cards without owning breakpoint selection', () => {
    assert.match(cardSource, /DataViewColumn/u)
    assert.match(cardSource, /<article/u)
    assert.match(cardSource, /ariaLabel:\s*string/u)
    assert.match(cardSource, /empty/u)
    assert.doesNotMatch(`${cardSource}\n${styles}`, /@media/u)
  })
})
