import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { describe, it } from 'node:test'
import { fileURLToPath } from 'node:url'

import { getNextDataSort, getNextPage } from './dataViewState.ts'

const currentDir = dirname(fileURLToPath(import.meta.url))
const projectRoot = resolve(currentDir, '../../../..')
const tableSource = readFileSync(resolve(currentDir, 'DataTable.tsx'), 'utf8')
const cardSource = readFileSync(resolve(currentDir, 'DataCardList.tsx'), 'utf8')
const styles = readFileSync(resolve(currentDir, 'DataView.module.css'), 'utf8')
const adapterSource = readFileSync(resolve(projectRoot, 'apps/web/src/components/user-page/DataTable.tsx'), 'utf8')

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
    assert.doesNotMatch(tableSource, /react-i18next/u)
  })

  it('renders the same columns as mobile cards without owning breakpoint selection', () => {
    assert.match(cardSource, /DataViewColumn/u)
    assert.match(cardSource, /<article/u)
    assert.match(cardSource, /ariaLabel:\s*string/u)
    assert.match(cardSource, /empty/u)
    assert.doesNotMatch(`${cardSource}\n${styles}`, /@media/u)
    assert.doesNotMatch(cardSource, /react-i18next/u)
  })

  it('keeps translation, row sorting and pagination in the temporary Web adapter only', () => {
    assert.match(adapterSource, /DataCardList/u)
    assert.match(adapterSource, /DataTable as UiDataTable/u)
    assert.match(adapterSource, /useTranslation/u)
    assert.match(adapterSource, /sortRows/u)
    assert.match(adapterSource, /paginateRows/u)
    assert.match(adapterSource, /getNextDataSort/u)
    assert.match(adapterSource, /getNextPage/u)
  })
})
